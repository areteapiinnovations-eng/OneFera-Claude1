package com.onefera.app.feature.seller

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.onefera.app.R
import com.onefera.app.core.common.compactCount
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.component.SelectChip
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.model.Order
import com.onefera.app.data.model.OrderStatus
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.formatRupees
import com.onefera.app.data.seller.SellerRepository
import com.onefera.app.data.seller.SellerStats
import com.onefera.app.feature.shop.ProductImage
import com.onefera.app.feature.shop.QuantityStepper
import com.onefera.app.feature.shop.StatusBadge
import com.onefera.app.feature.shop.formatDate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class SellerSection(val label: String) { Overview("Overview"), Listings("Listings"), Orders("Orders") }

enum class OrderFilter(val label: String, val matches: (OrderStatus) -> Boolean) {
    All("All", { true }),
    ToShip("To ship", { it == OrderStatus.Placed || it == OrderStatus.Packed }),
    InTransit("In transit", { it == OrderStatus.Shipped || it == OrderStatus.OutForDelivery }),
    Delivered("Delivered", { it == OrderStatus.Delivered }),
    Cancelled("Cancelled", { it == OrderStatus.Cancelled }),
}

data class SellerHubUiState(
    val loading: Boolean = true,
    val sellerId: String = "",
    val listings: List<Product> = emptyList(),
    val orders: List<Order> = emptyList(),
    val stats: SellerStats = SellerStats(),
)

@HiltViewModel
class SellerHubViewModel @Inject constructor(
    auth: AuthRepository,
    private val seller: SellerRepository,
) : ViewModel() {
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<SellerHubUiState> = combine(auth.uidFlow(), seller.myListings(), seller.sellerOrders()) { uid, listings, orders ->
        val me = uid.orEmpty()
        SellerHubUiState(false, me, listings, orders, SellerStats.compute(me, orders, listings))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SellerHubUiState())

    fun setStock(product: Product, stock: Int) = viewModelScope.launch {
        seller.setStock(product.id, stock).onFailure { _messages.send(it.message ?: "Couldn't update stock.") }
    }
}

@Composable
fun SellerHubScreen(onBack: () -> Unit, viewModel: SellerHubViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val snackbar = remember { SnackbarHostState() }
    var section by rememberSaveable { mutableStateOf(SellerSection.Overview) }
    var filter by rememberSaveable { mutableStateOf(OrderFilter.All) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.45f) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column {
                    ScreenHeader("Seller hub 🏪", onBack = onBack)
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SellerSection.entries) { s ->
                            val badge = if (s == SellerSection.Orders && state.stats.toShip > 0) " · ${state.stats.toShip}" else ""
                            SelectChip(s.label + badge, selected = s == section, onClick = { section = s })
                        }
                    }
                }
            },
            bottomBar = {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    GradientButton("New listing", onClick = { actions.editListing("") }, trailingIcon = R.drawable.ic_add)
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.loading) return@LazyColumn
                when (section) {
                    SellerSection.Overview -> overview(state, onSeeOrders = { section = SellerSection.Orders; filter = OrderFilter.ToShip }, onRestock = viewModel::setStock)
                    SellerSection.Listings -> listings(state.listings, onStock = viewModel::setStock)
                    SellerSection.Orders -> orders(state.orders, state.sellerId, filter, onFilter = { filter = it })
                }
            }
        }
    }
}

// region Overview

private fun LazyListScope.overview(state: SellerHubUiState, onSeeOrders: () -> Unit, onRestock: (Product, Int) -> Unit) {
    val stats = state.stats
    if (state.listings.isEmpty() && state.orders.isEmpty()) {
        item {
            EmptyState(
                icon = R.drawable.ic_storefront,
                title = "Your store is ready ✨",
                message = "List your first product. It shows up in the Shop and you can tag it in your posts.",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }
    item { RevenueCard(stats) }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("To ship", stats.toShip, R.drawable.ic_package, Modifier.weight(1f), highlight = stats.toShip > 0, onClick = onSeeOrders)
            StatTile("In transit", stats.inTransit, R.drawable.ic_shipping, Modifier.weight(1f))
            StatTile("Delivered", stats.delivered, R.drawable.ic_check_circle_filled, Modifier.weight(1f))
        }
    }
    item { SalesChart(stats) }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Live listings", stats.activeListings, R.drawable.ic_storefront, Modifier.weight(1f))
            StatTile("Units sold", stats.unitsSold, R.drawable.ic_sell, Modifier.weight(1f))
            StatTile("Sold out", stats.outOfStock, R.drawable.ic_inventory, Modifier.weight(1f), warn = stats.outOfStock > 0)
        }
    }
    if (stats.topProducts.isNotEmpty()) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Best sellers", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                stats.topProducts.forEachIndexed { i, top ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(22.dp).gradientTint(OneFeraTheme.extras.horizontalGradient()))
                        ProductImage(top.imageUrl, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), placeholder = top.title)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(top.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${top.units} sold", style = MaterialTheme.typography.labelSmall, color = OneFeraTheme.extras.muted)
                        }
                        Text(formatRupees(top.revenue), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
    if (stats.lowStock.isNotEmpty()) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = StatusColors.Warning, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Running low", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                stats.lowStock.take(5).forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${p.stock} left", style = MaterialTheme.typography.labelMedium, color = StatusColors.Warning)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "+10",
                            style = MaterialTheme.typography.labelLarge,
                            color = OneFeraTheme.extras.onGradient,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(OneFeraTheme.extras.horizontalGradient())
                                .clickable { onRestock(p, p.stock + 10) }
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .semantics { contentDescription = "Restock ${p.title} by 10" },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RevenueCard(stats: SellerStats) {
    val extras = OneFeraTheme.extras
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(extras.gradientBrush()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("TOTAL SALES", style = MaterialTheme.typography.labelMedium, color = extras.onGradient.copy(alpha = 0.85f))
        Text(formatRupees(stats.revenue), style = MaterialTheme.typography.headlineLarge, color = extras.onGradient)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${stats.orders} orders", style = MaterialTheme.typography.labelLarge, color = extras.onGradient)
            Text("Avg ${formatRupees(stats.averageOrderValue)}", style = MaterialTheme.typography.labelLarge, color = extras.onGradient)
            stats.weekChangePercent?.let { change ->
                Text(
                    (if (change >= 0) "▲ $change%" else "▼ ${-change}%") + " vs last week",
                    style = MaterialTheme.typography.labelLarge,
                    color = extras.onGradient,
                )
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, icon: Int, modifier: Modifier, highlight: Boolean = false, warn: Boolean = false, onClick: (() -> Unit)? = null) {
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (extras.isDark) extras.glass else MaterialTheme.colorScheme.surface)
            .then(if (highlight) Modifier.border(1.5.dp, extras.gradientBrush(), shape) else Modifier.border(1.dp, extras.glassBorder, shape))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (warn) {
            Icon(painterResource(icon), contentDescription = null, tint = StatusColors.Warning, modifier = Modifier.size(20.dp))
        } else {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp).gradientTint(extras.gradientBrush()))
        }
        Text(compactCount(value), style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = extras.muted, maxLines = 1)
    }
}

@Composable
private fun SalesChart(stats: SellerStats) {
    val extras = OneFeraTheme.extras
    val days = stats.last7Days
    val max = (days.maxOfOrNull { it.revenue } ?: 0).coerceAtLeast(1)
    val dayFormat = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
    val gradient = extras.gradient
    val empty = extras.glassBorder
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Last 7 days", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(formatRupees(stats.revenueLast7Days), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = "Sales chart: " + days.joinToString { "${dayFormat.format(Date(it.dayStart))} ${formatRupees(it.revenue)}" } },
        ) {
            val slot = size.width / days.size.coerceAtLeast(1)
            val barWidth = slot * 0.55f
            days.forEachIndexed { i, day ->
                val h = if (day.revenue == 0) 6f else (size.height * day.revenue / max).coerceAtLeast(6f)
                val left = i * slot + (slot - barWidth) / 2
                drawRoundRect(
                    brush = if (day.revenue == 0) Brush.verticalGradient(listOf(empty, empty)) else Brush.verticalGradient(gradient, startY = size.height - h, endY = size.height),
                    topLeft = Offset(left, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(10f, 10f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            days.forEach { day ->
                Text(
                    dayFormat.format(Date(day.dayStart)).take(3),
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// endregion

// region Listings

private fun LazyListScope.listings(listings: List<Product>, onStock: (Product, Int) -> Unit) {
    if (listings.isEmpty()) {
        item {
            EmptyState(
                icon = R.drawable.ic_sell,
                title = "No listings yet",
                message = "Tap New listing to add photos, a price and stock.",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }
    items(listings, key = { it.id }) { product -> ListingRow(product, onStock = { onStock(product, it) }) }
}

@Composable
private fun ListingRow(product: Product, onStock: (Int) -> Unit) {
    val extras = OneFeraTheme.extras
    val actions = LocalAppActions.current
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (extras.isDark) extras.glass else MaterialTheme.colorScheme.surface)
            .border(1.dp, extras.glassBorder, shape)
            .clickable { actions.editListing(product.id) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductImage(product.imageUrl, product.title, Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)), placeholder = product.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(product.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${formatRupees(product.price)} · ${product.soldCount} sold", style = MaterialTheme.typography.labelSmall, color = extras.muted)
            Text(
                when {
                    !product.inStock -> "Sold out"
                    product.stock <= SellerStats.LOW_STOCK -> "Low stock"
                    else -> "In stock"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    !product.inStock -> StatusColors.Error
                    product.stock <= SellerStats.LOW_STOCK -> StatusColors.Warning
                    else -> StatusColors.Success
                },
            )
        }
        QuantityStepper(product.stock, onChange = onStock, max = com.onefera.app.data.seller.ListingDraft.MAX_STOCK, min = 0)
    }
}

// endregion

// region Orders

private fun LazyListScope.orders(orders: List<Order>, sellerId: String, filter: OrderFilter, onFilter: (OrderFilter) -> Unit) {
    item {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(OrderFilter.entries) { f ->
                val count = orders.count { f.matches(it.status) }
                SelectChip("${f.label} $count", selected = f == filter, onClick = { onFilter(f) })
            }
        }
    }
    val shown = orders.filter { filter.matches(it.status) }
    if (shown.isEmpty()) {
        item {
            EmptyState(
                icon = R.drawable.ic_package,
                title = if (orders.isEmpty()) "No orders yet" else "Nothing here",
                message = if (orders.isEmpty()) "When someone buys from you, the order lands here first." else "No orders match this filter.",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }
    items(shown, key = { it.id }) { order -> SellerOrderRow(order, sellerId) }
}

@Composable
private fun SellerOrderRow(order: Order, sellerId: String) {
    val extras = OneFeraTheme.extras
    val actions = LocalAppActions.current
    val mine = order.items.filter { it.product.sellerId == sellerId }
    val first = mine.firstOrNull()?.product
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (extras.isDark) extras.glass else MaterialTheme.colorScheme.surface)
            .border(1.dp, extras.glassBorder, shape)
            .clickable { actions.openSellerOrder(order.id) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductImage(first?.imageUrl, null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)), placeholder = first?.title.orEmpty())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            StatusBadge(order.status)
            Text(
                first?.title.orEmpty() + if (mine.size > 1) " + ${mine.size - 1} more" else "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${order.address.name} · ${formatDate(order.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = extras.muted,
                maxLines = 1,
            )
        }
        Text(formatRupees(mine.sumOf { it.price * it.quantity }), style = MaterialTheme.typography.titleSmall)
    }
}

// endregion
