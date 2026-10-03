package com.onefera.app.data.model

import kotlinx.serialization.Serializable

/** Small, denormalised copy of a user stored on posts, comments, stories and notifications. */
@Serializable
data class UserSummary(
    val uid: String = "",
    val displayName: String = "",
    val username: String = "",
    val avatarUrl: String? = null,
    val verified: Boolean = false,
    val isPrivate: Boolean = false,
)

fun UserProfile.toSummary() = UserSummary(
    uid = uid,
    displayName = displayName,
    username = username,
    avatarUrl = avatarUrl,
    verified = verified,
    isPrivate = isPrivate,
)

@Serializable
enum class PostType { Post, Reel }

@Serializable
enum class MediaType { Image, Video }

@Serializable
data class PostMedia(
    val url: String = "",
    val type: MediaType = MediaType.Image,
    /** Poster frame for videos. */
    val thumbnailUrl: String? = null,
    /** width / height. */
    val aspectRatio: Float = 0.8f,
)

/** `public` posts appear in For You, search and tags; `followers` posts only for approved followers. */
object PostVisibility {
    const val PUBLIC = "public"
    const val FOLLOWERS = "followers"
}

@Serializable
data class Post(
    val id: String = "",
    val authorId: String = "",
    val author: UserSummary = UserSummary(),
    val type: PostType = PostType.Post,
    val caption: String = "",
    val media: List<PostMedia> = emptyList(),
    val location: String = "",
    val tags: List<String> = emptyList(),
    val soundName: String = "",
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val shareCount: Int = 0,
    val createdAt: Long = 0L,
    val visibility: String = PostVisibility.PUBLIC,
    /** Products tagged on the post ("tap to buy"), at most [MAX_PRODUCT_TAGS]. */
    val products: List<ProductSummary> = emptyList(),
) {
    val cover: PostMedia? get() = media.firstOrNull()
    val coverImageUrl: String? get() = cover?.let { it.thumbnailUrl ?: it.url.takeIf { _ -> it.type == MediaType.Image } }
    val isVideo: Boolean get() = cover?.type == MediaType.Video

    companion object {
        const val MAX_PRODUCT_TAGS = 5
    }
}

@Serializable
data class Comment(
    val id: String = "",
    val postId: String = "",
    val author: UserSummary = UserSummary(),
    val text: String = "",
    val createdAt: Long = 0L,
)

@Serializable
data class Story(
    val id: String = "",
    val author: UserSummary = UserSummary(),
    val mediaUrl: String = "",
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
)

/** All active stories of one author, oldest first, as shown in the stories row. */
data class StoryGroup(val author: UserSummary, val stories: List<Story>, val isMine: Boolean)

@Serializable
enum class NotificationType { Like, Comment, Follow, FollowRequest, FollowAccepted }

@Serializable
data class AppNotification(
    val id: String = "",
    val type: NotificationType = NotificationType.Like,
    val actor: UserSummary = UserSummary(),
    val postId: String? = null,
    val postThumbUrl: String? = null,
    val text: String = "",
    val createdAt: Long = 0L,
    val read: Boolean = false,
)

enum class FollowState { Self, None, Requested, Following }

data class TagSummary(val tag: String, val postCount: Int)

/** What the user filled in on the Create screen. */
data class PostDraft(
    val caption: String,
    val location: String,
    val mediaUris: List<android.net.Uri>,
    val isVideo: Boolean,
    val asReel: Boolean,
    val products: List<ProductSummary> = emptyList(),
)

/** Extracts #hashtags (lower-case, without '#'), max 30. */
fun extractHashtags(text: String): List<String> =
    Regex("#([\\p{L}\\p{N}_]{1,40})").findAll(text).map { it.groupValues[1].lowercase() }.distinct().take(30).toList()
