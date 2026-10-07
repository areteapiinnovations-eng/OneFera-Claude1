package com.onefera.app.data.demo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.onefera.app.data.backend.ApplicationScope
import com.onefera.app.data.model.AppNotification
import com.onefera.app.data.model.AuraGrade
import com.onefera.app.data.model.Comment
import com.onefera.app.data.model.NotificationType
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.Story
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.model.toSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.demoSocialStore: DataStore<Preferences> by preferencesDataStore(name = "demo_social")

/**
 * On-device social graph for demo mode: posts, likes, saves, comments, stories, follows and
 * notifications. It mirrors what the Cloud Functions do in production (counters, notifications)
 * and simulates a little engagement on the user's own posts so the app feels alive.
 */
@Singleton
class DemoSocialBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val accounts: DemoBackend,
    private val catalog: DemoCatalog,
) {
    @Serializable
    data class SocialState(
        val seedVersion: Int = 0,
        val posts: List<Post> = emptyList(),
        val comments: List<Comment> = emptyList(),
        val likes: Map<String, Set<String>> = emptyMap(),
        val saves: Map<String, Set<String>> = emptyMap(),
        val stories: List<Story> = emptyList(),
        /** Follow edges as "followerUid>targetUid". */
        val follows: Set<String> = emptySet(),
        /** Pending follow requests as "requesterUid>targetUid". */
        val requests: Set<String> = emptySet(),
        val notifications: Map<String, List<AppNotification>> = emptyMap(),
        val welcomed: Set<String> = emptySet(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val state = MutableStateFlow<SocialState?>(null)
    val data: Flow<SocialState> = state.filterNotNull()

    init {
        scope.launch {
            val saved = context.demoSocialStore.data.first()[KEY]
                ?.let { runCatching { json.decodeFromString(SocialState.serializer(), it) }.getOrNull() }
            val now = System.currentTimeMillis()
            var s = if (saved == null || saved.seedVersion < DemoSeed.VERSION) {
                SocialState(
                    seedVersion = DemoSeed.VERSION,
                    posts = DemoSeed.posts(now, catalog.byId) + saved?.posts.orEmpty().filterNot { it.id.startsWith("seed-") },
                    comments = DemoSeed.comments(now),
                    stories = DemoSeed.stories(now),
                )
            } else {
                saved
            }
            // Keep the demo stories row populated: refresh seeded stories once they expire.
            if (s.stories.none { it.id.startsWith("seed-story") && it.expiresAt > now }) {
                s = s.copy(stories = s.stories.filterNot { it.id.startsWith("seed-story") } + DemoSeed.stories(now))
            }
            state.value = s
        }
    }

    suspend fun snapshot(): SocialState = data.first()

    fun <T> select(selector: (SocialState) -> T): Flow<T> = data.map(selector).distinctUntilChanged()

    suspend fun <T> update(block: (SocialState) -> Pair<SocialState, T>): T = mutex.withLock {
        val (next, result) = block(snapshot())
        state.value = next
        context.demoSocialStore.edit { it[KEY] = json.encodeToString(SocialState.serializer(), next) }
        result
    }

    private fun SocialState.notify(uid: String, n: AppNotification): SocialState =
        copy(notifications = notifications + (uid to (listOf(n) + notifications[uid].orEmpty()).take(100)))

    private fun newNotification(type: NotificationType, actor: UserSummary, text: String, post: Post? = null) = AppNotification(
        id = UUID.randomUUID().toString(),
        type = type,
        actor = actor,
        postId = post?.id,
        postThumbUrl = post?.coverImageUrl,
        text = text,
        createdAt = System.currentTimeMillis(),
    )

    private suspend fun addAura(uid: String, points: Int) =
        accounts.adjustProfile(uid) { it.copy(auraPoints = (it.auraPoints + points).coerceIn(0, AuraGrade.MAX_POINTS)) }

    /** First visit of a user: a couple of followers, a pending request and their notifications. */
    suspend fun ensureWelcome(uid: String) {
        if (uid in snapshot().welcomed || uid.startsWith("demo-") && uid != "demo-founder") return
        val zoya = DemoSeed.creators.first { it.uid == "demo-zoya" }.toSummary()
        val aanya = DemoSeed.creators.first { it.uid == "demo-aanya" }.toSummary()
        val rohan = DemoSeed.creators.first { it.uid == "demo-rohan" }.toSummary()
        val added = update { s ->
            if (uid in s.welcomed) return@update s to false
            var next = s.copy(
                welcomed = s.welcomed + uid,
                follows = s.follows + "${zoya.uid}>$uid" + "${aanya.uid}>$uid",
                requests = s.requests + "${rohan.uid}>$uid",
            )
            next = next.notify(uid, newNotification(NotificationType.Follow, aanya, "started following you"))
            next = next.notify(uid, newNotification(NotificationType.Follow, zoya, "started following you"))
            next = next.notify(uid, newNotification(NotificationType.FollowRequest, rohan, "requested to follow you"))
            next to true
        }
        if (added) accounts.adjustProfile(uid) { it.copy(followersCount = it.followersCount + 2) }
    }

    suspend fun setLiked(uid: String, post: Post, liked: Boolean) {
        update { s ->
            val mine = s.likes[uid].orEmpty()
            if (liked == post.id in mine) return@update s to Unit
            val delta = if (liked) 1 else -1
            s.copy(
                likes = s.likes + (uid to if (liked) mine + post.id else mine - post.id),
                posts = s.posts.map { if (it.id == post.id) it.copy(likeCount = (it.likeCount + delta).coerceAtLeast(0)) else it },
            ) to Unit
        }
    }

    suspend fun setSaved(uid: String, postId: String, saved: Boolean) {
        update { s ->
            val mine = s.saves[uid].orEmpty()
            s.copy(saves = s.saves + (uid to if (saved) mine + postId else mine - postId)) to Unit
        }
    }

    suspend fun addPost(post: Post) {
        update { s -> s.copy(posts = listOf(post) + s.posts) to Unit }
        accounts.adjustProfile(post.authorId) { it.copy(postsCount = it.postsCount + 1) }
        addAura(post.authorId, 10)
        simulateEngagement(post)
    }

    suspend fun deletePost(post: Post) {
        update { s -> s.copy(posts = s.posts.filterNot { it.id == post.id }, comments = s.comments.filterNot { it.postId == post.id }) to Unit }
        accounts.adjustProfile(post.authorId) { it.copy(postsCount = (it.postsCount - 1).coerceAtLeast(0)) }
    }

    suspend fun addComment(author: UserSummary, post: Post, text: String) {
        val comment = Comment(UUID.randomUUID().toString(), post.id, author, text, System.currentTimeMillis())
        update { s ->
            var next = s.copy(
                comments = s.comments + comment,
                posts = s.posts.map { if (it.id == post.id) it.copy(commentCount = it.commentCount + 1) else it },
            )
            if (post.authorId != author.uid) {
                next = next.notify(post.authorId, newNotification(NotificationType.Comment, author, "commented: $text", post))
            }
            next to Unit
        }
    }

    suspend fun updatePost(postId: String, transform: (Post) -> Post) {
        update { s -> s.copy(posts = s.posts.map { if (it.id == postId) transform(it) else it }) to Unit }
    }

    suspend fun editComment(commentId: String, text: String) {
        update { s -> s.copy(comments = s.comments.map { if (it.id == commentId) it.copy(text = text, edited = true) else it }) to Unit }
    }

    suspend fun deleteComment(comment: Comment) {
        update { s ->
            s.copy(
                comments = s.comments.filterNot { it.id == comment.id },
                posts = s.posts.map { if (it.id == comment.postId) it.copy(commentCount = (it.commentCount - 1).coerceAtLeast(0)) else it },
            ) to Unit
        }
    }

    suspend fun addStory(story: Story) {
        update { s -> s.copy(stories = s.stories + story) to Unit }
    }

    suspend fun deleteStory(storyId: String) {
        update { s -> s.copy(stories = s.stories.filterNot { it.id == storyId }) to Unit }
    }

    suspend fun follow(me: UserSummary, target: UserSummary, isPrivate: Boolean) {
        val edge = "${me.uid}>${target.uid}"
        if (isPrivate) {
            update { s -> s.copy(requests = s.requests + edge) to Unit }
            // Simulate the creator approving the request a few seconds later.
            scope.launch {
                delay(6_000)
                if (edge !in snapshot().requests) return@launch
                update { s ->
                    s.copy(requests = s.requests - edge, follows = s.follows + edge)
                        .notify(me.uid, newNotification(NotificationType.FollowAccepted, target, "accepted your follow request")) to Unit
                }
                adjustFollowCounts(me.uid, target.uid, +1)
            }
        } else {
            val added = update { s -> if (edge in s.follows) s to false else s.copy(follows = s.follows + edge) to true }
            if (added) adjustFollowCounts(me.uid, target.uid, +1)
        }
    }

    suspend fun unfollow(meUid: String, targetUid: String) {
        val removed = update { s -> val e = "$meUid>$targetUid"; if (e in s.follows) s.copy(follows = s.follows - e) to true else s to false }
        if (removed) adjustFollowCounts(meUid, targetUid, -1)
    }

    suspend fun cancelRequest(meUid: String, targetUid: String) {
        update { s -> s.copy(requests = s.requests - "$meUid>$targetUid") to Unit }
    }

    suspend fun respond(meUid: String, requesterUid: String, accept: Boolean) {
        val edge = "$requesterUid>$meUid"
        update { s ->
            var next = s.copy(
                requests = s.requests - edge,
                notifications = s.notifications + (meUid to s.notifications[meUid].orEmpty()
                    .filterNot { it.type == NotificationType.FollowRequest && it.actor.uid == requesterUid }),
            )
            if (accept) next = next.copy(follows = next.follows + edge)
            next to Unit
        }
        if (accept) adjustFollowCounts(requesterUid, meUid, +1)
    }

    suspend fun markAllRead(uid: String) {
        update { s -> s.copy(notifications = s.notifications + (uid to s.notifications[uid].orEmpty().map { it.copy(read = true) })) to Unit }
    }

    private suspend fun adjustFollowCounts(followerUid: String, targetUid: String, delta: Int) {
        accounts.adjustProfile(followerUid) { it.copy(followingCount = (it.followingCount + delta).coerceAtLeast(0)) }
        accounts.adjustProfile(targetUid) { it.copy(followersCount = (it.followersCount + delta).coerceAtLeast(0)) }
    }

    /** A few seed creators "react" to the user's new post: likes, a comment and Aura. */
    private fun simulateEngagement(post: Post) {
        scope.launch {
            val fans = DemoSeed.creators.filterNot { it.isPrivate }.shuffled().take(3)
            fans.forEachIndexed { i, fan ->
                delay(if (i == 0) 4_000 else 3_000)
                val actor = fan.toSummary()
                update { s ->
                    if (s.posts.none { it.id == post.id }) return@update s to Unit
                    s.copy(posts = s.posts.map { if (it.id == post.id) it.copy(likeCount = it.likeCount + 1) else it })
                        .notify(post.authorId, newNotification(NotificationType.Like, actor, "liked your post", post)) to Unit
                }
                addAura(post.authorId, 2)
            }
            delay(3_000)
            if (snapshot().posts.any { it.id == post.id }) {
                addComment(fans.first().toSummary(), post, DemoSeed.reactions.random())
                addAura(post.authorId, 3)
            }
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("state")
    }
}
