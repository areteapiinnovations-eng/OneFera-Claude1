package com.onefera.app.feature.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.CartTotals
import com.onefera.app.data.model.Order
import com.onefera.app.data.model.OrderStatus
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.formatRupees
import com.onefera.app.data.shop.ShopRepository
import com.onefera.app.navigation.OrderRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

internal fun formatDate(millis: Long, pattern: String = "d MMM, h:mm a"): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))

// region Orders list

@HiltViewModel
class OrdersViewModel @Inject constructor(shop: ShopRepository) : ViewModel() {
    val orders: StateFlow<List<Order>?> = shop.orders().map<List<Order>, List<Order>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun OrdersScreen(onBack: () -> Unit, viewModel: OrdersViewModel = hiltViewModel()) {
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.4f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Your orders", onBack = onBack)
            val list = orders
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    icon = R.drawable.ic_package,
                    title = "No orders yet",
                    message = "When you buy something, you can track it right here.",
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    action = { GradientButton("Explore the shop", onClick = onBack, modifier = Modifier.width(220.dp)) },
                )
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.id }) { order -> OrderRow(order, onClick = { actions.openOrder(order.id) }) }
                }
            }
        }
    }
}

@Composable
private fun OrderRow(order: Order, onClick: () -> Unit) {
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(22.dp)
    val first = order.items.firstOrNull()?.product
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (extras.isDark) extras.glass else MaterialTheme.colorScheme.surface)
            .border(1.dp, extras.glassBorder, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductImage(first?.imageUrl, null, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)), placeholder = first?.brand.orEmpty())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            StatusBadge(order.status)
            Text(
                first?.title.orEmpty() + if (order.items.size > 1) " + ${order.items.size - 1} more" else "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${formatRupees(order.total)} · ${formatDate(order.createdAt, "d MMM yyyy")}", style = MaterialTheme.typography.labelSmall, color = extras.muted)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = extras.muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun StatusBadge(status: OrderStatus) {
    val color = when (status) {
        OrderStatus.Delivered -> StatusColors.Success
        OrderStatus.Cancelled -> StatusColors.Error
        OrderStatus.PendingPayment -> StatusColors.Warning
        else -> null
    }
    val extras = OneFeraTheme.extras
    Text(
        status.label,
        style = MaterialTheme.typography.labelMedium,
        color = if (color == null) extras.onGradient else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (color == null) Modifier.background(extras.horizontalGradient()) else Modifier.background(color))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

// endregion

// region Order detail & tracking

@HiltViewModel
class OrderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val shop: ShopRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<OrderRoute>()
    val justPlaced = route.justPlaced
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val order: StateFlow<Order?> = shop.order(route.orderId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var cancelling by mutableStateOf(false)
        private set

    fun cancel() {
        if (cancelling) return
        cancelling = true
        viewModelScope.launch {
            shop.cancelOrder(route.orderId)
                .onSuccess { _messages.send("Order cancelled. Any payment will be refunded in 5–7 days.") }
                .onFailure { _messages.send(it.message ?: "Couldn't cancel this order.") }
            cancelling = false
        }
    }
}

@Composable
fun OrderScreen(onBack: () -> Unit, viewModel: OrderViewModel = hiltViewModel()) {
    val order by viewModel.order.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.4f) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { ScreenHeader(if (viewModel.justPlaced) "Order placed" else "Order details", onBack = onBack) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            order?.let { o -> OrderBody(o, padding, justPlaced = viewModel.justPlaced, cancelling = viewModel.cancelling, onCancel = { confirmCancel = true }, onBack = onBack) }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel this order?") },
            text = { Text("If you paid online, the full amount goes back to your original payment method.") },
            confirmButton = { TextButton(onClick = { confirmCancel = false; viewModel.cancel() }) { Text("Cancel order", color = StatusColors.Error) } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun OrderBody(o: Order, padding: PaddingValues, justPlaced: Boolean, cancelling: Boolean, onCancel: () -> Unit, onBack: () -> Unit) {
    val actions = LocalAppActions.current
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (justPlaced) PlacedBanner(o)
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Order ${o.id}", style = MaterialTheme.typography.titleMedium)
                    Text("Placed ${formatDate(o.createdAt)}", style = MaterialTheme.typography.labelSmall, color = OneFeraTheme.extras.muted)
                }
                StatusBadge(o.status)
            }
            Spacer(Modifier.height(14.dp))
            Timeline(o)
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Items", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            o.items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { actions.openProduct(item.product.id) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProductImage(item.product.imageUrl, null, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)), placeholder = item.product.brand)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.product.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull("Qty ${item.quantity}", item.variant.ifEmpty { null }).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = OneFeraTheme.extras.muted,
                        )
                    }
                    Text(formatRupees(item.price * item.quantity), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        TotalsCard(CartTotals(o.subtotal, savings = o.items.sumOf { (it.product.mrp - it.price).coerceAtLeast(0) * it.quantity }, deliveryFee = o.deliveryFee))
        GlassCard(Modifier.fillMaxWidth()) {
            Text("Delivering to", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("${o.address.name} · ${o.address.phone}", style = MaterialTheme.typography.bodyMedium)
            Text(o.address.oneLine(), style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
            Spacer(Modifier.height(10.dp))
            Text("Paid with ${o.paymentMethod.label}", style = MaterialTheme.typography.bodyMedium)
            if (o.paymentId.isNotEmpty()) Text("Payment ID ${o.paymentId}", style = MaterialTheme.typography.labelSmall, color = OneFeraTheme.extras.muted)
        }
        if (o.status.canCancel) {
            GlassButton("Cancel order", onClick = onCancel, enabled = !cancelling, modifier = Modifier.fillMaxWidth())
        }
        if (justPlaced) {
            GradientButton("Keep shopping", onClick = onBack)
        }
    }
}

@Composable
private fun PlacedBanner(order: Order) {
    val extras = OneFeraTheme.extras
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(extras.gradientBrush()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Yay, it's yours! 🎉", style = MaterialTheme.typography.headlineSmall, color = extras.onGradient)
        Text(
            if (order.estimatedDelivery > 0) "Arriving by ${formatDate(order.estimatedDelivery, "EEE, d MMM")}" else "We'll keep you posted on every step.",
            style = MaterialTheme.typography.bodyMedium,
            color = extras.onGradient,
        )
    }
}

@Composable
internal fun Timeline(order: Order) {
    val extras = OneFeraTheme.extras
    val steps = if (order.status == OrderStatus.Cancelled) {
        OrderStatus.timeline.filter { order.reachedAt(it) != null } + OrderStatus.Cancelled
    } else {
        OrderStatus.timeline
    }
    Column {
        steps.forEachIndexed { index, step ->
            val at = order.reachedAt(step)
            val done = at != null
            val isLast = index == steps.lastIndex
            Row {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .then(
                                when {
                                    step == OrderStatus.Cancelled -> Modifier.background(StatusColors.Error)
                                    done -> Modifier.background(extras.gradientBrush())
                                    else -> Modifier.border(2.dp, extras.glassBorder, CircleShape)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) Icon(painterResource(if (step == OrderStatus.Cancelled) R.drawable.ic_close else R.drawable.ic_check), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                    if (!isLast) {
                        val nextDone = order.reachedAt(steps[index + 1]) != null
                        Box(
                            Modifier
                                .width(2.dp)
                                .height(30.dp)
                                .then(if (nextDone) Modifier.background(extras.gradientBrush()) else Modifier.background(extras.glassBorder)),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.padding(top = 1.dp)) {
                    Text(step.label, style = MaterialTheme.typography.titleSmall, color = if (done) MaterialTheme.colorScheme.onSurface else extras.muted)
                    if (at != null) Text(formatDate(at), style = MaterialTheme.typography.labelSmall, color = extras.muted)
                }
            }
        }
    }
}

// endregion

// region Wishlist

@HiltViewModel
class WishlistViewModel @Inject constructor(private val shop: ShopRepository) : ViewModel() {
    val products: StateFlow<List<Product>?> = shop.wishlist().map<List<Product>, List<Product>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun remove(product: Product) = viewModelScope.launch { shop.setWishlisted(product, false) }
}

@Composable
fun WishlistScreen(onBack: () -> Unit, viewModel: WishlistViewModel = hiltViewModel()) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.4f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Wishlist", onBack = onBack)
            val list = products
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    icon = R.drawable.ic_heart,
                    title = "Nothing saved yet",
                    message = "Tap the ♡ on anything you love to keep it here.",
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list.size, key = { list[it].id }) { i ->
                        val p = list[i]
                        ProductCard(p, wished = true, onClick = { actions.openProduct(p.id) }, onToggleWish = { viewModel.remove(p) })
                    }
                }
            }
        }
    }
}

// endregion
