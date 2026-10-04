package com.onefera.app.data.moderation

import com.onefera.app.data.model.UserSummary
import kotlinx.coroutines.flow.Flow

enum class ReportReason(val label: String) {
    Spam("Spam or scam"),
    Harassment("Bullying or harassment"),
    Hate("Hate speech or symbols"),
    Nudity("Nudity or sexual content"),
    Violence("Violence or dangerous acts"),
    SelfHarm("Suicide or self-harm"),
    Fake("Fake account or impersonation"),
    Counterfeit("Fake or prohibited product"),
    Other("Something else"),
}

enum class ReportTarget { Post, Comment, User, Message, Product }

/**
 * Safety tools required for user-generated content: blocking (hides the person everywhere and
 * stops them following, commenting on or messaging you) and reports (reviewed by moderators;
 * posts reported by several people are hidden automatically until reviewed).
 */
interface ModerationRepository {
    /** People the signed-in user has blocked. */
    fun blockedIds(): Flow<Set<String>>
    fun blockedUsers(): Flow<List<UserSummary>>

    suspend fun block(user: UserSummary): Result<Unit>
    suspend fun unblock(uid: String): Result<Unit>

    suspend fun report(target: ReportTarget, targetId: String, ownerId: String, reason: ReportReason, details: String = ""): Result<Unit>
}
