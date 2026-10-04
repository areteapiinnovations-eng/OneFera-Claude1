package com.onefera.app.data.rewards

import com.onefera.app.data.model.BoxStatus
import com.onefera.app.data.model.CheckInResult
import com.onefera.app.data.model.Coupon
import com.onefera.app.data.model.LeaderboardEntry
import com.onefera.app.data.model.LeaderboardScope
import com.onefera.app.data.model.MembershipPlan
import com.onefera.app.data.model.MysteryReward
import kotlinx.coroutines.flow.Flow

/** Daily streaks, Mystery Boxes, coupons, the Aura leaderboard and memberships. */
interface RewardsRepository {
    /** Today's check-in: extends (or restarts) the streak, awards Aura and unlocks today's Mystery Box. */
    suspend fun checkIn(): Result<CheckInResult>

    fun boxStatus(): Flow<BoxStatus>
    suspend fun openMysteryBox(): Result<MysteryReward>

    /** Unused, unexpired coupons, soonest-expiring first. */
    fun coupons(): Flow<List<Coupon>>

    fun leaderboard(scope: LeaderboardScope): Flow<List<LeaderboardEntry>>

    /**
     * Starts or renews a membership for a month. Simulated for now: Play Store builds must sell
     * subscriptions through Google Play Billing, which is wired up with release hardening.
     */
    suspend fun subscribe(plan: MembershipPlan): Result<Unit>
    suspend fun cancelMembership(): Result<Unit>
}
