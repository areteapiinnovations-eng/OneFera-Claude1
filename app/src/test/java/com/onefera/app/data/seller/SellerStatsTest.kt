package com.onefera.app.data.seller

import com.onefera.app.data.model.Order
import com.onefera.app.data.model.OrderItem
import com.onefera.app.data.model.OrderStatus
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.ProductSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class SellerStatsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 24 * 60 * 60 * 1000L
    private val now = 20_000 * day + 12 * 60 * 60 * 1000L // noon UTC

    private fun line(id: String, seller: String, price: Int, qty: Int = 1) =
        OrderItem(ProductSummary(id = id, title = id, price = price, sellerId = seller), quantity = qty, price = price)

    private fun order(id: String, daysAgo: Int, status: OrderStatus, vararg items: OrderItem) =
        Order(id = id, items = items.toList(), status = status, createdAt = now - daysAgo * day)

    @Test
    fun `counts only this seller's lines and skips cancelled orders`() {
        val orders = listOf(
            order("a", 0, OrderStatus.Placed, line("tote", "me", 500, 2), line("other", "them", 9_999)),
            order("b", 1, OrderStatus.Shipped, line("mug", "me", 300)),
            order("c", 2, OrderStatus.Cancelled, line("tote", "me", 500)),
            order("d", 9, OrderStatus.Delivered, line("tote", "me", 500)),
        )
        val stats = SellerStats.compute("me", orders, emptyList(), now, utc)
        assertEquals(1_000 + 300 + 500, stats.revenue)
        assertEquals(3, stats.orders)
        assertEquals(4, stats.unitsSold)
        assertEquals(1, stats.toShip)
        assertEquals(1, stats.inTransit)
        assertEquals(1, stats.delivered)
        assertEquals(1, stats.cancelled)
        assertEquals(600, stats.averageOrderValue)
        assertEquals("tote", stats.topProducts.first().productId)
        assertEquals(1_500, stats.topProducts.first().revenue)
    }

    @Test
    fun `builds a seven day chart and week over week change`() {
        val orders = listOf(
            order("a", 0, OrderStatus.Placed, line("x", "me", 400)),
            order("b", 6, OrderStatus.Delivered, line("x", "me", 200)),
            order("c", 8, OrderStatus.Delivered, line("x", "me", 300)),
        )
        val stats = SellerStats.compute("me", orders, emptyList(), now, utc)
        assertEquals(7, stats.last7Days.size)
        assertEquals(400, stats.last7Days.last().revenue)
        assertEquals(200, stats.last7Days.first().revenue)
        assertEquals(600, stats.revenueLast7Days)
        assertEquals(300, stats.revenuePrevious7Days)
        assertEquals(100, stats.weekChangePercent)
        assertNull(SellerStats.compute("me", emptyList(), emptyList(), now, utc).weekChangePercent)
    }

    @Test
    fun `flags low and empty stock`() {
        val listings = listOf(
            Product(id = "a", stock = 0),
            Product(id = "b", stock = 3),
            Product(id = "c", stock = 50),
        )
        val stats = SellerStats.compute("me", emptyList(), listings, now, utc)
        assertEquals(2, stats.activeListings)
        assertEquals(1, stats.outOfStock)
        assertEquals(listOf("b"), stats.lowStock.map { it.id })
    }

    @Test
    fun `listing drafts are validated`() {
        val ok = ListingDraft(title = "Tote bag", price = 500, stock = 3, images = listOf("x"))
        assertNull(ok.problem())
        assertEquals("Add at least one photo.", ok.copy(images = emptyList()).problem())
        assertEquals("MRP can't be lower than your price.", ok.copy(mrp = 100).problem())
        assertEquals(OrderStatus.Packed, OrderStatus.Placed.nextForSeller())
        assertNull(OrderStatus.Delivered.nextForSeller())
    }
}
