package com.onefera.app.data.firebase

import android.net.Uri
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
import com.onefera.app.data.model.StoryGroup
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.model.toSummary
import com.onefera.app.data.social.NotificationRepository
import com.onefera.app.data.social.SocialRepository
import com.onefera.app.data.social.StoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Stories live in `stories/{id}` with an `expiresAt` 24 h after posting; images in `stories/{uid}/`. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreStoryRepository @Inject constructor(
    private val auth: AuthRepository,
    private val media: MediaProcessor,
) : StoryRepository {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    override fun storyGroups(): Flow<List<StoryGroup>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) return@flatMapLatest flowOf(emptyList())
        val following = db.user(uid).collection("following").snapshotFlow().map { s -> s.documents.map { it.id }.toSet() }
        val stories = db.collection("stories")
            .whereGreaterThan("expiresAt", System.currentTimeMillis())
            .orderBy("expiresAt", Query.Direction.ASCENDING).limit(200)
            .snapshotFlow().map { s -> s.documents.map { it.toStory() } }
        combine(stories, following) { list, followingIds ->
            list.filter { it.expiresAt > System.currentTimeMillis() }
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

    override suspend fun addStory(image: Uri, onProgress: (Float) -> Unit): Result<Unit> = runFriendly {
        val uid = auth.currentUid()
        val me = db.summaryOf(uid)
        val prepared = media.prepareImage(image, maxSide = 1600)
        try {
            val ref = db.collection("stories").document()
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
                ),
            ).await()
        } finally {
            media.cleanUp(prepared)
        }
    }

    companion object {
        const val STORY_TTL_MS = 24 * 60 * 60 * 1000L
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

    override suspend fun follow(target: UserProfile): Result<FollowState> = runFriendly {
        val uid = auth.currentUid()
        val me = db.summaryOf(uid)
        if (target.isPrivate) {
            db.user(target.uid).collection("followRequests").document(uid)
                .set(me.toMap() + ("createdAt" to FieldValue.serverTimestamp())).await()
            FollowState.Requested
        } else {
            db.runBatch { b ->
                b.set(db.user(uid).collection("following").document(target.uid), target.toSummary().toMap() + ("createdAt" to FieldValue.serverTimestamp()))
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

    override fun suggestions(): Flow<List<UserSummary>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) return@flatMapLatest flowOf(emptyList())
        combine(
            db.collection("users").orderBy("followersCount", Query.Direction.DESCENDING).limit(40)
                .snapshotFlow().map { s -> s.documents.map { it.toUserSummaryDoc() } },
            followingIds(),
        ) { users, following -> users.filter { it.uid != uid && it.uid !in following && it.username.isNotEmpty() }.take(15) }
    }

    override suspend fun searchUsers(query: String): List<UserSummary> {
        val q = query.trim().removePrefix("@").lowercase()
        if (q.isEmpty()) return emptyList()
        return runCatching {
            val byHandle = db.collection("users").orderBy("username").startAt(q).endAt(q + "").limit(20).get().await()
            val byName = db.collection("users").orderBy("displayNameLower").startAt(q).endAt(q + "").limit(20).get().await()
            (byHandle.documents + byName.documents).map { it.toUserSummaryDoc() }.distinctBy { it.uid }.take(25)
        }.getOrDefault(emptyList())
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
