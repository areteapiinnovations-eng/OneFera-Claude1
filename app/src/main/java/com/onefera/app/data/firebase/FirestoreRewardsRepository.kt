package com.onefera.app.data.firebase

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.model.BoxStatus
import com.onefera.app.data.model.CheckInResult
import com.onefera.app.data.model.Coupon
import com.onefera.app.data.model.CouponKind
import com.onefera.app.data.model.LeaderboardEntry
import com.onefera.app.data.model.LeaderboardScope
import com.onefera.app.data.model.Membership
import com.onefera.app.data.model.MembershipPlan
import com.onefera.app.data.model.MysteryReward
import com.onefera.app.data.model.Rewards
import com.onefera.app.data.rewards.RewardsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewards on Firebase. Everything that grants value (Aura, boxes, coupons, memberships) runs in
 * Cloud Functions; the app only reads:
 * - `users/{uid}`: `streakDays`, `lastCheckInDay`, `auraPoints`, `membershipPlan`, `membershipExpiresAt`
 * - `users/{uid}/boxes/{yyyy-MM-dd}`: `{ opened }`
 * - `users/{uid}/coupons/{couponId}`
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreRewardsRepository @Inject constructor(private val auth: AuthRepository) : RewardsRepository {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val functions by lazy { FirebaseFunctions.getInstance("asia-south1") }
    private fun user(uid: String) = db.collection("users").document(uid)

    private suspend fun call(name: String, data: Map<String, Any?> = emptyMap()): Map<*, *> = try {
        functions.getHttpsCallable(name).call(data).await().data as? Map<*, *> ?: emptyMap<String, Any>()
    } catch (e: FirebaseFunctionsException) {
        throw UserFacingException(e.message ?: "Something went wrong. Try again.", e)
    }

    override suspend fun checkIn(): Result<CheckInResult> = runFriendly {
        auth.currentUid()
        val r = call("dailyCheckIn")
        CheckInResult(
            streak = (r["streak"] as? Number)?.toInt() ?: 0,
            auraGained = (r["auraGained"] as? Number)?.toInt() ?: 0,
            alreadyCheckedIn = r["alreadyCheckedIn"] as? Boolean ?: true,
            boxUnlocked = r["boxUnlocked"] as? Boolean ?: false,
        )
    }

    override fun boxStatus(): Flow<BoxStatus> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(BoxStatus())
        } else {
            val today = Rewards.dayKey(System.currentTimeMillis())
            combine(user(uid).snapshotFlow(), user(uid).collection("boxes").document(today).snapshotFlow()) { profile, box ->
                val plan = Membership(
                    runCatching { MembershipPlan.valueOf(profile.getString("membershipPlan").orEmpty()) }.getOrDefault(MembershipPlan.None),
                    profile.getLong("membershipExpiresAt") ?: 0L,
                ).active()
                BoxStatus(
                    checkedInToday = profile.getString("lastCheckInDay") == today,
                    opened = box.getLong("opened")?.toInt() ?: 0,
                    allowed = Rewards.boxesPerDay(plan),
                )
            }
        }
    }

    override suspend fun openMysteryBox(): Result<MysteryReward> = runFriendly {
        auth.currentUid()
        val r = call("openMysteryBox")
        MysteryReward(
            aura = (r["aura"] as? Number)?.toInt() ?: 0,
            coupon = (r["coupon"] as? Map<*, *>)?.toCoupon(),
        )
    }

    override fun coupons(): Flow<List<Coupon>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            user(uid).collection("coupons").orderBy("expiresAt").limit(50).snapshotFlow()
                .map { s -> s.documents.mapNotNull { it.data?.toCoupon(it.id) }.filter { it.usable() } }
        }
    }

    override fun leaderboard(scope: LeaderboardScope): Flow<List<LeaderboardEntry>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) return@flatMapLatest flowOf(emptyList())
        when (scope) {
            LeaderboardScope.Global -> db.collection("users").orderBy("auraPoints", Query.Direction.DESCENDING).limit(50)
                .snapshotFlow().map { s -> ranked(s.documents, uid) }
            LeaderboardScope.City -> user(uid).snapshotFlow().flatMapLatest { me ->
                val city = me.getString("city").orEmpty()
                if (city.isBlank()) {
                    flowOf(emptyList())
                } else {
                    db.collection("users").whereEqualTo("city", city).orderBy("auraPoints", Query.Direction.DESCENDING).limit(50)
                        .snapshotFlow().map { s -> ranked(s.documents, uid) }
                }
            }
            LeaderboardScope.Friends -> user(uid).collection("following").limit(200).snapshotFlow().flatMapLatest { following ->
                val ids = (following.documents.map { it.id } + uid).distinct().chunked(30)
                combine(ids.map { chunk -> db.collection("users").whereIn(FieldPath.documentId(), chunk).snapshotFlow() }) { parts ->
                    ranked(parts.flatMap { it.documents }, uid)
                }
            }
        }
    }

    private fun ranked(docs: List<DocumentSnapshot>, me: String): List<LeaderboardEntry> = docs
        .filter { !it.getString("username").isNullOrEmpty() }
        .sortedWith(compareByDescending<DocumentSnapshot> { it.getLong("auraPoints") ?: 0 }.thenByDescending { it.getLong("streakDays") ?: 0 })
        .take(50)
        .mapIndexed { i, d ->
            LeaderboardEntry(
                rank = i + 1,
                user = d.toUserSummaryDoc(),
                auraPoints = d.getLong("auraPoints")?.toInt() ?: 0,
                streakDays = d.getLong("streakDays")?.toInt() ?: 0,
                isMe = d.id == me,
            )
        }

    override suspend fun subscribe(plan: MembershipPlan): Result<Unit> = runFriendly {
        auth.currentUid()
        call("startMembership", mapOf("plan" to plan.name))
        Unit
    }

    override suspend fun cancelMembership(): Result<Unit> = runFriendly {
        auth.currentUid()
        call("cancelMembership")
        Unit
    }
}

private fun Map<*, *>.toCoupon(id: String = get("id") as? String ?: ""): Coupon = Coupon(
    id = id,
    kind = runCatching { CouponKind.valueOf(get("kind") as? String ?: "") }.getOrDefault(CouponKind.Flat),
    value = (get("value") as? Number)?.toInt() ?: 0,
    minOrder = (get("minOrder") as? Number)?.toInt() ?: 0,
    maxDiscount = (get("maxDiscount") as? Number)?.toInt() ?: 0,
    expiresAt = (get("expiresAt") as? Number)?.toLong() ?: 0L,
    used = get("used") as? Boolean ?: false,
)
