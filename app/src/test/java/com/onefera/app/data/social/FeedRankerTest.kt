package com.onefera.app.data.social

import com.onefera.app.data.model.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRankerTest {
    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun post(id: String, author: String, ageHours: Int, likes: Int = 0, comments: Int = 0) =
        Post(id = id, authorId = author, createdAt = now - ageHours * hour, likeCount = likes, commentCount = comments)

    @Test
    fun `engaged posts outrank quiet ones of the same age`() {
        val quiet = post("quiet", "a", ageHours = 1)
        val loved = post("loved", "b", ageHours = 1, likes = 40, comments = 6)
        val ranked = FeedRanker.forYou(listOf(quiet, loved), emptySet(), "me", now)
        assertEquals(listOf("loved", "quiet"), ranked.map { it.id })
    }

    @Test
    fun `fresh posts from people you follow beat older strangers`() {
        val stranger = post("stranger", "x", ageHours = 2, likes = 3)
        val friend = post("friend", "f", ageHours = 2, likes = 1)
        val ranked = FeedRanker.forYou(listOf(stranger, friend), setOf("f"), "me", now)
        assertEquals("friend", ranked.first().id)
    }

    @Test
    fun `very old posts sink even with likes`() {
        val old = post("old", "a", ageHours = 240, likes = 20)
        val recent = post("recent", "b", ageHours = 1, likes = 2)
        assertTrue(FeedRanker.score(recent, emptySet(), "me", now) > FeedRanker.score(old, emptySet(), "me", now))
    }

    @Test
    fun `one author cannot fill the top of the feed`() {
        val posts = (1..5).map { post("a$it", "a", ageHours = 1, likes = 100 - it) } + listOf(post("b1", "b", ageHours = 1, likes = 1))
        val ranked = FeedRanker.forYou(posts, emptySet(), "me", now)
        assertEquals(listOf("a1", "a2", "b1", "a3", "a4", "a5"), ranked.map { it.id })
    }
}
