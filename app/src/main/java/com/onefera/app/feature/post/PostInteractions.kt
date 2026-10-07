package com.onefera.app.feature.post

import android.content.Context
import android.content.Intent
import com.onefera.app.core.common.AppLinks
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.PostEdit
import com.onefera.app.data.social.PostRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/** A post as shown to the signed-in user, with their like / save state. */
data class FeedItem(val post: Post, val liked: Boolean, val saved: Boolean)

/** Attaches the signed-in user's like / save state to a stream of posts. */
/** Adds like/save state and hides posts from blocked accounts. */
fun Flow<List<Post>>.withInteractions(posts: PostRepository, blocked: Flow<Set<String>> = flowOf(emptySet())): Flow<List<FeedItem>> =
    combine(this, posts.likedIds(), posts.savedIds(), blocked) { list, liked, saved, hidden ->
        list.filter { !it.hidden && it.authorId !in hidden }.map { FeedItem(it, it.id in liked, it.id in saved) }
    }

/** Like / save / delete actions shared by every screen that shows posts. */
class PostActions @Inject constructor(private val posts: PostRepository) {
    suspend fun toggleLike(item: FeedItem): Result<Unit> = posts.setLiked(item.post, !item.liked)
    suspend fun like(item: FeedItem): Result<Unit> = if (item.liked) Result.success(Unit) else posts.setLiked(item.post, true)
    suspend fun toggleSave(item: FeedItem): Result<Unit> = posts.setSaved(item.post, !item.saved)
    suspend fun delete(item: FeedItem): Result<Unit> = posts.deletePost(item.post)
    suspend fun edit(item: FeedItem, edit: PostEdit): Result<Unit> = posts.updatePost(item.post, edit)
    suspend fun toggleComments(item: FeedItem): Result<Unit> = posts.setCommentsOff(item.post, !item.post.commentsOff)
}

fun sharePost(context: Context, post: Post) {
    val text = buildString {
        append("Check out @${post.author.username} on OneFera ✦")
        if (post.caption.isNotBlank()) append("\n\"${post.caption.take(80)}\"")
        append("\n${AppLinks.post(post.id)}")
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share post"))
}
