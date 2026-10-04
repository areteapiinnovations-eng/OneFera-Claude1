package com.onefera.app.data.model

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/** Membership plans. Prices are per month in rupees. */
@Serializable
enum class MembershipPlan(val label: String, val monthlyPrice: Int, val tagline: String, val benefits: List<String>) {
    None("Free", 0, "", emptyList()),
    Plus(
        "OneFera+",
        199,
        "More boxes, more Aura, free delivery",
        listOf(
            "2 Mystery Boxes every day",
            "Double Aura from daily streaks",
            "Free delivery on every order",
            "OneFera+ badge on your profile",
        ),
    ),
    SellerPro(
        "Seller Pro",
        799,
        "Grow your store faster",
        listOf(
            "Unlimited listings (free sellers get ${Rewards.FREE_LISTING_LIMIT})",
            "30-day sales analytics",
            "Pro badge on your store and products",
            "Everything in OneFera+",
        ),
    ),
    ;

    /** Seller Pro includes every OneFera+ perk. */
    val hasPlusPerks: Boolean get() = this == Plus || this == SellerPro
}

/** A user's membership; [expiresAt] is millis, 0 when there is none. */
data class Membership(val plan: MembershipPlan = MembershipPlan.None, val expiresAt: Long = 0L) {
    fun active(now: Long = System.currentTimeMillis()): MembershipPlan = if (plan != MembershipPlan.None && expiresAt > now) plan else MembershipPlan.None
}

@Serializable
enum class CouponKind { Flat, Percent, FreeDelivery }

/** A checkout coupon, won from a Mystery Box. Single use. */
@Serializable
data class Coupon(
    val id: String = "",
    val kind: CouponKind = CouponKind.Flat,
    /** Rupees for Flat, percent for Percent, unused for FreeDelivery. */
    val value: Int = 0,
    val minOrder: Int = 0,
    /** Cap for percent coupons, in rupees (0 = no cap). */
    val maxDiscount: Int = 0,
    val expiresAt: Long = 0L,
    val used: Boolean = false,
) {
    val title: String
        get() = when (kind) {
            CouponKind.Flat -> "₹$value off"
            CouponKind.Percent -> "$value% off" + if (maxDiscount > 0) " (up to ₹$maxDiscount)" else ""
            CouponKind.FreeDelivery -> "Free delivery"
        }
    val condition: String get() = if (minOrder > 0) "On orders of ₹$minOrder+" else "On any order"

    fun usable(now: Long = System.currentTimeMillis()) = !used && expiresAt > now

    /** Item discount for this subtotal (free-delivery coupons are applied to the delivery fee instead). */
    fun discountFor(subtotal: Int): Int = when {
        subtotal < minOrder -> 0
        kind == CouponKind.Flat -> value.coerceAtMost(subtotal)
        kind == CouponKind.Percent -> (subtotal * value / 100).let { if (maxDiscount > 0) it.coerceAtMost(maxDiscount) else it }
        else -> 0
    }

    fun waivesDelivery(subtotal: Int): Boolean = kind == CouponKind.FreeDelivery && subtotal >= minOrder
}

/** What a Mystery Box gave. Exactly one of [aura] > 0 or [coupon] != null. */
@Serializable
data class MysteryReward(val aura: Int = 0, val coupon: Coupon? = null) {
    val headline: String get() = coupon?.let { "${it.title} coupon" } ?: "+$aura Aura"
}

data class CheckInResult(val streak: Int, val auraGained: Int, val alreadyCheckedIn: Boolean, val boxUnlocked: Boolean)

/** Today's Mystery Box allowance. */
data class BoxStatus(val checkedInToday: Boolean = false, val opened: Int = 0, val allowed: Int = 1) {
    val available: Int get() = if (checkedInToday) (allowed - opened).coerceAtLeast(0) else 0
}

enum class LeaderboardScope(val label: String) { Global("Global"), Friends("Friends"), City("My city") }

data class LeaderboardEntry(val rank: Int, val user: UserSummary, val auraPoints: Int, val streakDays: Int, val isMe: Boolean) {
    val grade: AuraGrade get() = AuraGrade.forPoints(auraPoints)
}

/**
 * The rules of the Aura economy. The Cloud Functions mirror these numbers, so demo mode and
 * the live backend behave the same.
 */
object Rewards {
    const val CHECK_IN_AURA = 5
    const val WEEK_BONUS_AURA = 25
    const val FREE_LISTING_LIMIT = 25
    const val COUPON_DAYS = 14
    private const val DAY = 24 * 60 * 60 * 1000L

    /** Streak days are counted in India time so they roll over at local midnight for everyone. */
    val streakZone: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    fun dayKey(millis: Long, zone: TimeZone = streakZone): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(Date(millis))

    private fun previousDayKey(today: String, zone: TimeZone = streakZone): String {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.parse(today) ?: return ""
        return dayKey(parsed.time - DAY + 12 * 60 * 60 * 1000L, zone) // midday avoids DST edge cases
    }

    /** Streak and Aura after checking in on [today], given the last check-in day and current streak. */
    fun checkIn(lastDay: String, today: String, currentStreak: Int, plus: Boolean): CheckInResult {
        if (lastDay == today) return CheckInResult(currentStreak, 0, alreadyCheckedIn = true, boxUnlocked = false)
        val streak = if (lastDay == previousDayKey(today)) currentStreak + 1 else 1
        val base = CHECK_IN_AURA * (if (plus) 2 else 1)
        val bonus = if (streak % 7 == 0) WEEK_BONUS_AURA else 0
        return CheckInResult(streak, base + bonus, alreadyCheckedIn = false, boxUnlocked = true)
    }

    fun boxesPerDay(plan: MembershipPlan): Int = if (plan.hasPlusPerks) 2 else 1

    /** Rolls a Mystery Box. Weights: Aura 65%, coupons 35%. */
    fun rollBox(random: Random, now: Long, idFactory: () -> String): MysteryReward {
        val expires = now + COUPON_DAYS * DAY
        return when (random.nextInt(100)) {
            in 0 until 40 -> MysteryReward(aura = 10)
            in 40 until 60 -> MysteryReward(aura = 25)
            in 60 until 65 -> MysteryReward(aura = 50)
            in 65 until 85 -> MysteryReward(coupon = Coupon(idFactory(), CouponKind.Flat, 50, minOrder = 499, expiresAt = expires))
            in 85 until 95 -> MysteryReward(coupon = Coupon(idFactory(), CouponKind.Percent, 10, minOrder = 999, maxDiscount = 200, expiresAt = expires))
            else -> MysteryReward(coupon = Coupon(idFactory(), CouponKind.FreeDelivery, 0, expiresAt = expires))
        }
    }
}
