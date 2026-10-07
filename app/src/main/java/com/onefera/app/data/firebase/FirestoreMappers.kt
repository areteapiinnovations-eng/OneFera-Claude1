package com.onefera.app.data.firebase

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.model.AppNotification
import com.onefera.app.data.model.Comment
import com.onefera.app.data.model.MediaType
import com.onefera.app.data.model.NotificationType
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.PostMedia
import com.onefera.app.data.model.PostType
import com.onefera.app.data.model.PostVisibility
import com.onefera.app.data.model.ProductSummary
import com.onefera.app.data.model.Story
import com.onefera.app.data.model.StoryAudience
import com.onefera.app.data.model.UserSummary
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal const val FIRESTORE_TAG = "OneFeraFirestore"

/** Live query results; errors (e.g. missing index while it builds) are logged and keep the last value. */
internal fun Query.snapshotFlow(): Flow<QuerySnapshot> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) Log.w(FIRESTORE_TAG, "Query listener failed: ${error.code}", error)
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}

/**
 * Like [snapshotFlow] but a failed query (rules, missing index, offline with an empty cache)
 * emits an empty list instead of staying silent, so screens that combine several queries show
 * what they have rather than a skeleton forever.
 */
internal fun Query.documentsFlow(): Flow<List<DocumentSnapshot>> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            Log.w(FIRESTORE_TAG, "Query failed, showing nothing for it: ${error.code}", error)
            trySend(emptyList())
        }
        if (snapshot != null) trySend(snapshot.documents)
    }
    awaitClose { registration.remove() }
}

internal fun com.google.firebase.firestore.DocumentReference.snapshotFlow(): Flow<DocumentSnapshot> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) Log.w(FIRESTORE_TAG, "Document listener failed: ${error.code}", error)
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}

internal fun AuthRepository.uidFlow(): Flow<String?> =
    session.map { (it as? SessionState.SignedIn)?.uid }.distinctUntilChanged()

internal fun AuthRepository.currentUid(): String =
    (session.value as? SessionState.SignedIn)?.uid ?: throw com.onefera.app.data.backend.UserFacingException("Please log in again.")

private fun DocumentSnapshot.millis(field: String): Long =
    getTimestamp(field, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: System.currentTimeMillis()

private fun Any?.asInt(): Int = (this as? Number)?.toInt() ?: 0

internal fun Map<*, *>?.toUserSummary(): UserSummary = UserSummary(
    uid = this?.get("uid") as? String ?: "",
    displayName = this?.get("displayName") as? String ?: "",
    username = this?.get("username") as? String ?: "",
    avatarUrl = this?.get("avatarUrl") as? String,
    verified = this?.get("verified") as? Boolean ?: false,
    isPrivate = this?.get("isPrivate") as? Boolean ?: false,
)

internal fun UserSummary.toMap(): Map<String, Any?> = mapOf(
    "uid" to uid,
    "displayName" to displayName,
    "username" to username,
    "avatarUrl" to avatarUrl,
    "verified" to verified,
    "isPrivate" to isPrivate,
)

internal fun DocumentSnapshot.toUserSummaryDoc(): UserSummary = UserSummary(
    uid = getString("uid") ?: id,
    displayName = getString("displayName").orEmpty(),
    username = getString("username").orEmpty(),
    avatarUrl = getString("avatarUrl"),
    verified = getBoolean("verified") ?: false,
    isPrivate = getBoolean("isPrivate") ?: false,
)

internal fun PostMedia.toMap(): Map<String, Any?> = mapOf(
    "url" to url,
    "type" to type.name,
    "thumbnailUrl" to thumbnailUrl,
    "aspectRatio" to aspectRatio.toDouble(),
)

private fun Map<*, *>.toMedia(): PostMedia = PostMedia(
    url = get("url") as? String ?: "",
    type = runCatching { MediaType.valueOf(get("type") as? String ?: "") }.getOrDefault(MediaType.Image),
    thumbnailUrl = get("thumbnailUrl") as? String,
    aspectRatio = (get("aspectRatio") as? Number)?.toFloat() ?: 0.8f,
)

internal fun DocumentSnapshot.toPost(): Post = Post(
    id = id,
    authorId = getString("authorId").orEmpty(),
    author = (get("author") as? Map<*, *>).toUserSummary(),
    type = runCatching { PostType.valueOf(getString("type").orEmpty()) }.getOrDefault(PostType.Post),
    caption = getString("caption").orEmpty(),
    media = (get("media") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.toMedia() }.orEmpty(),
    location = getString("location").orEmpty(),
    tags = (get("tags") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    soundName = getString("soundName").orEmpty(),
    likeCount = get("likeCount").asInt(),
    commentCount = get("commentCount").asInt(),
    shareCount = get("shareCount").asInt(),
    createdAt = millis("createdAt"),
    visibility = getString("visibility") ?: PostVisibility.PUBLIC,
    products = (get("products") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.toProductSummary() }.orEmpty(),
    hidden = getBoolean("hidden") ?: false,
    commentsOff = getBoolean("commentsOff") ?: false,
    edited = getBoolean("edited") ?: false,
)

internal fun DocumentSnapshot.toComment(postId: String): Comment = Comment(
    id = id,
    postId = postId,
    author = (get("author") as? Map<*, *>).toUserSummary(),
    text = getString("text").orEmpty(),
    createdAt = millis("createdAt"),
    edited = getBoolean("edited") ?: false,
)

internal fun DocumentSnapshot.toStory(): Story = Story(
    id = id,
    author = (get("author") as? Map<*, *>).toUserSummary(),
    mediaUrl = getString("mediaUrl").orEmpty(),
    createdAt = millis("createdAt"),
    expiresAt = getLong("expiresAt") ?: 0L,
    audience = getString("audience") ?: StoryAudience.PUBLIC,
    mentions = (get("mentions") as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.toUserSummary() }.orEmpty(),
    allowReplies = getBoolean("allowReplies") ?: true,
)

internal fun DocumentSnapshot.toNotification(): AppNotification = AppNotification(
    id = id,
    type = runCatching { NotificationType.valueOf(getString("type").orEmpty()) }.getOrDefault(NotificationType.Like),
    actor = (get("actor") as? Map<*, *>).toUserSummary(),
    postId = getString("postId"),
    postThumbUrl = getString("postThumbUrl"),
    text = getString("text").orEmpty(),
    createdAt = millis("createdAt"),
    read = getBoolean("read") ?: false,
)

internal fun ProductSummary.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "brand" to brand,
    "imageUrl" to imageUrl,
    "price" to price,
    "mrp" to mrp,
    "sellerId" to sellerId,
)

internal fun Map<*, *>.toProductSummary(): ProductSummary = ProductSummary(
    id = get("id") as? String ?: "",
    title = get("title") as? String ?: "",
    brand = get("brand") as? String ?: "",
    imageUrl = get("imageUrl") as? String,
    price = (get("price") as? Number)?.toInt() ?: 0,
    mrp = (get("mrp") as? Number)?.toInt() ?: 0,
    sellerId = get("sellerId") as? String ?: "",
)
