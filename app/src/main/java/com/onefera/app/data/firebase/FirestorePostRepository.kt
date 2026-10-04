package com.onefera.app.data.firebase

import android.net.Uri
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.media.MediaProcessor
import com.onefera.app.data.media.PreparedMedia
import com.onefera.app.data.model.Comment
import com.onefera.app.data.model.MediaType
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.PostDraft
import com.onefera.app.data.model.PostMedia
import com.onefera.app.data.model.PostType
import com.onefera.app.data.model.PostVisibility
import com.onefera.app.data.model.TagSummary
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.model.extractHashtags
import com.onefera.app.data.social.FeedScope
import com.onefera.app.data.social.PostRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore layout:
 * - `posts/{postId}`                       post document (counters are maintained by Cloud Functions)
 * - `posts/{postId}/likes/{uid}`           one doc per like
 * - `posts/{postId}/comments/{commentId}`
 * - `users/{uid}/likes/{postId}`, `users/{uid}/saved/{postId}`   per-user mirrors for fast lookups
 * - `tags/{tag}`                           `{ postCount }` (Cloud Functions)
 * Media lives in Cloud Storage under `posts/{uid}/{postId}/`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestorePostRepository @Inject constructor(
    private val auth: AuthRepository,
    private val media: MediaProcessor,
) : PostRepository {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }
    private val posts get() = db.collection("posts")

    private fun Query.toPosts(): Flow<List<Post>> = snapshotFlow().map { s -> s.documents.map { it.toPost() } }

    override fun feed(scope: FeedScope): Flow<List<Post>> = when (scope) {
        FeedScope.ForYou -> posts.whereEqualTo("visibility", PostVisibility.PUBLIC)
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(FEED_LIMIT).toPosts()
        FeedScope.Following -> auth.uidFlow().flatMapLatest { uid ->
            if (uid == null) flowOf(emptyList()) else followingIds(uid).flatMapLatest { ids -> postsBy(ids + uid) }
        }
    }

    private fun followingIds(uid: String): Flow<List<String>> =
        db.collection("users").document(uid).collection("following").snapshotFlow().map { s -> s.documents.map { it.id } }

    /** Firestore `in` queries take up to 30 values, so large follow lists are queried in chunks. */
    private fun postsBy(authorIds: List<String>): Flow<List<Post>> {
        val chunks = authorIds.distinct().take(MAX_FOLLOWING_FOR_FEED).chunked(30)
        if (chunks.isEmpty()) return flowOf(emptyList())
        val flows = chunks.map { chunk ->
            posts.whereIn("authorId", chunk).orderBy("createdAt", Query.Direction.DESCENDING).limit(FEED_LIMIT).toPosts()
        }
        return combine(flows) { lists -> lists.flatMap { it }.sortedByDescending { it.createdAt }.take(FEED_LIMIT.toInt()) }
    }

    override fun reels(): Flow<List<Post>> = posts
        .whereEqualTo("type", PostType.Reel.name)
        .whereEqualTo("visibility", PostVisibility.PUBLIC)
        .orderBy("createdAt", Query.Direction.DESCENDING).limit(FEED_LIMIT).toPosts()

    override fun userPosts(uid: String): Flow<List<Post>> =
        posts.whereEqualTo("authorId", uid).orderBy("createdAt", Query.Direction.DESCENDING).limit(PROFILE_LIMIT).toPosts()

    override fun post(postId: String): Flow<Post?> =
        posts.document(postId).snapshotFlow().map { if (it.exists()) it.toPost() else null }

    override fun postsWithTag(tag: String): Flow<List<Post>> = posts
        .whereArrayContains("tags", tag.lowercase())
        .whereEqualTo("visibility", PostVisibility.PUBLIC)
        .orderBy("createdAt", Query.Direction.DESCENDING).limit(FEED_LIMIT).toPosts()

    private fun idSet(collection: String): Flow<Set<String>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptySet())
        } else {
            db.collection("users").document(uid).collection(collection).snapshotFlow().map { s -> s.documents.map { it.id }.toSet() }
        }
    }

    override fun likedIds(): Flow<Set<String>> = idSet("likes")
    override fun savedIds(): Flow<Set<String>> = idSet("saved")

    override fun savedPosts(): Flow<List<Post>> = savedIds().flatMapLatest { ids ->
        val chunks = ids.toList().take(PROFILE_LIMIT.toInt()).chunked(30)
        if (chunks.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(chunks.map { posts.whereIn(FieldPath.documentId(), it).toPosts() }) { lists ->
                lists.flatMap { it }.sortedByDescending { it.createdAt }
            }
        }
    }

    override suspend fun setLiked(post: Post, liked: Boolean): Result<Unit> = runFriendly {
        val uid = auth.currentUid()
        val likeDoc = posts.document(post.id).collection("likes").document(uid)
        val mirror = db.collection("users").document(uid).collection("likes").document(post.id)
        db.runBatch { batch ->
            if (liked) {
                batch.set(likeDoc, mapOf("uid" to uid, "createdAt" to FieldValue.serverTimestamp()))
                batch.set(mirror, mapOf("createdAt" to FieldValue.serverTimestamp()))
            } else {
                batch.delete(likeDoc)
                batch.delete(mirror)
            }
        }.await()
    }

    override suspend fun setSaved(post: Post, saved: Boolean): Result<Unit> = runFriendly {
        val doc = db.collection("users").document(auth.currentUid()).collection("saved").document(post.id)
        if (saved) doc.set(mapOf("createdAt" to FieldValue.serverTimestamp())).await() else doc.delete().await()
    }

    override suspend fun createPost(draft: PostDraft, onProgress: (Float) -> Unit): Result<Post> = runFriendly {
        val uid = auth.currentUid()
        val me = currentUserSummary(uid)
        val ref = posts.document()
        val prepared = mutableListOf<PreparedMedia>()
        try {
            onProgress(0.02f)
            if (draft.isVideo) {
                prepared += media.prepareVideo(draft.mediaUris.first())
            } else {
                draft.mediaUris.take(MediaProcessor.MAX_IMAGES).forEach { prepared += media.prepareImage(it) }
            }
            val totalBytes = prepared.sumOf { it.file.length() + (it.thumbnail?.length() ?: 0L) }.coerceAtLeast(1L)
            var doneBytes = 0L
            val uploaded = prepared.mapIndexed { index, item ->
                val ext = if (draft.isVideo) "mp4" else "jpg"
                val type = if (draft.isVideo) "video/mp4" else "image/jpeg"
                val url = upload("posts/$uid/${ref.id}/$index.$ext", item.file, type) { sent ->
                    onProgress(0.05f + 0.9f * (doneBytes + sent) / totalBytes)
                }
                doneBytes += item.file.length()
                val thumbUrl = item.thumbnail?.let { thumb ->
                    upload("posts/$uid/${ref.id}/${index}_thumb.jpg", thumb, "image/jpeg") {}.also { doneBytes += thumb.length() }
                }
                PostMedia(url = url, type = if (draft.isVideo) MediaType.Video else MediaType.Image, thumbnailUrl = thumbUrl, aspectRatio = item.aspectRatio)
            }
            val post = Post(
                id = ref.id,
                authorId = uid,
                author = me,
                type = if (draft.isVideo && draft.asReel) PostType.Reel else PostType.Post,
                caption = draft.caption.trim(),
                media = uploaded,
                location = draft.location.trim(),
                tags = extractHashtags(draft.caption),
                soundName = if (draft.isVideo) "Original audio · ${me.username}" else "",
                createdAt = System.currentTimeMillis(),
                visibility = if (me.isPrivate) PostVisibility.FOLLOWERS else PostVisibility.PUBLIC,
                products = draft.products.take(Post.MAX_PRODUCT_TAGS),
            )
            ref.set(
                mapOf(
                    "authorId" to uid,
                    "author" to me.toMap(),
                    "type" to post.type.name,
                    "caption" to post.caption,
                    "media" to post.media.map { it.toMap() },
                    "location" to post.location,
                    "tags" to post.tags,
                    "soundName" to post.soundName,
                    "likeCount" to 0,
                    "commentCount" to 0,
                    "shareCount" to 0,
                    "visibility" to post.visibility,
                    "products" to post.products.map { it.toMap() },
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
            onProgress(1f)
            post
        } finally {
            media.cleanUp(*prepared.toTypedArray())
        }
    }

    override suspend fun deletePost(post: Post): Result<Unit> = runFriendly {
        posts.document(post.id).delete().await()
        // Storage files are removed by the onPostDeleted Cloud Function.
    }

    override fun comments(postId: String): Flow<List<Comment>> =
        posts.document(postId).collection("comments").orderBy("createdAt", Query.Direction.ASCENDING).limit(300)
            .snapshotFlow().map { s -> s.documents.map { it.toComment(postId) } }

    override suspend fun addComment(post: Post, text: String): Result<Unit> = runFriendly {
        val uid = auth.currentUid()
        posts.document(post.id).collection("comments").add(
            mapOf(
                "authorId" to uid,
                "author" to currentUserSummary(uid).toMap(),
                "text" to text.trim().take(MAX_COMMENT),
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    override suspend fun searchTags(prefix: String): List<TagSummary> {
        val p = prefix.trim().removePrefix("#").lowercase()
        if (p.isEmpty()) return emptyList()
        return runCatching {
            db.collection("tags").orderBy(FieldPath.documentId()).startAt(p).endAt(p + "").limit(20).get().await()
                .documents.map { TagSummary(it.id, it.getLong("postCount")?.toInt() ?: 0) }
                .sortedByDescending { it.postCount }
        }.getOrDefault(emptyList())
    }

    override suspend fun trendingTags(): List<TagSummary> = runCatching {
        db.collection("tags").orderBy("postCount", Query.Direction.DESCENDING).limit(12).get().await()
            .documents.map { TagSummary(it.id, it.getLong("postCount")?.toInt() ?: 0) }
    }.getOrDefault(emptyList())

    private suspend fun currentUserSummary(uid: String): UserSummary {
        val doc = db.collection("users").document(uid).get().await()
        if (!doc.exists()) throw UserFacingException("Finish setting up your profile first.")
        return doc.toUserSummaryDoc()
    }

    private suspend fun upload(path: String, file: File, contentType: String, onBytes: (Long) -> Unit): String {
        val ref = storage.reference.child(path)
        ref.putFile(Uri.fromFile(file), StorageMetadata.Builder().setContentType(contentType).build())
            .addOnProgressListener { onBytes(it.bytesTransferred) }
            .await()
        return ref.downloadUrl.await().toString()
    }

    companion object {
        const val FEED_LIMIT = 60L
        const val PROFILE_LIMIT = 120L
        const val MAX_FOLLOWING_FOR_FEED = 300
        const val MAX_COMMENT = 500
    }
}

/** Runs [block], converting unexpected errors into a friendly message. */
internal suspend fun <T> runFriendly(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: UserFacingException) {
    Result.failure(e)
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    val friendly = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is UserFacingException }
    Result.failure(friendly ?: UserFacingException("Something went wrong. Check your connection and try again.", e))
}
