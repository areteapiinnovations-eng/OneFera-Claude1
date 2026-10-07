package com.onefera.app.data.social

import com.onefera.app.data.model.Post
import kotlin.math.ln

/**
 * Orders the For You feed. Pure and deterministic so it can be unit-tested; the inputs are the
 * newest public posts plus who the viewer follows.
 *
 * Score = engagement (likes, comments, shares, with diminishing returns) + a boost for people the
 * viewer follows, minus a freshness decay of one point per [HALF_DAY_HOURS] hours. Posts from the
 * same author are then spread out so one prolific account can't fill the screen.
 */
object FeedRanker {
    private const val HALF_DAY_HOURS = 12.0
    private const val FOLLOWED_BOOST = 1.5
    private const val MAX_PER_AUTHOR_IN_A_ROW = 2

    fun score(post: Post, following: Set<String>, viewerUid: String, now: Long): Double {
        val engagement = 2.0 * ln(1.0 + post.likeCount) + 3.0 * ln(1.0 + post.commentCount) + 1.0 * ln(1.0 + post.shareCount)
        val social = if (post.authorId in following) FOLLOWED_BOOST else 0.0
        val ageHours = ((now - post.createdAt).coerceAtLeast(0L)) / 3_600_000.0
        val own = if (post.authorId == viewerUid) -0.5 else 0.0
        return engagement + social + own - ageHours / HALF_DAY_HOURS
    }

    fun forYou(candidates: List<Post>, following: Set<String>, viewerUid: String, now: Long = System.currentTimeMillis()): List<Post> {
        val ranked = candidates.sortedByDescending { score(it, following, viewerUid, now) }
        return spreadAuthors(ranked)
    }

    /** Keeps at most [MAX_PER_AUTHOR_IN_A_ROW] consecutive posts per author where another author is available. */
    internal fun spreadAuthors(ranked: List<Post>): List<Post> {
        val remaining = ranked.toMutableList()
        val out = ArrayList<Post>(ranked.size)
        while (remaining.isNotEmpty()) {
            val lastAuthor = out.lastOrNull()?.authorId
            val run = out.takeLastWhile { it.authorId == lastAuthor }.size
            val pick = if (lastAuthor != null && run >= MAX_PER_AUTHOR_IN_A_ROW) {
                remaining.firstOrNull { it.authorId != lastAuthor } ?: remaining.first()
            } else {
                remaining.first()
            }
            remaining.remove(pick)
            out += pick
        }
        return out
    }
}
