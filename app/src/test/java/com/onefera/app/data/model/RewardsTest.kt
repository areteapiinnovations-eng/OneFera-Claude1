package com.onefera.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RewardsTest {
    @Test
    fun `streak grows on consecutive days and restarts after a gap`() {
        val next = Rewards.checkIn(lastDay = "2026-10-02", today = "2026-10-03", currentStreak = 4, plus = false)
        assertEquals(5, next.streak)
        assertEquals(Rewards.CHECK_IN_AURA, next.auraGained)
        assertTrue(next.boxUnlocked)

        val gap = Rewards.checkIn(lastDay = "2026-09-30", today = "2026-10-03", currentStreak = 9, plus = false)
        assertEquals(1, gap.streak)

        val again = Rewards.checkIn(lastDay = "2026-10-03", today = "2026-10-03", currentStreak = 5, plus = false)
        assertTrue(again.alreadyCheckedIn)
        assertEquals(0, again.auraGained)
        assertEquals(5, again.streak)
    }

    @Test
    fun `week bonus and plus doubling`() {
        val seventh = Rewards.checkIn("2026-10-02", "2026-10-03", currentStreak = 6, plus = true)
        assertEquals(7, seventh.streak)
        assertEquals(Rewards.CHECK_IN_AURA * 2 + Rewards.WEEK_BONUS_AURA, seventh.auraGained)
        // Month rollover.
        assertEquals(2, Rewards.checkIn("2026-09-30", "2026-10-01", 1, false).streak)
    }

    @Test
    fun `coupons discount correctly`() {
        val flat = Coupon(kind = CouponKind.Flat, value = 50, minOrder = 499, expiresAt = Long.MAX_VALUE)
        assertEquals(0, flat.discountFor(400))
        assertEquals(50, flat.discountFor(600))
        val pct = Coupon(kind = CouponKind.Percent, value = 10, minOrder = 999, maxDiscount = 200, expiresAt = Long.MAX_VALUE)
        assertEquals(150, pct.discountFor(1_500))
        assertEquals(200, pct.discountFor(5_000))
        val free = Coupon(kind = CouponKind.FreeDelivery, expiresAt = Long.MAX_VALUE)
        assertTrue(free.waivesDelivery(100))
        assertFalse(flat.copy(used = true).usable())
    }

    @Test
    fun `cart totals apply coupons and plus delivery`() {
        val items = listOf(CartItem(ProductSummary(id = "a", price = 300), quantity = 1))
        assertEquals(Product.DELIVERY_FEE, CartTotals.of(items).deliveryFee)
        assertEquals(0, CartTotals.of(items, freeDelivery = true).deliveryFee)
        assertEquals(0, CartTotals.of(items, Coupon(kind = CouponKind.FreeDelivery)).deliveryFee)
        val big = listOf(CartItem(ProductSummary(id = "b", price = 1_000), quantity = 1))
        val totals = CartTotals.of(big, Coupon(kind = CouponKind.Flat, value = 50, minOrder = 499))
        assertEquals(50, totals.discount)
        assertEquals(950, totals.total)
    }

    @Test
    fun `mystery boxes always give something`() {
        val random = Random(42)
        repeat(200) {
            val reward = Rewards.rollBox(random, now = 0L) { "id" }
            assertTrue(reward.aura > 0 || reward.coupon != null)
        }
        assertNotNull(Rewards.rollBox(Random(1), 0L) { "x" }.headline)
        assertEquals(2, Rewards.boxesPerDay(MembershipPlan.SellerPro))
        assertEquals(1, Rewards.boxesPerDay(MembershipPlan.None))
        assertEquals(MembershipPlan.None, Membership(MembershipPlan.Plus, expiresAt = 10).active(now = 20))
    }
}
