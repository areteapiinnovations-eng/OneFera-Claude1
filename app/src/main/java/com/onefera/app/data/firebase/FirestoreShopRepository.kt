package com.onefera.app.data.firebase

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.backend.UserFacingException
import com.onefera.app.data.model.Address
import com.onefera.app.data.model.CartItem
import com.onefera.app.data.model.Order
import com.onefera.app.data.model.OrderItem
import com.onefera.app.data.model.OrderStatus
import com.onefera.app.data.model.PaymentMethod
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.ProductCategory
import com.onefera.app.data.shop.CheckoutSession
import com.onefera.app.data.shop.PaymentResult
import com.onefera.app.data.shop.ShopRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore layout:
 * - `products/{productId}`                 catalogue (written by sellers / admin; counters by Cloud Functions)
 * - `users/{uid}/wishlist/{productId}`     `{ addedAt }`
 * - `users/{uid}/cart/{itemKey}`           product summary, variant, quantity
 * - `users/{uid}/addresses/default`        last used delivery address
 * - `orders/{orderId}`                     created, paid and cancelled only through Cloud Functions
 *
 * Checkout runs in Cloud Functions so prices, stock and payment verification never trust the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FirestoreShopRepository @Inject constructor(
    private val auth: AuthRepository,
) : ShopRepository {

    private val db by lazy { FirebaseFirestore.getInstance() }
    private val functions by lazy { FirebaseFunctions.getInstance(REGION) }
    private val products get() = db.collection("products")
    private fun user(uid: String) = db.collection("users").document(uid)

    private fun <T> perUser(empty: T, block: (String) -> Flow<T>): Flow<T> =
        auth.uidFlow().flatMapLatest { uid -> if (uid == null) flowOf(empty) else block(uid) }

    override fun catalogue(): Flow<List<Product>> =
        products.orderBy("soldCount", Query.Direction.DESCENDING).limit(CATALOGUE_LIMIT)
            .snapshotFlow().map { s -> s.documents.map { it.toProduct() } }

    override fun product(productId: String): Flow<Product?> =
        products.document(productId).snapshotFlow().map { if (it.exists()) it.toProduct() else null }

    override suspend fun searchProducts(query: String): List<Product> {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.map { it.take(15) }
        if (terms.isEmpty()) return emptyList()
        return runCatching {
            // Firestore allows one array-contains per query: match the longest word, then refine here.
            products.whereArrayContains("keywords", terms.maxBy { it.length }).limit(40).get().await()
                .documents.map { it.toProduct() }
                .filter { p -> val keys = p.searchKeywords().toSet(); terms.all { it in keys } }
                .sortedByDescending { it.soldCount }
        }.getOrDefault(emptyList())
    }

    override fun wishlistIds(): Flow<Set<String>> = perUser(emptySet()) { uid ->
        user(uid).collection("wishlist").snapshotFlow().map { s -> s.documents.map { it.id }.toSet() }
    }

    override fun wishlist(): Flow<List<Product>> = perUser(emptyList()) { uid ->
        user(uid).collection("wishlist").orderBy("addedAt", Query.Direction.DESCENDING).limit(60).snapshotFlow()
            .flatMapLatest { s ->
                val ids = s.documents.map { it.id }
                if (ids.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(ids.map { product(it) }) { list -> list.filterNotNull() }
                }
            }
    }

    override suspend fun setWishlisted(product: Product, wished: Boolean): Result<Unit> = runFriendly {
        val ref = user(auth.currentUid()).collection("wishlist").document(product.id)
        if (wished) ref.set(mapOf("addedAt" to FieldValue.serverTimestamp())).await() else ref.delete().await()
    }

    override fun cart(): Flow<List<CartItem>> = perUser(emptyList()) { uid ->
        user(uid).collection("cart").orderBy("addedAt", Query.Direction.DESCENDING).snapshotFlow()
            .map { s -> s.documents.map { it.toCartItem() } }
    }

    override suspend fun addToCart(product: Product, variant: String, quantity: Int): Result<Unit> = runFriendly {
        val uid = auth.currentUid()
        if (!product.inStock) throw UserFacingException("Sold out, sorry 😔")
        if (product.variants.isNotEmpty() && variant !in product.variants) throw UserFacingException("Pick a size or option first.")
        val key = CartItem.keyFor(product.id, variant)
        val ref = user(uid).collection("cart").document(key)
        db.runTransaction { tx ->
            val existing = tx.get(ref).getLong("quantity")?.toInt() ?: 0
            val qty = (existing + quantity).coerceIn(1, minOf(Product.MAX_QUANTITY, product.stock))
            tx.set(
                ref,
                mapOf(
                    "product" to product.toSummary().toMap(),
                    "variant" to variant,
                    "quantity" to qty,
                    "addedAt" to FieldValue.serverTimestamp(),
                ),
            )
        }.await()
    }

    override suspend fun setQuantity(itemKey: String, quantity: Int): Result<Unit> = runFriendly {
        val ref = user(auth.currentUid()).collection("cart").document(itemKey)
        if (quantity <= 0) ref.delete().await() else ref.update("quantity", quantity.coerceAtMost(Product.MAX_QUANTITY)).await()
    }

    override suspend fun removeFromCart(itemKey: String): Result<Unit> = setQuantity(itemKey, 0)

    override fun savedAddress(): Flow<Address?> = perUser(null) { uid ->
        user(uid).collection("addresses").document("default").snapshotFlow().map { if (it.exists()) it.toAddress() else null }
    }

    override suspend fun saveAddress(address: Address): Result<Unit> = runFriendly {
        if (!address.isComplete) throw UserFacingException("Please fill in the delivery address.")
        user(auth.currentUid()).collection("addresses").document("default").set(address.toMap()).await()
    }

    override suspend fun startCheckout(address: Address, method: PaymentMethod): Result<CheckoutSession> = runFriendly {
        auth.currentUid()
        if (!address.isComplete) throw UserFacingException("Please fill in the delivery address.")
        if (cart().first().isEmpty()) throw UserFacingException("Your cart is empty.")
        val result = call("startCheckout", mapOf("address" to address.toMap(), "method" to method.name))
        CheckoutSession(
            orderId = result["orderId"] as? String ?: throw UserFacingException("Checkout failed. Please try again."),
            amount = (result["amount"] as? Number)?.toInt() ?: 0,
            method = method,
            razorpayOrderId = result["razorpayOrderId"] as? String ?: "",
            razorpayKeyId = result["keyId"] as? String ?: "",
            simulated = result["simulated"] as? Boolean ?: true,
        )
    }

    override suspend fun confirmPayment(session: CheckoutSession, payment: PaymentResult): Result<Order> = runFriendly {
        auth.currentUid()
        call(
            "confirmPayment",
            mapOf(
                "orderId" to session.orderId,
                "paymentId" to payment.paymentId,
                "razorpayOrderId" to session.razorpayOrderId,
                "signature" to payment.signature,
            ),
        )
        db.collection("orders").document(session.orderId).get().await().toOrder()
    }

    override fun orders(): Flow<List<Order>> = perUser(emptyList()) { uid ->
        db.collection("orders").whereEqualTo("buyerId", uid).orderBy("createdAt", Query.Direction.DESCENDING).limit(50)
            .snapshotFlow().map { s -> s.documents.map { it.toOrder() }.filter { it.status != OrderStatus.PendingPayment } }
    }

    override fun order(orderId: String): Flow<Order?> =
        db.collection("orders").document(orderId).snapshotFlow().map { if (it.exists()) it.toOrder() else null }

    override suspend fun cancelOrder(orderId: String): Result<Unit> = runFriendly {
        call("cancelOrder", mapOf("orderId" to orderId))
        Unit
    }

    /** Calls an HTTPS callable function; its `HttpsError` messages are already user friendly. */
    private suspend fun call(name: String, data: Map<String, Any?>): Map<*, *> = try {
        functions.getHttpsCallable(name).call(data).await().data as? Map<*, *> ?: emptyMap<String, Any>()
    } catch (e: FirebaseFunctionsException) {
        val friendly = when (e.code) {
            FirebaseFunctionsException.Code.INVALID_ARGUMENT,
            FirebaseFunctionsException.Code.FAILED_PRECONDITION,
            FirebaseFunctionsException.Code.NOT_FOUND,
            FirebaseFunctionsException.Code.PERMISSION_DENIED,
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
            -> e.message
            FirebaseFunctionsException.Code.UNAUTHENTICATED -> "Please log in again."
            else -> null
        }
        throw UserFacingException(friendly ?: "Something went wrong. Check your connection and try again.", e)
    }

    private companion object {
        const val REGION = "asia-south1"
        const val CATALOGUE_LIMIT = 300L
    }
}

private fun Any?.asInt(): Int = (this as? Number)?.toInt() ?: 0

private fun DocumentSnapshot.millisOrZero(field: String): Long =
    getTimestamp(field, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time ?: 0L

internal fun DocumentSnapshot.toProduct(): Product = Product(
    id = id,
    title = getString("title").orEmpty(),
    brand = getString("brand").orEmpty(),
    description = getString("description").orEmpty(),
    category = runCatching { ProductCategory.valueOf(getString("category").orEmpty()) }.getOrDefault(ProductCategory.More),
    price = get("price").asInt(),
    mrp = get("mrp").asInt(),
    images = (get("images") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    highlights = (get("highlights") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    variants = (get("variants") as? List<*>)?.filterIsInstance<String>().orEmpty(),
    rating = (get("rating") as? Number)?.toFloat() ?: 0f,
    ratingCount = get("ratingCount").asInt(),
    soldCount = get("soldCount").asInt(),
    stock = get("stock").asInt(),
    sellerId = getString("sellerId").orEmpty(),
    seller = (get("seller") as? Map<*, *>).toUserSummary(),
    isDrop = getBoolean("isDrop") ?: false,
    createdAt = millisOrZero("createdAt"),
)

private fun DocumentSnapshot.toCartItem(): CartItem = CartItem(
    product = (get("product") as? Map<*, *>)?.toProductSummary() ?: com.onefera.app.data.model.ProductSummary(id = id),
    variant = getString("variant").orEmpty(),
    quantity = get("quantity").asInt().coerceAtLeast(1),
    addedAt = millisOrZero("addedAt"),
)

internal fun Address.toMap(): Map<String, Any?> = mapOf(
    "name" to name,
    "phone" to phone,
    "line1" to line1,
    "line2" to line2,
    "city" to city,
    "state" to state,
    "pincode" to pincode,
)

private fun Map<*, *>?.toAddress(): Address = Address(
    name = this?.get("name") as? String ?: "",
    phone = this?.get("phone") as? String ?: "",
    line1 = this?.get("line1") as? String ?: "",
    line2 = this?.get("line2") as? String ?: "",
    city = this?.get("city") as? String ?: "",
    state = this?.get("state") as? String ?: "",
    pincode = this?.get("pincode") as? String ?: "",
)

private fun DocumentSnapshot.toAddress(): Address = data.toAddress()

internal fun DocumentSnapshot.toOrder(): Order = Order(
    id = id,
    buyerId = getString("buyerId").orEmpty(),
    items = (get("items") as? List<*>)?.mapNotNull { raw ->
        (raw as? Map<*, *>)?.let {
            OrderItem(
                product = (it["product"] as? Map<*, *>)?.toProductSummary() ?: com.onefera.app.data.model.ProductSummary(),
                variant = it["variant"] as? String ?: "",
                quantity = it["quantity"].asInt(),
                price = it["price"].asInt(),
            )
        }
    }.orEmpty(),
    subtotal = get("subtotal").asInt(),
    deliveryFee = get("deliveryFee").asInt(),
    total = get("total").asInt(),
    address = (get("address") as? Map<*, *>).toAddress(),
    paymentMethod = runCatching { PaymentMethod.valueOf(getString("paymentMethod").orEmpty()) }.getOrDefault(PaymentMethod.Upi),
    paymentId = getString("paymentId").orEmpty(),
    status = runCatching { OrderStatus.valueOf(getString("status").orEmpty()) }.getOrDefault(OrderStatus.Placed),
    history = (get("history") as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
        val millis = (v as? com.google.firebase.Timestamp)?.toDate()?.time ?: (v as? Number)?.toLong()
        if (k is String && millis != null) k to millis else null
    }?.toMap().orEmpty(),
    createdAt = millisOrZero("createdAt"),
    estimatedDelivery = getLong("estimatedDelivery") ?: 0L,
)
