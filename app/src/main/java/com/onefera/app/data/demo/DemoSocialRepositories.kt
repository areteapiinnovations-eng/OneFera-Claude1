package com.onefera.app.data.demo

import android.content.Context
import android.net.Uri
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.media.MediaProcessor
import com.onefera.app.data.media.PreparedMedia
import com.onefera.app.data.model.AppNotification
import com.onefera.app.data.model.Comment
import com.onefera.app.data.model.FollowState
import com.onefera.app.data.model.MediaType
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.PostDraft
import com.onefera.app.data.model.PostMedia
import com.onefera.app.data.model.PostType
import com.onefera.app.data.model.PostVisibility
import com.onefera.app.data.model.Story
import com.onefera.app.data.model.StoryGroup
import com.onefera.app.data.model.TagSummary
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.model.extractHashtags
import com.onefera.app.data.model.toSummary
import com.onefera.app.data.social.FeedScope
import com.onefera.app.data.social.NotificationRepository
import com.onefera.app.data.social.PostRepository
import com.onefera.app.data.social.SocialRepository
import com.onefera.app.data.social.StoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private suspend fun AuthRepository.demoUid(): String =
    (session.value as? com.onefera.app.data.auth.SessionState.SignedIn)?.uid ?: throw UserFacingException("Please log in again.")

private suspend fun DemoBackend.summary(uid: String): UserSummary =
    profileNow(uid)?.toSummary() ?: throw UserFacingException("Finish setting up your profile first.")

/** Whether [viewer] may see [post] (public, own post, or an approved follower of a private author). */
private fun DemoSocialBackend.SocialState.canSee(viewer: String?, post: Post) =
    post.visibility == PostVisibility.PUBLIC || post.authorId == viewer || "$viewer>${post.authorId}" in follows

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DemoPostRepository @Inject constructor(
    private val social: DemoSocialBackend,
    private val accounts: DemoBackend,
    private val auth: AuthRepository,
    private val media: MediaProcessor,
    @ApplicationContext private val context: Context,
) : PostRepository {

    private fun visiblePosts(filter: (DemoSocialBackend.SocialState, String?, Post) -> Boolean): Flow<List<Post>> =
        auth.uidFlow().flatMapLatest { uid ->
            social.select { s -> s.posts.filter { s.canSee(uid, it) && filter(s, uid, it) }.sortedByDescending { it.createdAt } }
        }

    override fun feed(scope: FeedScope): Flow<List<Post>> = when (scope) {
        FeedScope.ForYou -> visiblePosts { _, _, p -> p.visibility == PostVisibility.PUBLIC }
        FeedScope.Following -> visiblePosts { s, uid, p -> p.authorId == uid || "$uid>${p.authorId}" in s.follows }
    }

    override fun reels(): Flow<List<Post>> = visiblePosts { _, _, p -> p.type == PostType.Reel }

    override fun userPosts(uid: String): Flow<List<Post>> = visiblePosts { _, _, p -> p.authorId == uid }

    override fun post(postId: String): Flow<Post?> = social.select { s -> s.posts.firstOrNull { it.id == postId } }

    override fun postsWithTag(tag: String): Flow<List<Post>> =
        visiblePosts { _, _, p -> tag.lowercase().removePrefix("#") in p.tags && p.visibility == PostVisibility.PUBLIC }

    override fun savedPosts(): Flow<List<Post>> = auth.uidFlow().flatMapLatest { uid ->
        social.select { s ->
            val ids = s.saves[uid].orEmpty()
            s.posts.filter { it.id in ids && s.canSee(uid, it) }
        }
    }

    override fun likedIds(): Flow<Set<String>> = auth.uidFlow().flatMapLatest { uid -> social.select { it.likes[uid].orEmpty() } }
    override fun savedIds(): Flow<Set<String>> = auth.uidFlow().flatMapLatest { uid -> social.select { it.saves[uid].orEmpty() } }

    override suspend fun setLiked(post: Post, liked: Boolean): Result<Unit> = runCatching { social.setLiked(auth.demoUid(), post, liked) }
    override suspend fun setSaved(post: Post, saved: Boolean): Result<Unit> = runCatching { social.setSaved(auth.demoUid(), post.id, saved) }

    override suspend fun createPost(draft: PostDraft, onProgress: (Float) -> Unit): Result<Post> = runCatching {
        val uid = auth.demoUid()
        val me = accounts.summary(uid)
        val id = "post-" + UUID.randomUUID().toString().take(12)
        onProgress(0.05f)
        val prepared: List<PreparedMedia> = if (draft.isVideo) {
            listOf(media.prepareVideo(draft.mediaUris.first()))
        } else {
            draft.mediaUris.take(MediaProcessor.MAX_IMAGES).map { media.prepareImage(it) }
        }
        val stored = withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "demo_media").apply { mkdirs() }
            prepared.mapIndexed { i, item ->
                onProgress(0.2f + 0.7f * i / prepared.size)
                val file = File(dir, "$id-$i.${if (draft.isVideo) "mp4" else "jpg"}")
                item.file.copyTo(file, overwrite = true)
                val thumb = item.thumbnail?.let { t -> File(dir, "$id-$i-thumb.jpg").also { t.copyTo(it, overwrite = true) } }
                PostMedia(
                    url = Uri.fromFile(file).toString(),
                    type = if (draft.isVideo) MediaType.Video else MediaType.Image,
                    thumbnailUrl = thumb?.let { Uri.fromFile(it).toString() },
                    aspectRatio = item.aspectRatio,
                )
            }
        }
        media.cleanUp(*prepared.toTypedArray())
        delay(400)
        val post = Post(
            id = id,
            authorId = uid,
            author = me,
            type = if (draft.isVideo && draft.asReel) PostType.Reel else PostType.Post,
            caption = draft.caption.trim(),
            media = stored,
            location = draft.location.trim(),
            tags = extractHashtags(draft.caption),
            soundName = if (draft.isVideo) "Original audio · ${me.username}" else "",
            createdAt = System.currentTimeMillis(),
            visibility = if (me.isPrivate) PostVisibility.FOLLOWERS else PostVisibility.PUBLIC,
        )
        social.addPost(post)
        onProgress(1f)
        post
    }

    override suspend fun deletePost(post: Post): Result<Unit> = runCatching {
        if (post.authorId != auth.demoUid()) throw UserFacingException("You can only delete your own posts.")
        social.deletePost(post)
    }

    override fun comments(postId: String): Flow<List<Comment>> =
        social.select { s -> s.comments.filter { it.postId == postId }.sortedBy { it.createdAt } }

    override suspend fun addComment(post: Post, text: String): Result<Unit> = runCatching {
        social.addComment(accounts.summary(auth.demoUid()), post, text.trim().take(500))
    }

    override suspend fun searchTags(prefix: String): List<TagSummary> {
        val p = prefix.trim().removePrefix("#").lowercase()
        if (p.isEmpty()) return emptyList()
        return tagCounts(social.snapshot().posts).filter { it.tag.startsWith(p) }.take(20)
    }

    override suspend fun trendingTags(): List<TagSummary> = tagCounts(social.snapshot().posts).take(12)
}

private fun tagCounts(posts: List<Post>): List<TagSummary> =
    posts.filter { it.visibility == PostVisibility.PUBLIC }.flatMap { it.tags }
        .groupingBy { it }.eachCount().map { (tag, count) -> TagSummary(tag, count) }
        .sortedByDescending { it.postCount }

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DemoStoryRepository @Inject constructor(
    private val social: DemoSocialBackend,
    private val accounts: DemoBackend,
    private val auth: AuthRepository,
    private val media: MediaProcessor,
    @ApplicationContext private val context: Context,
) : StoryRepository {

    override fun storyGroups(): Flow<List<StoryGroup>> = auth.uidFlow().flatMapLatest { uid ->
        social.select { s ->
            val now = System.currentTimeMillis()
            s.stories.filter { it.expiresAt > now }
                .groupBy { it.author.uid }
                .map { (authorId, items) -> StoryGroup(items.first().author, items.sortedBy { it.createdAt }, authorId == uid) }
                .sortedWith(
                    compareByDescending<StoryGroup> { it.isMine }
                        .thenByDescending { "$uid>${it.author.uid}" in s.follows }
                        .thenByDescending { it.stories.last().createdAt },
                )
        }
    }

    override suspend fun addStory(image: Uri, onProgress: (Float) -> Unit): Result<Unit> = runCatching {
        val uid = auth.demoUid()
        val me = accounts.summary(uid)
        onProgress(0.1f)
        val prepared = media.prepareImage(image, maxSide = 1600)
        val id = "story-" + UUID.randomUUID().toString().take(12)
        val file = withContext(Dispatchers.IO) {
            File(File(context.filesDir, "demo_media").apply { mkdirs() }, "$id.jpg").also { prepared.file.copyTo(it, overwrite = true) }
        }
        media.cleanUp(prepared)
        onProgress(0.8f)
        val now = System.currentTimeMillis()
        social.addStory(Story(id, me, Uri.fromFile(file).toString(), now, now + 24 * 60 * 60 * 1000L))
        onProgress(1f)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DemoSocialRepository @Inject constructor(
    private val social: DemoSocialBackend,
    private val accounts: DemoBackend,
    private val auth: AuthRepository,
) : SocialRepository {

    override fun followState(targetUid: String): Flow<FollowState> = auth.uidFlow().flatMapLatest { uid ->
        social.select { s ->
            when {
                uid == targetUid -> FollowState.Self
                "$uid>$targetUid" in s.follows -> FollowState.Following
                "$uid>$targetUid" in s.requests -> FollowState.Requested
                else -> FollowState.None
            }
        }
    }

    override fun followingIds(): Flow<Set<String>> = auth.uidFlow().flatMapLatest { uid ->
        social.select { s -> s.follows.filter { it.startsWith("$uid>") }.map { it.substringAfter('>') }.toSet() }
    }

    override suspend fun follow(target: UserSummary): Result<FollowState> = runCatching {
        val me = accounts.summary(auth.demoUid())
        // Use the stored profile so the privacy flag is current.
        val isPrivate = accounts.profileNow(target.uid)?.isPrivate ?: target.isPrivate
        social.follow(me, target, isPrivate)
        if (isPrivate) FollowState.Requested else FollowState.Following
    }

    override suspend fun unfollow(targetUid: String): Result<Unit> = runCatching { social.unfollow(auth.demoUid(), targetUid) }
    override suspend fun cancelRequest(targetUid: String): Result<Unit> = runCatching { social.cancelRequest(auth.demoUid(), targetUid) }
    override suspend fun respondToRequest(requesterUid: String, accept: Boolean): Result<Unit> =
        runCatching { social.respond(auth.demoUid(), requesterUid, accept) }

    private fun edges(uid: String, followers: Boolean): Flow<List<UserSummary>> =
        combine(social.select { it.follows }, accounts.profiles) { follows, profiles ->
            val ids = follows.mapNotNull { edge ->
                val (from, to) = edge.split('>')
                if (followers && to == uid) from else if (!followers && from == uid) to else null
            }.toSet()
            profiles.filter { it.uid in ids && it.username.isNotEmpty() }.map { it.toSummary() }
        }

    override fun followers(uid: String): Flow<List<UserSummary>> = edges(uid, followers = true)
    override fun following(uid: String): Flow<List<UserSummary>> = edges(uid, followers = false)

    override fun suggestions(): Flow<List<UserSummary>> = auth.uidFlow().flatMapLatest { uid ->
        combine(accounts.profiles, followingIds()) { profiles, following ->
            profiles.filter { it.uid != uid && it.uid !in following && it.username.isNotEmpty() }
                .sortedByDescending { it.followersCount }.map { it.toSummary() }
        }
    }

    override suspend fun searchUsers(query: String): List<UserSummary> {
        val q = query.trim().removePrefix("@").lowercase()
        if (q.isEmpty()) return emptyList()
        delay(150)
        return accounts.profiles.first()
            .filter { it.username.isNotEmpty() && (it.username.contains(q) || it.displayName.lowercase().contains(q)) }
            .sortedByDescending { it.followersCount }.map { it.toSummary() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DemoNotificationRepository @Inject constructor(
    private val social: DemoSocialBackend,
    private val auth: AuthRepository,
) : NotificationRepository {

    override fun notifications(): Flow<List<AppNotification>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            social.select { it.notifications[uid].orEmpty() }.onStart { social.ensureWelcome(uid) }
        }
    }

    override fun unreadCount(): Flow<Int> = notifications().map { list -> list.count { !it.read } }

    override suspend fun markAllRead() {
        runCatching { social.markAllRead(auth.demoUid()) }
    }
}
