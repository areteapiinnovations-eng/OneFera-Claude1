package com.onefera.app.data.social

import android.net.Uri
import com.onefera.app.data.model.AppNotification
import com.onefera.app.data.model.Comment
import com.onefera.app.data.model.FollowState
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.PostDraft
import com.onefera.app.data.model.PostEdit
import com.onefera.app.data.model.Story
import com.onefera.app.data.model.StoryGroup
import com.onefera.app.data.model.StoryOptions
import com.onefera.app.data.model.TagSummary
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
    suspend fun updatePost(post: Post, edit: PostEdit): Result<Unit>
    suspend fun setCommentsOff(post: Post, off: Boolean): Result<Unit>
    suspend fun deletePost(post: Post): Result<Unit>

    fun comments(postId: String): Flow<List<Comment>>
    suspend fun addComment(post: Post, text: String): Result<Unit>
    suspend fun editComment(comment: Comment, text: String): Result<Unit>
    /** Allowed for the comment's author and the post's author. */
    suspend fun deleteComment(comment: Comment): Result<Unit>

    suspend fun searchTags(prefix: String): List<TagSummary>
    suspend fun trendingTags(): List<TagSummary>
}

interface StoryRepository {
    /** Active (last 24 h) stories the user may see, grouped by author; the signed-in user's group comes first. */
    fun storyGroups(): Flow<List<StoryGroup>>
    /** Posts a finished (already edited) story image. */
    suspend fun addStory(image: Uri, options: StoryOptions, onProgress: (Float) -> Unit): Result<Unit>
    /** Removes one of the user's own stories. */
    suspend fun deleteStory(story: Story): Result<Unit>
    /** Records that the signed-in user has seen [story] (no-op for their own). */
    suspend fun markViewed(story: Story)
    /** Who has seen one of the user's own stories. */
    fun viewers(storyId: String): Flow<List<UserSummary>>
}

/** Follows, follow requests, people search and suggestions. */
interface SocialRepository {
    fun followState(targetUid: String): Flow<FollowState>
    fun followingIds(): Flow<Set<String>>

    /** Follows a public account, or sends a request to a private one. */
    suspend fun follow(target: UserSummary): Result<FollowState>
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
