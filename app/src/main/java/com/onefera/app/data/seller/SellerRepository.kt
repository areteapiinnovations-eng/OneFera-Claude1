package com.onefera.app.data.seller

import android.net.Uri
import com.onefera.app.data.model.Order
import com.onefera.app.data.model.OrderStatus
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.ProductCategory
import kotlinx.coroutines.flow.Flow

/** Seller mode: the signed-in seller's listings, inventory and incoming orders. */
interface SellerRepository {
    fun myListings(): Flow<List<Product>>
    fun listing(productId: String): Flow<Product?>

    /** Creates a listing, or updates it when [ListingDraft.id] is set. New photos are uploaded first. */
    suspend fun saveListing(draft: ListingDraft, onProgress: (Float) -> Unit = {}): Result<Product>
    suspend fun deleteListing(productId: String): Result<Unit>
    suspend fun setStock(productId: String, stock: Int): Result<Unit>

    /** Paid orders that contain at least one of this seller's products, newest first. */
    fun sellerOrders(): Flow<List<Order>>
    fun sellerOrder(orderId: String): Flow<Order?>

    /** Moves an order one step along the delivery timeline (Placed → Packed → … → Delivered). */
    suspend fun advanceOrder(orderId: String): Result<Unit>
}

/** What the seller filled in on the listing editor. */
data class ListingDraft(
    val id: String? = null,
    val title: String = "",
    val brand: String = "",
    val description: String = "",
    val category: ProductCategory = ProductCategory.Fashion,
    val price: Int = 0,
    val mrp: Int = 0,
    val stock: Int = 0,
    val variants: List<String> = emptyList(),
    val highlights: List<String> = emptyList(),
    /** Already-uploaded photos kept on the listing. */
    val images: List<String> = emptyList(),
    /** New photos picked on the device. */
    val newImages: List<Uri> = emptyList(),
) {
    val photoCount: Int get() = images.size + newImages.size

    /** First problem with the draft, or null when it can be published. */
    fun problem(): String? = when {
        title.trim().length < 3 -> "Give your product a name (3+ characters)."
        title.length > 80 -> "Keep the name under 80 characters."
        price < MIN_PRICE -> "Set a price of at least ₹$MIN_PRICE."
        price > MAX_PRICE -> "Price can't be more than ₹$MAX_PRICE."
        mrp != 0 && mrp < price -> "MRP can't be lower than your price."
        stock < 0 || stock > MAX_STOCK -> "Stock must be between 0 and $MAX_STOCK."
        photoCount == 0 -> "Add at least one photo."
        photoCount > MAX_PHOTOS -> "Up to $MAX_PHOTOS photos per listing."
        description.length > 1000 -> "Keep the description under 1,000 characters."
        variants.size > 12 -> "Up to 12 options per listing."
        else -> null
    }

    companion object {
        const val MIN_PRICE = 10
        const val MAX_PRICE = 500_000
        const val MAX_STOCK = 9_999
        const val MAX_PHOTOS = 5
    }
}

/** The status an order moves to next when the seller advances it, or null at the end. */
fun OrderStatus.nextForSeller(): OrderStatus? = when (this) {
    OrderStatus.Placed -> OrderStatus.Packed
    OrderStatus.Packed -> OrderStatus.Shipped
    OrderStatus.Shipped -> OrderStatus.OutForDelivery
    OrderStatus.OutForDelivery -> OrderStatus.Delivered
    else -> null
}
