package com.onefera.app.data.demo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.backend.ApplicationScope
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.model.AuraGrade
import com.onefera.app.data.model.BoxStatus
import com.onefera.app.data.model.CheckInResult
import com.onefera.app.data.model.Coupon
import com.onefera.app.data.model.LeaderboardEntry
import com.onefera.app.data.model.LeaderboardScope
import com.onefera.app.data.model.MembershipPlan
import com.onefera.app.data.model.MysteryReward
import com.onefera.app.data.model.Rewards
import com.onefera.app.data.model.toSummary
import com.onefera.app.data.rewards.RewardsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private val Context.demoRewardsStore: DataStore<Preferences> by preferencesDataStore(name = "demo_rewards")

/** On-device rewards for demo mode, following the same [Rewards] rules as the Cloud Functions. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DemoRewardsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val auth: AuthRepository,
    private val accounts: DemoBackend,
    private val social: DemoSocialBackend,
) : RewardsRepository {

    @Serializable
    data class RewardsState(
        val coupons: Map<String, List<Coupon>> = emptyMap(),
        /** "uid|yyyy-MM-dd" → boxes opened that day. */
        val boxesOpened: Map<String, Int> = emptyMap(),
        /** "uid|yyyy-MM-dd" → what those boxes gave. */
        val boxesWon: Map<String, List<String>> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val state = MutableStateFlow<RewardsState?>(null)
    private val data: Flow<RewardsState> = state.filterNotNull()

    init {
        scope.launch {
            state.value = context.demoRewardsStore.data.first()[KEY]
                ?.let { runCatching { json.decodeFromString(RewardsState.serializer(), it) }.getOrNull() } ?: RewardsState()
        }
    }

    private suspend fun <T> update(block: (RewardsState) -> Pair<RewardsState, T>): T = mutex.withLock {
        val (next, result) = block(data.first())
        state.value = next
        context.demoRewardsStore.edit { it[KEY] = json.encodeToString(RewardsState.serializer(), next) }
        result
    }

    private fun uid(): String = (auth.session.value as? SessionState.SignedIn)?.uid ?: throw UserFacingException("Please log in again.")
    private fun today() = Rewards.dayKey(System.currentTimeMillis())

    private suspend fun addAura(uid: String, points: Int) =
        accounts.adjustProfile(uid) { it.copy(auraPoints = (it.auraPoints + points).coerceIn(0, AuraGrade.MAX_POINTS)) }

    override suspend fun checkIn(): Result<CheckInResult> = runCatching {
        val uid = uid()
        val profile = accounts.profileNow(uid) ?: throw UserFacingException("Account not found.")
        val result = Rewards.checkIn(profile.lastCheckInDay, today(), profile.streakDays, profile.membership.active().hasPlusPerks)
        if (!result.alreadyCheckedIn) {
            accounts.adjustProfile(uid) {
                it.copy(
                    streakDays = result.streak,
                    lastCheckInDay = today(),
                    auraPoints = (it.auraPoints + result.auraGained).coerceIn(0, AuraGrade.MAX_POINTS),
                )
            }
        }
        result
    }

    override fun boxStatus(): Flow<BoxStatus> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(BoxStatus())
        } else {
            combine(accounts.profile(uid), data) { profile, s ->
                val today = today()
                BoxStatus(
                    checkedInToday = profile?.lastCheckInDay == today,
                    opened = s.boxesOpened["$uid|$today"] ?: 0,
                    allowed = Rewards.boxesPerDay(profile?.membership?.active() ?: MembershipPlan.None),
                    wonToday = s.boxesWon["$uid|$today"].orEmpty(),
                )
            }.distinctUntilChanged()
        }
    }

    override suspend fun openMysteryBox(): Result<MysteryReward> = runCatching {
        val uid = uid()
        val status = boxStatus().first()
        if (!status.checkedInToday) throw UserFacingException("Check in first to unlock today's box.")
        if (status.available <= 0) throw UserFacingException("You've opened today's boxes. New ones drop at midnight ✨")
        delay(900) // suspense
        val now = System.currentTimeMillis()
        val reward = Rewards.rollBox(Random.Default, now) { "cp_" + UUID.randomUUID().toString().take(10) }
        update { s ->
            val key = "$uid|${today()}"
            val coupons = reward.coupon?.let { c -> s.coupons + (uid to (s.coupons[uid].orEmpty() + c)) } ?: s.coupons
            s.copy(
                boxesOpened = s.boxesOpened + (key to (s.boxesOpened[key] ?: 0) + 1),
                boxesWon = s.boxesWon + (key to (s.boxesWon[key].orEmpty() + reward.headline)),
                coupons = coupons,
            ) to Unit
        }
        if (reward.aura > 0) addAura(uid, reward.aura)
        reward
    }

    override fun coupons(): Flow<List<Coupon>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            data.map { s -> s.coupons[uid].orEmpty().filter { it.usable() }.sortedBy { it.expiresAt } }.distinctUntilChanged()
        }
    }

    /** Used by the demo checkout. */
    internal suspend fun usableCoupon(uid: String, couponId: String): Coupon? =
        data.first().coupons[uid].orEmpty().firstOrNull { it.id == couponId && it.usable() }

    internal suspend fun markCouponUsed(uid: String, couponId: String) {
        update { s -> s.copy(coupons = s.coupons + (uid to s.coupons[uid].orEmpty().map { if (it.id == couponId) it.copy(used = true) else it })) to Unit }
    }

    override fun leaderboard(scope: LeaderboardScope): Flow<List<LeaderboardEntry>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            combine(accounts.profiles, social.data.map { it.follows }.distinctUntilChanged()) { profiles, follows ->
                val me = profiles.firstOrNull { it.uid == uid }
                profiles
                    .filter { it.username.isNotEmpty() }
                    .filter { p ->
                        when (scope) {
                            LeaderboardScope.Global -> true
                            LeaderboardScope.Friends -> p.uid == uid || "$uid>${p.uid}" in follows
                            LeaderboardScope.City -> me != null && me.city.isNotBlank() && p.city.equals(me.city, ignoreCase = true)
                        }
                    }
                    .sortedWith(compareByDescending<com.onefera.app.data.model.UserProfile> { it.auraPoints }.thenByDescending { it.streakDays })
                    .take(50)
                    .mapIndexed { i, p -> LeaderboardEntry(i + 1, p.toSummary(), p.auraPoints, p.streakDays, p.uid == uid) }
            }
        }
    }

    override suspend fun subscribe(plan: MembershipPlan): Result<Unit> = runCatching {
        val uid = uid()
        if (plan == MembershipPlan.None) throw UserFacingException("Pick a plan.")
        delay(800)
        val now = System.currentTimeMillis()
        accounts.adjustProfile(uid) { p ->
            val base = if (p.membership.active(now) == plan) p.membershipExpiresAt else now
            p.copy(membershipPlan = plan, membershipExpiresAt = base + 30L * 24 * 60 * 60 * 1000)
        }
    }

    override suspend fun cancelMembership(): Result<Unit> = runCatching {
        val uid = uid()
        accounts.adjustProfile(uid) { it.copy(membershipPlan = MembershipPlan.None, membershipExpiresAt = 0L) }
    }

    private companion object {
        val KEY = stringPreferencesKey("state")
    }
}
