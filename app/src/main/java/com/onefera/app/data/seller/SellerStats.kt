package com.onefera.app.data.seller

import com.onefera.app.data.model.Order
import com.onefera.app.data.model.OrderStatus
import com.onefera.app.data.model.Product
import java.util.Calendar
import java.util.TimeZone

/** One bar in the sales chart: revenue on a day (local midnight [dayStart]). */
data class DaySales(val dayStart: Long, val revenue: Int, val orders: Int)

data class TopProduct(val productId: String, val title: String, val imageUrl: String?, val units: Int, val revenue: Int)

/**
 * Seller analytics, computed from the seller's orders and listings. Only this seller's lines
 * count (an order can contain items from several sellers); cancelled orders are excluded.
 */
data class SellerStats(
    val revenue: Int = 0,
    val orders: Int = 0,
    val unitsSold: Int = 0,
    val toShip: Int = 0,
    val inTransit: Int = 0,
    val delivered: Int = 0,
    val cancelled: Int = 0,
    val activeListings: Int = 0,
    val outOfStock: Int = 0,
    val lowStock: List<Product> = emptyList(),
    val last7Days: List<DaySales> = emptyList(),
    val revenueLast7Days: Int = 0,
    val revenuePrevious7Days: Int = 0,
    val topProducts: List<TopProduct> = emptyList(),
) {
    val averageOrderValue: Int get() = if (orders == 0) 0 else revenue / orders

    /** Week-over-week revenue change in percent, or null when there's nothing to compare. */
    val weekChangePercent: Int?
        get() = if (revenuePrevious7Days == 0) null else ((revenueLast7Days - revenuePrevious7Days) * 100f / revenuePrevious7Days).toInt()

    companion object {
        const val LOW_STOCK = 5
        private const val DAY = 24 * 60 * 60 * 1000L

        fun compute(
            sellerId: String,
            orders: List<Order>,
            listings: List<Product>,
            now: Long = System.currentTimeMillis(),
            timeZone: TimeZone = TimeZone.getDefault(),
        ): SellerStats {
            val paid = orders.filter { it.status != OrderStatus.PendingPayment }
            val live = paid.filter { it.status != OrderStatus.Cancelled }
            fun Order.mine() = items.filter { it.product.sellerId == sellerId }
            fun Order.myRevenue() = mine().sumOf { it.price * it.quantity }

            val today = startOfDay(now, timeZone)
            val days = (6 downTo 0).map { today - it * DAY }
            val byDay = live.groupBy { startOfDay(it.createdAt, timeZone) }
            val last7 = days.map { day -> byDay[day].orEmpty().let { DaySales(day, it.sumOf { o -> o.myRevenue() }, it.size) } }
            val previousStart = today - 13 * DAY
            val previous = live.filter { it.createdAt >= previousStart && it.createdAt < today - 6 * DAY }.sumOf { it.myRevenue() }

            val top = live.flatMap { it.mine() }
                .groupBy { it.product.id }
                .map { (id, lines) ->
                    val first = lines.first().product
                    TopProduct(id, first.title, first.imageUrl, lines.sumOf { it.quantity }, lines.sumOf { it.price * it.quantity })
                }
                .sortedByDescending { it.revenue }
                .take(5)

            return SellerStats(
                revenue = live.sumOf { it.myRevenue() },
                orders = live.size,
                unitsSold = live.sumOf { o -> o.mine().sumOf { it.quantity } },
                toShip = paid.count { it.status == OrderStatus.Placed || it.status == OrderStatus.Packed },
                inTransit = paid.count { it.status == OrderStatus.Shipped || it.status == OrderStatus.OutForDelivery },
                delivered = paid.count { it.status == OrderStatus.Delivered },
                cancelled = paid.count { it.status == OrderStatus.Cancelled },
                activeListings = listings.count { it.inStock },
                outOfStock = listings.count { !it.inStock },
                lowStock = listings.filter { it.stock in 1..LOW_STOCK }.sortedBy { it.stock },
                last7Days = last7,
                revenueLast7Days = last7.sumOf { it.revenue },
                revenuePrevious7Days = previous,
                topProducts = top,
            )
        }

        fun startOfDay(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): Long =
            Calendar.getInstance(timeZone).apply {
                timeInMillis = millis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
    }
}
