package com.onefera.app.data.firebase

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.moderation.ModerationRepository
import com.onefera.app.data.moderation.ReportReason
import com.onefera.app.data.moderation.ReportTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * - `users/{uid}/blocked/{targetUid}`: `{ user, createdAt }`
 * - `reports/{id}`: write-only for clients; `onReportCreated` hides heavily reported posts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreModerationRepository @Inject constructor(private val auth: AuthRepository) : ModerationRepository {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private fun user(uid: String) = db.collection("users").document(uid)

    override fun blockedIds(): Flow<Set<String>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) flowOf(emptySet()) else user(uid).collection("blocked").snapshotFlow().map { s -> s.documents.map { it.id }.toSet() }
    }

    override fun blockedUsers(): Flow<List<UserSummary>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            user(uid).collection("blocked").snapshotFlow().map { s -> s.documents.map { (it.get("user") as? Map<*, *>).toUserSummary().copy(uid = it.id) } }
        }
    }

    override suspend fun block(user: UserSummary): Result<Unit> = runFriendly {
        val me = auth.currentUid()
        if (user.uid == me) throw UserFacingException("You can't block yourself.")
        val batch = db.batch()
        batch.set(user(me).collection("blocked").document(user.uid), mapOf("user" to user.toMap(), "createdAt" to FieldValue.serverTimestamp()))
        // Cut follow ties both ways.
        batch.delete(user(me).collection("following").document(user.uid))
        batch.delete(user(user.uid).collection("followers").document(me))
        batch.delete(user(me).collection("followers").document(user.uid))
        batch.delete(user(user.uid).collection("following").document(me))
        batch.commit().await()
    }

    override suspend fun unblock(uid: String): Result<Unit> = runFriendly {
        user(auth.currentUid()).collection("blocked").document(uid).delete().await()
        Unit
    }

    override suspend fun report(target: ReportTarget, targetId: String, ownerId: String, reason: ReportReason, details: String): Result<Unit> = runFriendly {
        db.collection("reports").add(
            mapOf(
                "reporterId" to auth.currentUid(),
                "targetType" to target.name,
                "targetId" to targetId,
                "ownerId" to ownerId,
                "reason" to reason.name,
                "details" to details.trim().take(500),
                "status" to "open",
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        Unit
    }
}
