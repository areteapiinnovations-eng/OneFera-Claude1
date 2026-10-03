package com.onefera.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProductCategory(val label: String, val emoji: String) {
    Mobiles("Mobiles", "📱"),
    Electronics("Electronics", "🎧"),
    Fashion("Fashion", "👟"),
    Beauty("Beauty", "💄"),
    Appliances("Appliances", "🏠"),
    Groceries("Groceries", "🛒"),
    KidsToys("Kids & Toys", "🧸"),
    More("More", "✨"),
}

/** Prices are whole rupees. */
@Serializable
data class Product(
    val id: String = "",
    val title: String = "",
    val brand: String = "",
    val description: String = "",
    val category: ProductCategory = ProductCategory.More,
    val price: Int = 0,
    val mrp: Int = 0,
    val images: List<String> = emptyList(),
    val highlights: List<String> = emptyList(),
    /** Size / colour options; empty when the product has none. */
    val variants: List<String> = emptyList(),
    val rating: Float = 0f,
    val ratingCount: Int = 0,
    val soldCount: Int = 0,
    val stock: Int = 0,
    val sellerId: String = "",
    val seller: UserSummary = UserSummary(),
    val isDrop: Boolean = false,
    val createdAt: Long = 0L,
) {
    val imageUrl: String? get() = images.firstOrNull()
    val discountPercent: Int get() = if (mrp > price && mrp > 0) ((mrp - price) * 100f / mrp).toInt() else 0
    val inStock: Boolean get() = stock > 0
    val freeDelivery: Boolean get() = price >= FREE_DELIVERY_ABOVE

    fun toSummary() = ProductSummary(id, title, brand, imageUrl, price, mrp, sellerId)

    /** Lower-case words used for prefix search (Firestore `array-contains`). */
    fun searchKeywords(): List<String> = keywordsFor("$title $brand ${category.label}")

    companion object {
        const val FREE_DELIVERY_ABOVE = 499
        const val DELIVERY_FEE = 49
        const val MAX_QUANTITY = 10

        fun keywordsFor(text: String): List<String> = text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .flatMap { word -> (2..minOf(word.length, 15)).map { word.take(it) } }
            .distinct()
            .take(200)
    }
}

/** Small, denormalised copy of a product stored on carts, orders and posts. */
@Serializable
data class ProductSummary(
    val id: String = "",
    val title: String = "",
    val brand: String = "",
    val imageUrl: String? = null,
    val price: Int = 0,
    val mrp: Int = 0,
    val sellerId: String = "",
)

@Serializable
data class CartItem(
    val product: ProductSummary = ProductSummary(),
    val variant: String = "",
    val quantity: Int = 1,
    val addedAt: Long = 0L,
) {
    /** One cart line per product + variant. */
    val key: String get() = keyFor(product.id, variant)
    val lineTotal: Int get() = product.price * quantity

    companion object {
        fun keyFor(productId: String, variant: String) =
            if (variant.isBlank()) productId else "${productId}__${variant.lowercase().replace(Regex("[^a-z0-9]+"), "-")}"
    }
}

@Serializable
data class Address(
    val name: String = "",
    val phone: String = "",
    val line1: String = "",
    val line2: String = "",
    val city: String = "",
    val state: String = "",
    val pincode: String = "",
) {
    val isComplete: Boolean
        get() = name.isNotBlank() && phone.length == 10 && line1.isNotBlank() && city.isNotBlank() && state.isNotBlank() && pincode.length == 6

    fun oneLine(): String = listOf(line1, line2, city, state, pincode).filter { it.isNotBlank() }.joinToString(", ")
}

@Serializable
enum class PaymentMethod(val label: String) { Upi("UPI"), Card("Card"), NetBanking("Net banking"), Cod("Cash on delivery") }

@Serializable
enum class OrderStatus(val label: String) {
    PendingPayment("Awaiting payment"),
    Placed("Order placed"),
    Packed("Packed"),
    Shipped("Shipped"),
    OutForDelivery("Out for delivery"),
    Delivered("Delivered"),
    Cancelled("Cancelled"),
    ;

    val isActive: Boolean get() = this != Delivered && this != Cancelled && this != PendingPayment
    val canCancel: Boolean get() = this == Placed || this == Packed

    companion object {
        /** The happy path shown on the tracking timeline. */
        val timeline = listOf(Placed, Packed, Shipped, OutForDelivery, Delivered)
    }
}

@Serializable
data class OrderItem(
    val product: ProductSummary = ProductSummary(),
    val variant: String = "",
    val quantity: Int = 1,
    val price: Int = 0,
)

@Serializable
data class Order(
    val id: String = "",
    val buyerId: String = "",
    val items: List<OrderItem> = emptyList(),
    val subtotal: Int = 0,
    val deliveryFee: Int = 0,
    val discount: Int = 0,
    val couponId: String = "",
    val total: Int = 0,
    val address: Address = Address(),
    val paymentMethod: PaymentMethod = PaymentMethod.Upi,
    val paymentId: String = "",
    val status: OrderStatus = OrderStatus.Placed,
    /** Status name → time it was reached. */
    val history: Map<String, Long> = emptyMap(),
    val createdAt: Long = 0L,
    val estimatedDelivery: Long = 0L,
) {
    val itemCount: Int get() = items.sumOf { it.quantity }
    fun reachedAt(status: OrderStatus): Long? = history[status.name]
}

/** Prices for a cart, computed the same way on the device and in Cloud Functions. */
data class CartTotals(val subtotal: Int, val savings: Int, val deliveryFee: Int, val discount: Int = 0) {
    val total: Int get() = (subtotal + deliveryFee - discount).coerceAtLeast(0)

    companion object {
        /**
         * [coupon] (if usable for this subtotal) takes money off the items or waives delivery;
         * [freeDelivery] is the OneFera+ perk.
         */
        fun of(items: List<CartItem>, coupon: Coupon? = null, freeDelivery: Boolean = false): CartTotals {
            val subtotal = items.sumOf { it.lineTotal }
            val savings = items.sumOf { (it.product.mrp - it.product.price).coerceAtLeast(0) * it.quantity }
            val waived = freeDelivery || coupon?.waivesDelivery(subtotal) == true
            val delivery = if (subtotal == 0 || subtotal >= Product.FREE_DELIVERY_ABOVE || waived) 0 else Product.DELIVERY_FEE
            val discount = coupon?.discountFor(subtotal) ?: 0
            return CartTotals(subtotal, savings + discount, delivery, discount)
        }
    }
}

enum class ProductSort(val label: String) {
    Popular("Popular"),
    Newest("Newest"),
    PriceLowHigh("Price: low to high"),
    PriceHighLow("Price: high to low"),
    Rating("Top rated"),
}

data class ProductFilter(
    val category: ProductCategory? = null,
    val maxPrice: Int? = null,
    val minRating: Float = 0f,
    val inStockOnly: Boolean = false,
    val sort: ProductSort = ProductSort.Popular,
) {
    val activeCount: Int get() = listOf(maxPrice != null, minRating > 0f, inStockOnly).count { it }

    fun apply(products: List<Product>): List<Product> = products
        .asSequence()
        .filter { category == null || it.category == category }
        .filter { maxPrice == null || it.price <= maxPrice }
        .filter { it.rating >= minRating }
        .filter { !inStockOnly || it.inStock }
        .let { seq ->
            when (sort) {
                ProductSort.Popular -> seq.sortedByDescending { it.soldCount }
                ProductSort.Newest -> seq.sortedByDescending { it.createdAt }
                ProductSort.PriceLowHigh -> seq.sortedBy { it.price }
                ProductSort.PriceHighLow -> seq.sortedByDescending { it.price }
                ProductSort.Rating -> seq.sortedWith(compareByDescending<Product> { it.rating }.thenByDescending { it.ratingCount })
            }
        }
        .toList()
}

/** ₹1,23,456 with Indian digit grouping (last three digits, then pairs). */
fun formatRupees(amount: Int): String {
    val digits = kotlin.math.abs(amount.toLong()).toString()
    val grouped = if (digits.length <= 3) {
        digits
    } else {
        val head = digits.dropLast(3)
        head.reversed().chunked(2).joinToString(",").reversed() + "," + digits.takeLast(3)
    }
    return (if (amount < 0) "-₹" else "₹") + grouped
}
