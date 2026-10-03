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
import com.onefera.app.data.model.Address
import com.onefera.app.data.model.CartItem
import com.onefera.app.data.model.CartTotals
import com.onefera.app.data.model.Order
import com.onefera.app.data.model.OrderItem
import com.onefera.app.data.model.OrderStatus
import com.onefera.app.data.model.PaymentMethod
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.formatRupees
import com.onefera.app.data.shop.CheckoutSession
import com.onefera.app.data.shop.PaymentResult
import com.onefera.app.data.shop.ShopRepository
import com.onefera.app.data.seller.ListingDraft
import com.onefera.app.data.seller.SellerRepository
import com.onefera.app.data.seller.nextForSeller
import com.onefera.app.data.media.MediaProcessor
import com.onefera.app.data.model.toSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.demoShopStore: DataStore<Preferences> by preferencesDataStore(name = "demo_shop")

/**
 * On-device shop for demo mode. Payments are simulated, and placed orders move through
 * packed → shipped → out for delivery → delivered over a few minutes so tracking can be tried.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DemoShopRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val auth: AuthRepository,
    private val catalog: DemoCatalog,
    private val accounts: DemoBackend,
    private val media: MediaProcessor,
    private val rewards: DemoRewardsRepository,
) : ShopRepository, SellerRepository {

    @Serializable
    data class ShopState(
        val wishlists: Map<String, List<String>> = emptyMap(),
        val carts: Map<String, List<CartItem>> = emptyMap(),
        val addresses: Map<String, Address> = emptyMap(),
        val orders: List<Order> = emptyList(),
        /** Units sold per product since install (lowers stock, raises popularity). */
        val sold: Map<String, Int> = emptyMap(),
        /** Products listed by users in Seller mode on this device. */
        val listings: List<Product> = emptyList(),
        /** Orders for the user's own listings: the seller advances these by hand. */
        val sellerManaged: Set<String> = emptySet(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val state = MutableStateFlow<ShopState?>(null)
    private val data: Flow<ShopState> = state.filterNotNull()

    /** Re-evaluates simulated order progress every 15 s while someone is watching. */
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(15_000)
        }
    }

    init {
        scope.launch {
            state.value = context.demoShopStore.data.first()[KEY]
                ?.let { runCatching { json.decodeFromString(ShopState.serializer(), it) }.getOrNull() } ?: ShopState()
        }
    }

    private suspend fun <T> update(block: (ShopState) -> Pair<ShopState, T>): T = mutex.withLock {
        val (next, result) = block(data.first())
        state.value = next
        context.demoShopStore.edit { it[KEY] = json.encodeToString(ShopState.serializer(), next) }
        result
    }

    private fun uid(): String = (auth.session.value as? SessionState.SignedIn)?.uid ?: throw UserFacingException("Please log in again.")

    private fun <T> perUser(empty: T, block: (String) -> Flow<T>): Flow<T> =
        auth.uidFlow().flatMapLatest { uid -> if (uid == null) flowOf(empty) else block(uid) }

    private suspend fun catalogue(sold: Map<String, Int>, listings: List<Product>): List<Product> = withContext(Dispatchers.IO) {
        (listings + catalog.products).map { p ->
            val n = sold[p.id] ?: 0
            if (n == 0) p else p.copy(stock = (p.stock - n).coerceAtLeast(0), soldCount = p.soldCount + n)
        }
    }

    override fun catalogue(): Flow<List<Product>> =
        data.map { it.sold to it.listings }.distinctUntilChanged().map { (sold, listings) -> catalogue(sold, listings) }

    override fun product(productId: String): Flow<Product?> = catalogue().map { list -> list.firstOrNull { it.id == productId } }

    override suspend fun searchProducts(query: String): List<Product> {
        val words = Product.keywordsFor(query)
        if (words.isEmpty()) return emptyList()
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        return catalogue().first()
            .filter { p -> val keys = p.searchKeywords().toSet(); terms.all { it.take(15) in keys } }
            .sortedByDescending { it.soldCount }
    }

    override fun wishlistIds(): Flow<Set<String>> = perUser(emptySet()) { uid ->
        data.map { it.wishlists[uid].orEmpty().toSet() }.distinctUntilChanged()
    }

    override fun wishlist(): Flow<List<Product>> = perUser(emptyList()) { uid ->
        combine(data.map { it.wishlists[uid].orEmpty() }.distinctUntilChanged(), catalogue()) { ids, products ->
            val byId = products.associateBy { it.id }
            ids.mapNotNull { byId[it] }
        }
    }

    override suspend fun setWishlisted(product: Product, wished: Boolean): Result<Unit> = runCatching {
        val uid = uid()
        update { s ->
            val current = s.wishlists[uid].orEmpty()
            val next = if (wished) listOf(product.id) + (current - product.id) else current - product.id
            s.copy(wishlists = s.wishlists + (uid to next)) to Unit
        }
    }

    override fun cart(): Flow<List<CartItem>> = perUser(emptyList()) { uid ->
        data.map { it.carts[uid].orEmpty().sortedByDescending { item -> item.addedAt } }.distinctUntilChanged()
    }

    override suspend fun addToCart(product: Product, variant: String, quantity: Int): Result<Unit> = runCatching {
        val uid = uid()
        if (product.sellerId == uid) throw UserFacingException("That's your own listing 😄")
        if (!product.inStock) throw UserFacingException("Sold out, sorry 😔")
        if (product.variants.isNotEmpty() && variant !in product.variants) throw UserFacingException("Pick a size or option first.")
        update { s ->
            val cart = s.carts[uid].orEmpty()
            val key = CartItem.keyFor(product.id, variant)
            val existing = cart.firstOrNull { it.key == key }
            val qty = ((existing?.quantity ?: 0) + quantity).coerceIn(1, minOf(Product.MAX_QUANTITY, product.stock))
            val item = CartItem(product.toSummary(), variant, qty, System.currentTimeMillis())
            s.copy(carts = s.carts + (uid to (cart.filterNot { it.key == key } + item))) to Unit
        }
    }

    override suspend fun setQuantity(itemKey: String, quantity: Int): Result<Unit> = runCatching {
        val uid = uid()
        update { s ->
            val cart = s.carts[uid].orEmpty().mapNotNull {
                when {
                    it.key != itemKey -> it
                    quantity <= 0 -> null
                    else -> it.copy(quantity = quantity.coerceAtMost(Product.MAX_QUANTITY))
                }
            }
            s.copy(carts = s.carts + (uid to cart)) to Unit
        }
    }

    override suspend fun removeFromCart(itemKey: String): Result<Unit> = setQuantity(itemKey, 0)

    override fun savedAddress(): Flow<Address?> = perUser(null) { uid -> data.map { it.addresses[uid] }.distinctUntilChanged() }

    override suspend fun saveAddress(address: Address): Result<Unit> = runCatching {
        val uid = uid()
        if (!address.isComplete) throw UserFacingException("Please fill in the delivery address.")
        update { s -> s.copy(addresses = s.addresses + (uid to address)) to Unit }
    }

    override suspend fun startCheckout(address: Address, method: PaymentMethod, couponId: String?): Result<CheckoutSession> = runCatching {
        val uid = uid()
        val coupon = couponId?.let { rewards.usableCoupon(uid, it) ?: throw UserFacingException("That coupon has expired or was already used.") }
        val plus = accounts.profileNow(uid)?.membership?.active()?.hasPlusPerks == true
        if (!address.isComplete) throw UserFacingException("Please fill in the delivery address.")
        delay(500) // feels like a network call
        update { s ->
            val cart = s.carts[uid].orEmpty()
            if (cart.isEmpty()) throw UserFacingException("Your cart is empty.")
            val products = catalogueNow(s)
            // Re-price from the catalogue, exactly like the Cloud Function does.
            val items = cart.map { line ->
                val p = products[line.product.id] ?: throw UserFacingException("${line.product.title} is no longer available.")
                if (p.stock < line.quantity) throw UserFacingException("Only ${p.stock} left of ${p.title}.")
                OrderItem(p.toSummary(), line.variant, line.quantity, p.price)
            }
            val totals = CartTotals.of(items.map { CartItem(it.product, it.variant, it.quantity) }, coupon, freeDelivery = plus)
            if (coupon != null && totals.discount == 0 && !coupon.waivesDelivery(totals.subtotal)) {
                throw UserFacingException("Add ${formatRupees(coupon.minOrder - totals.subtotal)} more to use this coupon.")
            }
            val now = System.currentTimeMillis()
            val order = Order(
                id = "OF" + UUID.randomUUID().toString().replace("-", "").take(10).uppercase(),
                buyerId = uid,
                items = items,
                subtotal = totals.subtotal,
                deliveryFee = totals.deliveryFee,
                discount = totals.discount,
                couponId = coupon?.id.orEmpty(),
                total = totals.total,
                address = address,
                paymentMethod = method,
                status = OrderStatus.PendingPayment,
                createdAt = now,
            )
            s.copy(orders = s.orders + order, addresses = s.addresses + (uid to address)) to
                CheckoutSession(order.id, order.total, method, simulated = true)
        }
    }

    private fun catalogueNow(s: ShopState): Map<String, Product> = (s.listings + catalog.products).associate { p ->
        p.id to p.copy(stock = (p.stock - (s.sold[p.id] ?: 0)).coerceAtLeast(0))
    }

    override suspend fun confirmPayment(session: CheckoutSession, payment: PaymentResult): Result<Order> = runCatching {
        val uid = uid()
        delay(400)
        update { s ->
            val order = s.orders.firstOrNull { it.id == session.orderId && it.buyerId == uid }
                ?: throw UserFacingException("We couldn't find that order.")
            if (order.status != OrderStatus.PendingPayment) return@update s to order
            val now = System.currentTimeMillis()
            val placed = order.copy(
                status = OrderStatus.Placed,
                paymentId = payment.paymentId,
                history = mapOf(OrderStatus.Placed.name to now),
                estimatedDelivery = now + 3 * DAY,
            )
            val sold = order.items.fold(s.sold) { acc, item -> acc + (item.product.id to (acc[item.product.id] ?: 0) + item.quantity) }
            s.copy(
                orders = s.orders.map { if (it.id == order.id) placed else it },
                carts = s.carts + (uid to emptyList()),
                sold = sold,
            ) to placed
        }.also { placed -> if (placed.couponId.isNotEmpty()) rewards.markCouponUsed(uid, placed.couponId) }
    }

    override fun orders(): Flow<List<Order>> = perUser(emptyList()) { uid ->
        ordersWhere { it.buyerId == uid }
    }

    override fun order(orderId: String): Flow<Order?> = orders().map { list -> list.firstOrNull { it.id == orderId } }

    /** Paid orders matching [filter], with simulated courier progress applied. */
    private fun ordersWhere(filter: (Order) -> Boolean): Flow<List<Order>> =
        combine(data.map { s -> s.orders.filter { it.status != OrderStatus.PendingPayment && filter(it) } to s.sellerManaged }.distinctUntilChanged(), clock) { (list, managed), now ->
            list.map { if (it.id in managed) it else progressed(it, now) }.sortedByDescending { it.createdAt }
        }.distinctUntilChanged()

    override suspend fun cancelOrder(orderId: String): Result<Unit> = runCatching {
        val uid = uid()
        update { s ->
            val order = s.orders.firstOrNull { it.id == orderId && it.buyerId == uid } ?: throw UserFacingException("Order not found.")
            val current = if (order.id in s.sellerManaged) order else progressed(order, System.currentTimeMillis())
            if (!current.status.canCancel) throw UserFacingException("This order has already shipped, so it can't be cancelled.")
            val now = System.currentTimeMillis()
            val cancelled = current.copy(status = OrderStatus.Cancelled, history = current.history + (OrderStatus.Cancelled.name to now))
            val sold = order.items.fold(s.sold) { acc, item -> acc + (item.product.id to ((acc[item.product.id] ?: 0) - item.quantity).coerceAtLeast(0)) }
            s.copy(orders = s.orders.map { if (it.id == orderId) cancelled else it }, sold = sold) to Unit
        }
    }

    // ---------------------------------------------------------------- seller mode

    override fun myListings(): Flow<List<Product>> = perUser(emptyList()) { uid ->
        catalogue().map { all -> all.filter { it.sellerId == uid }.sortedByDescending { it.createdAt } }
    }

    override fun listing(productId: String): Flow<Product?> = product(productId)

    override suspend fun saveListing(draft: ListingDraft, onProgress: (Float) -> Unit): Result<Product> = runCatching {
        val uid = uid()
        draft.problem()?.let { throw UserFacingException(it) }
        val me = accounts.profileNow(uid)?.toSummary() ?: throw UserFacingException("Finish setting up your profile first.")
        val id = draft.id ?: ("my-" + UUID.randomUUID().toString().take(10))
        val existing = data.first().listings.firstOrNull { it.id == id }
        if (draft.id != null && existing == null) throw UserFacingException("You can only edit your own listings.")
        // Copy new photos into app storage so they survive the picker's temporary permission.
        val stored = draft.newImages.mapIndexed { i, uri ->
            onProgress((i + 0.5f) / draft.newImages.size.coerceAtLeast(1))
            withContext(Dispatchers.IO) {
                val prepared = media.prepareImage(uri)
                val dir = java.io.File(context.filesDir, "demo_media").apply { mkdirs() }
                val file = java.io.File(dir, "listing-$id-${System.currentTimeMillis()}-$i.jpg")
                prepared.file.copyTo(file, overwrite = true)
                media.cleanUp(prepared)
                android.net.Uri.fromFile(file).toString()
            }
        }
        onProgress(1f)
        val sold = data.first().sold[id] ?: 0
        val product = Product(
            id = id,
            title = draft.title.trim(),
            brand = draft.brand.trim().ifEmpty { me.displayName },
            description = draft.description.trim(),
            category = draft.category,
            price = draft.price,
            mrp = draft.mrp.takeIf { it >= draft.price } ?: draft.price,
            images = draft.images + stored,
            highlights = draft.highlights.map { it.trim() }.filter { it.isNotEmpty() }.take(6),
            variants = draft.variants.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12),
            rating = existing?.rating ?: 0f,
            ratingCount = existing?.ratingCount ?: 0,
            soldCount = existing?.soldCount ?: 0,
            // Effective stock = listed stock − units sold, so store the sum.
            stock = draft.stock + sold,
            sellerId = uid,
            seller = me,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        update { s -> s.copy(listings = listOf(product) + s.listings.filterNot { it.id == id }) to Unit }
        if (existing == null) simulateFirstSale(product)
        product.copy(stock = draft.stock)
    }

    override suspend fun deleteListing(productId: String): Result<Unit> = runCatching {
        val uid = uid()
        update { s ->
            if (s.listings.none { it.id == productId && it.sellerId == uid }) throw UserFacingException("You can only remove your own listings.")
            s.copy(
                listings = s.listings.filterNot { it.id == productId },
                carts = s.carts.mapValues { (_, items) -> items.filterNot { it.product.id == productId } },
            ) to Unit
        }
    }

    override suspend fun setStock(productId: String, stock: Int): Result<Unit> = runCatching {
        val uid = uid()
        if (stock !in 0..ListingDraft.MAX_STOCK) throw UserFacingException("Stock must be between 0 and ${ListingDraft.MAX_STOCK}.")
        update { s ->
            if (s.listings.none { it.id == productId && it.sellerId == uid }) throw UserFacingException("You can only change your own listings.")
            val sold = s.sold[productId] ?: 0
            s.copy(listings = s.listings.map { if (it.id == productId) it.copy(stock = stock + sold) else it }) to Unit
        }
    }

    override fun sellerOrders(): Flow<List<Order>> = perUser(emptyList()) { uid ->
        ordersWhere { order -> order.items.any { it.product.sellerId == uid } }
    }

    override fun sellerOrder(orderId: String): Flow<Order?> = sellerOrders().map { list -> list.firstOrNull { it.id == orderId } }

    override suspend fun advanceOrder(orderId: String): Result<Unit> = runCatching {
        val uid = uid()
        update { s ->
            val order = s.orders.firstOrNull { o -> o.id == orderId && o.items.any { it.product.sellerId == uid } }
                ?: throw UserFacingException("Order not found.")
            val next = order.status.nextForSeller() ?: throw UserFacingException("This order is already ${order.status.label.lowercase()}.")
            val updated = order.copy(status = next, history = order.history + (next.name to System.currentTimeMillis()))
            s.copy(orders = s.orders.map { if (it.id == orderId) updated else it }, sellerManaged = s.sellerManaged + orderId) to Unit
        }
    }

    /** A demo shopper buys a brand-new listing shortly after it goes live, so the seller flow can be tried. */
    private fun simulateFirstSale(product: Product) {
        if (!product.inStock) return
        scope.launch {
            delay(20_000)
            val buyer = DemoSeed.creators.filter { it.uid != product.sellerId }.random()
            val now = System.currentTimeMillis()
            val qty = 1
            val order = Order(
                id = "OF" + UUID.randomUUID().toString().replace("-", "").take(10).uppercase(),
                buyerId = buyer.uid,
                items = listOf(OrderItem(product.toSummary(), product.variants.firstOrNull().orEmpty(), qty, product.price)),
                subtotal = product.price * qty,
                deliveryFee = if (product.price >= Product.FREE_DELIVERY_ABOVE) 0 else Product.DELIVERY_FEE,
                total = product.price * qty + if (product.price >= Product.FREE_DELIVERY_ABOVE) 0 else Product.DELIVERY_FEE,
                address = Address(buyer.displayName, "9000000000", "Demo street 1", "", buyer.city.ifEmpty { "Mumbai" }, "India", "400001"),
                paymentMethod = PaymentMethod.Upi,
                paymentId = "pay_sim_demo",
                status = OrderStatus.Placed,
                history = mapOf(OrderStatus.Placed.name to now),
                createdAt = now,
                estimatedDelivery = now + 3 * DAY,
            )
            runCatching {
                update { s ->
                    if (s.listings.none { it.id == product.id }) return@update s to Unit
                    s.copy(
                        orders = s.orders + order,
                        sellerManaged = s.sellerManaged + order.id,
                        sold = s.sold + (product.id to (s.sold[product.id] ?: 0) + qty),
                    ) to Unit
                }
            }
        }
    }

    /** Simulated courier: each step happens a few minutes after the previous one. */
    private fun progressed(order: Order, now: Long): Order {
        if (order.status != OrderStatus.Placed) return order
        val placedAt = order.reachedAt(OrderStatus.Placed) ?: return order
        var history = order.history
        var status = OrderStatus.Placed
        STEPS.forEach { (step, after) ->
            if (now >= placedAt + after) {
                history = history + (step.name to placedAt + after)
                status = step
            }
        }
        return order.copy(status = status, history = history)
    }

    private companion object {
        val KEY = stringPreferencesKey("state")
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
        val STEPS = listOf(
            OrderStatus.Packed to 1 * MINUTE,
            OrderStatus.Shipped to 3 * MINUTE,
            OrderStatus.OutForDelivery to 6 * MINUTE,
            OrderStatus.Delivered to 10 * MINUTE,
        )
    }
}
