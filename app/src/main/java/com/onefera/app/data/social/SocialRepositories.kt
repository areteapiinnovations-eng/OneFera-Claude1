package com.onefera.app.data.social

import android.net.Uri
import com.onefera.app.data.model.AppNotification
import com.onefera.app.data.model.Comment
import com.onefera.app.data.model.FollowState
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.PostDraft
import com.onefera.app.data.model.StoryGroup
import com.onefera.app.data.model.TagSummary
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.model.UserSummary
import kotlinx.coroutines.flow.Flow

enum class FeedScope { ForYou, Following }

/** Posts, reels, likes, saves and comments. */
interface PostRepository {
    fun feed(scope: FeedScope): Flow<List<Post>>
    fun reels(): Flow<List<Post>>
    fun userPosts(uid: String): Flow<List<Post>>
    fun post(postId: String): Flow<Post?>
    fun postsWithTag(tag: String): Flow<List<Post>>
    fun savedPosts(): Flow<List<Post>>

    /** IDs of posts the signed-in user has liked / saved. */
    fun likedIds(): Flow<Set<String>>
    fun savedIds(): Flow<Set<String>>

    suspend fun setLiked(post: Post, liked: Boolean): Result<Unit>
    suspend fun setSaved(post: Post, saved: Boolean): Result<Unit>
    suspend fun createPost(draft: PostDraft, onProgress: (Float) -> Unit): Result<Post>
    suspend fun deletePost(post: Post): Result<Unit>

    fun comments(postId: String): Flow<List<Comment>>
    suspend fun addComment(post: Post, text: String): Result<Unit>

    suspend fun searchTags(prefix: String): List<TagSummary>
}

interface StoryRepository {
    /** Active (last 24 h) stories grouped by author; the signed-in user's group comes first. */
    fun storyGroups(): Flow<List<StoryGroup>>
    suspend fun addStory(image: Uri, onProgress: (Float) -> Unit): Result<Unit>
}

/** Follows, follow requests, people search and suggestions. */
interface SocialRepository {
    fun followState(targetUid: String): Flow<FollowState>
    fun followingIds(): Flow<Set<String>>

    /** Follows a public account, or sends a request to a private one. */
    suspend fun follow(target: UserProfile): Result<FollowState>
    suspend fun unfollow(targetUid: String): Result<Unit>
    suspend fun cancelRequest(targetUid: String): Result<Unit>
    suspend fun respondToRequest(requesterUid: String, accept: Boolean): Result<Unit>

    fun followers(uid: String): Flow<List<UserSummary>>
    fun following(uid: String): Flow<List<UserSummary>>
    fun suggestions(): Flow<List<UserSummary>>
    suspend fun searchUsers(query: String): List<UserSummary>
}

interface NotificationRepository {
    fun notifications(): Flow<List<AppNotification>>
    fun unreadCount(): Flow<Int>
    suspend fun markAllRead()
}
