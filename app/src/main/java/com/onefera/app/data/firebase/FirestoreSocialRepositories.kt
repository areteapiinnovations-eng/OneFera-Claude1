package com.onefera.app.data.firebase

import android.net.Uri
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.media.MediaProcessor
import com.onefera.app.data.model.AppNotification
import com.onefera.app.data.model.FollowState
import com.onefera.app.data.model.NotificationType
import com.onefera.app.data.model.Story
import com.onefera.app.data.model.StoryAudience
import com.onefera.app.data.model.StoryGroup
import com.onefera.app.data.model.StoryOptions
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.social.NotificationRepository
import com.onefera.app.data.social.SocialRepository
import com.onefera.app.data.social.StoryRepository
import com.onefera.app.data.user.UserSearch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private fun FirebaseFirestore.user(uid: String) = collection("users").document(uid)

private suspend fun FirebaseFirestore.summaryOf(uid: String): UserSummary {
    val doc = user(uid).get().await()
    if (!doc.exists()) throw UserFacingException("Finish setting up your profile first.")
    return doc.toUserSummaryDoc()
}

/**
 * Stories live in `stories/{id}` with an `expiresAt` 24 h after posting and an `audience`
 * (`public` or `followers`); images in Cloud Storage at `stories/{uid}/{id}.jpg`; who has seen a
 * story in `stories/{id}/views/{uid}` (readable by its author only).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreStoryRepository @Inject constructor(
    private val auth: AuthRepository,
    private val media: MediaProcessor,
) : StoryRepository {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }
    private val stories get() = db.collection("stories")

    /**
     * Two kinds of query, both provable by the rules: public stories from anyone, and every story
     * (any audience) from the people the user follows plus their own, chunked for `in` limits.
     */
    override fun storyGroups(): Flow<List<StoryGroup>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) return@flatMapLatest flowOf(emptyList())
        val following = db.user(uid).collection("following").documentsFlow().map { docs -> docs.map { it.id }.toSet() }
        following.flatMapLatest { followingIds ->
            val now = System.currentTimeMillis()
            val public = stories.whereEqualTo("audience", StoryAudience.PUBLIC).whereGreaterThan("expiresAt", now)
                .orderBy("expiresAt", Query.Direction.ASCENDING).limit(200).documentsFlow()
            val circles = (followingIds + uid).toList().take(MAX_STORY_AUTHORS).chunked(30).map { chunk ->
                stories.whereIn("authorId", chunk).whereGreaterThan("expiresAt", now)
                    .orderBy("expiresAt", Query.Direction.ASCENDING).limit(200).documentsFlow()
            }
            combine(listOf(public) + circles) { parts ->
                parts.flatMap { it.toList() }.distinctBy { it.id }.map { it.toStory() }
                    .filter { it.expiresAt > System.currentTimeMillis() }
                    .groupBy { it.author.uid }
                    .map { (authorId, items) -> StoryGroup(items.first().author, items.sortedBy { it.createdAt }, authorId == uid) }
                    // Mine first, then people I follow, then everyone else; newest activity first in each band.
                    .sortedWith(
                        compareByDescending<StoryGroup> { it.isMine }
                            .thenByDescending { it.author.uid in followingIds }
                            .thenByDescending { it.stories.last().createdAt },
                    )
            }
        }
    }

    override suspend fun addStory(image: Uri, options: StoryOptions, onProgress: (Float) -> Unit): Result<Unit> = runFriendly {
        val uid = auth.currentUid()
        val me = db.summaryOf(uid)
        val prepared = media.prepareImage(image, maxSide = 1920)
        try {
            val ref = stories.document()
            val file = storage.reference.child("stories/$uid/${ref.id}.jpg")
            file.putFile(Uri.fromFile(prepared.file), StorageMetadata.Builder().setContentType("image/jpeg").build())
                .addOnProgressListener { onProgress(it.bytesTransferred.toFloat() / it.totalByteCount.coerceAtLeast(1)) }
                .await()
            val url = file.downloadUrl.await().toString()
            val now = System.currentTimeMillis()
            ref.set(
                mapOf(
                    "authorId" to uid,
                    "author" to me.toMap(),
                    "mediaUrl" to url,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "expiresAt" to now + STORY_TTL_MS,
                    // A private account's stories are for followers whatever was picked.
                    "audience" to if (me.isPrivate) StoryAudience.FOLLOWERS else options.audience,
                    "mentions" to options.mentions.filter { it.uid != uid }.distinctBy { it.uid }.take(MAX_MENTIONS).map { it.toMap() },
                    "allowReplies" to options.allowReplies,
                ),
            ).await()
        } finally {
            media.cleanUp(prepared)
        }
    }

    override suspend fun deleteStory(story: Story): Result<Unit> = runFriendly {
        if (story.author.uid != auth.currentUid()) throw UserFacingException("You can only delete your own stories.")
        stories.document(story.id).delete().await()
        // The image and the views list are removed by the onStoryDeleted Cloud Function.
    }

    override suspend fun markViewed(story: Story) {
        val uid = runCatching { auth.currentUid() }.getOrNull() ?: return
        if (story.author.uid == uid) return
        runCatching {
            val me = db.summaryOf(uid)
            stories.document(story.id).collection("views").document(uid)
                .set(me.toMap() + ("viewedAt" to FieldValue.serverTimestamp())).await()
        }.onFailure { Log.w(FIRESTORE_TAG, "Couldn't record story view", it) }
    }

    override fun viewers(storyId: String): Flow<List<UserSummary>> =
        stories.document(storyId).collection("views").orderBy("viewedAt", Query.Direction.DESCENDING).limit(500)
            .documentsFlow().map { docs -> docs.map { it.toUserSummaryDoc().copy(uid = it.id) } }

    companion object {
        const val STORY_TTL_MS = 24 * 60 * 60 * 1000L
        const val MAX_MENTIONS = 10
        const val MAX_STORY_AUTHORS = 300
    }
}

/**
 * Follow graph:
 * - `users/{uid}/following/{targetUid}` and `users/{targetUid}/followers/{uid}` (written together)
 * - `users/{targetUid}/followRequests/{uid}` for private accounts until accepted
 * Follower/following counters and notifications are maintained by Cloud Functions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreSocialRepository @Inject constructor(private val auth: AuthRepository) : SocialRepository {
    private val db by lazy { FirebaseFirestore.getInstance() }

    override fun followState(targetUid: String): Flow<FollowState> = auth.uidFlow().flatMapLatest { uid ->
        when (uid) {
            null -> flowOf(FollowState.None)
            targetUid -> flowOf(FollowState.Self)
            else -> combine(
                db.user(uid).collection("following").document(targetUid).snapshotFlow(),
                db.user(targetUid).collection("followRequests").document(uid).snapshotFlow(),
            ) { following, request ->
                when {
                    following.exists() -> FollowState.Following
                    request.exists() -> FollowState.Requested
                    else -> FollowState.None
                }
            }
        }
    }

    override fun followingIds(): Flow<Set<String>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) flowOf(emptySet()) else db.user(uid).collection("following").snapshotFlow().map { s -> s.documents.map { it.id }.toSet() }
    }

    override suspend fun follow(target: UserSummary): Result<FollowState> = runFriendly {
        val uid = auth.currentUid()
        val me = db.summaryOf(uid)
        // The stored privacy flag wins over the copy on a post or search row, and deleted accounts can't be followed.
        val current = db.user(target.uid).get().await()
        if (!current.exists()) throw UserFacingException("This account no longer exists.")
        if (current.getBoolean("isPrivate") == true) {
            db.user(target.uid).collection("followRequests").document(uid)
                .set(me.toMap() + ("createdAt" to FieldValue.serverTimestamp())).await()
            FollowState.Requested
        } else {
            db.runBatch { b ->
                b.set(db.user(uid).collection("following").document(target.uid), target.toMap() + ("createdAt" to FieldValue.serverTimestamp()))
                b.set(db.user(target.uid).collection("followers").document(uid), me.toMap() + ("createdAt" to FieldValue.serverTimestamp()))
            }.await()
            FollowState.Following
        }
    }

    override suspend fun unfollow(targetUid: String): Result<Unit> = runFriendly {
        val uid = auth.currentUid()
        db.runBatch { b ->
            b.delete(db.user(uid).collection("following").document(targetUid))
            b.delete(db.user(targetUid).collection("followers").document(uid))
        }.await()
    }

    override suspend fun cancelRequest(targetUid: String): Result<Unit> = runFriendly {
        db.user(targetUid).collection("followRequests").document(auth.currentUid()).delete().await()
    }

    override suspend fun respondToRequest(requesterUid: String, accept: Boolean): Result<Unit> = runFriendly {
        val uid = auth.currentUid()
        val requestRef = db.user(uid).collection("followRequests").document(requesterUid)
        val request = requestRef.get().await()
        val pendingNotifications = db.user(uid).collection("notifications")
            .whereEqualTo("type", NotificationType.FollowRequest.name)
            .whereEqualTo("actor.uid", requesterUid).get().await()
        val me = if (accept) db.summaryOf(uid) else null
        db.runBatch { b ->
            if (accept && request.exists() && me != null) {
                b.set(
                    db.user(uid).collection("followers").document(requesterUid),
                    request.toUserSummaryDoc().toMap() + mapOf("createdAt" to FieldValue.serverTimestamp(), "viaRequest" to true),
                )
                b.set(db.user(requesterUid).collection("following").document(uid), me.toMap() + ("createdAt" to FieldValue.serverTimestamp()))
            }
            b.delete(requestRef)
            pendingNotifications.documents.forEach { b.delete(it.reference) }
        }.await()
    }

    private fun edgeList(uid: String, collection: String): Flow<List<UserSummary>> =
        db.user(uid).collection(collection).orderBy("createdAt", Query.Direction.DESCENDING).limit(500)
            .snapshotFlow().map { s -> s.documents.map { it.toUserSummaryDoc().copy(uid = it.id) } }

    override fun followers(uid: String): Flow<List<UserSummary>> = edgeList(uid, "followers")
    override fun following(uid: String): Flow<List<UserSummary>> = edgeList(uid, "following")

    /** People to follow: the newest members (so fresh sign-ups are found) interleaved with the most followed. */
    override fun suggestions(): Flow<List<UserSummary>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) return@flatMapLatest flowOf(emptyList())
        val popular = db.collection("users").orderBy("followersCount", Query.Direction.DESCENDING).limit(40)
            .documentsFlow().map { docs -> docs.map { it.toUserSummaryDoc() } }
        val newest = db.collection("users").orderBy("createdAt", Query.Direction.DESCENDING).limit(20)
            .documentsFlow().map { docs -> docs.map { it.toUserSummaryDoc() } }
        combine(popular, newest, followingIds()) { top, fresh, following ->
            val merged = ArrayList<UserSummary>()
            val a = fresh.iterator()
            val b = top.iterator()
            while (a.hasNext() || b.hasNext()) {
                if (a.hasNext()) merged += a.next()
                if (b.hasNext()) merged += b.next()
            }
            merged.distinctBy { it.uid }.filter { it.uid != uid && it.uid !in following && it.username.isNotEmpty() }.take(15)
        }
    }

    /**
     * Keyword search (`searchKeywords`, written at sign-up) plus the older prefix queries for
     * profiles saved before keywords existed. The three run in parallel; a failure of one is logged,
     * a failure of all is reported to the caller.
     */
    override suspend fun searchUsers(query: String): List<UserSummary> {
        val q = UserSearch.termFor(query)
        if (q.isEmpty()) return emptyList()
        val users = db.collection("users")
        val results = coroutineScope {
            listOf(
                async { users.whereArrayContains("searchKeywords", q).limit(25).get().await() },
                async { users.orderBy("username").startAt(q).endAt(q + "\uf8ff").limit(20).get().await() },
                async { users.orderBy("displayNameLower").startAt(q).endAt(q + "\uf8ff").limit(20).get().await() },
            ).map { runCatching { it.await() } }
        }
        if (results.all { it.isFailure }) {
            Log.w(FIRESTORE_TAG, "User search failed", results.first().exceptionOrNull())
            throw UserFacingException("Couldn't search right now. Check your connection and try again.")
        }
        return results.mapNotNull { it.getOrNull() }.flatMap { it.documents }
            .map { it.toUserSummaryDoc() }
            .filter { it.username.isNotEmpty() }
            .distinctBy { it.uid }
            .sortedWith(compareByDescending<UserSummary> { it.username.startsWith(q) }.thenBy { it.username })
            .take(25)
    }
}

/** `users/{uid}/notifications/{id}`, written by Cloud Functions; the owner may mark them read. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreNotificationRepository @Inject constructor(private val auth: AuthRepository) : NotificationRepository {
    private val db by lazy { FirebaseFirestore.getInstance() }

    override fun notifications(): Flow<List<AppNotification>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            db.user(uid).collection("notifications").orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
                .snapshotFlow().map { s -> s.documents.map { it.toNotification() } }
        }
    }

    override fun unreadCount(): Flow<Int> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) flowOf(0) else db.user(uid).collection("notifications").whereEqualTo("read", false).limit(99)
            .snapshotFlow().map { it.size() }
    }

    override suspend fun markAllRead() {
        val uid = runCatching { auth.currentUid() }.getOrNull() ?: return
        runCatching {
            val unread = db.user(uid).collection("notifications").whereEqualTo("read", false).limit(200).get().await()
            if (!unread.isEmpty) db.runBatch { b -> unread.documents.forEach { b.update(it.reference, "read", true) } }.await()
        }
    }
}
