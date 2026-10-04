package com.onefera.app.data.shop

import com.onefera.app.data.model.Address
import com.onefera.app.data.model.CartItem
import com.onefera.app.data.model.Order
import com.onefera.app.data.model.PaymentMethod
import com.onefera.app.data.model.Product
import kotlinx.coroutines.flow.Flow

/** Catalogue, wishlist, cart, addresses and orders. */
interface ShopRepository {
    /** The full live catalogue (filtered and sorted on the device; see [com.onefera.app.data.model.ProductFilter]). */
    fun catalogue(): Flow<List<Product>>
    fun product(productId: String): Flow<Product?>
    suspend fun searchProducts(query: String): List<Product>

    fun wishlistIds(): Flow<Set<String>>
    fun wishlist(): Flow<List<Product>>
    suspend fun setWishlisted(product: Product, wished: Boolean): Result<Unit>

    fun cart(): Flow<List<CartItem>>
    suspend fun addToCart(product: Product, variant: String, quantity: Int): Result<Unit>
    suspend fun setQuantity(itemKey: String, quantity: Int): Result<Unit>
    suspend fun removeFromCart(itemKey: String): Result<Unit>

    fun savedAddress(): Flow<Address?>
    suspend fun saveAddress(address: Address): Result<Unit>

    /**
     * Step 1 of checkout: the backend re-prices the cart from the catalogue, checks stock and
     * creates an order awaiting payment (plus a Razorpay order when live payments are on).
     */
    suspend fun startCheckout(address: Address, method: PaymentMethod, couponId: String? = null): Result<CheckoutSession>

    /** Step 2: the payment result goes back to the backend, which verifies it and confirms the order. */
    suspend fun confirmPayment(session: CheckoutSession, payment: PaymentResult): Result<Order>

    fun orders(): Flow<List<Order>>
    fun order(orderId: String): Flow<Order?>
    suspend fun cancelOrder(orderId: String): Result<Unit>
}

/** An order waiting to be paid. [razorpayOrderId] is empty in simulated mode. */
data class CheckoutSession(
    val orderId: String,
    val amount: Int,
    val method: PaymentMethod,
    val razorpayOrderId: String = "",
    val razorpayKeyId: String = "",
    val simulated: Boolean = true,
)

data class PaymentResult(
    val paymentId: String,
    val signature: String = "",
)
