package com.onefera.app.feature.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.CartItem
import com.onefera.app.data.model.CartTotals
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.formatRupees
import com.onefera.app.data.shop.ShopRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CartUiState(val loading: Boolean = true, val items: List<CartItem> = emptyList()) {
    val totals: CartTotals get() = CartTotals.of(items)
}

@HiltViewModel
class CartViewModel @Inject constructor(private val shop: ShopRepository) : ViewModel() {
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<CartUiState> = shop.cart().map { CartUiState(false, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CartUiState())

    fun setQuantity(item: CartItem, quantity: Int) = viewModelScope.launch {
        shop.setQuantity(item.key, quantity).onFailure { _messages.send(it.message ?: "Couldn't update your cart.") }
    }

    fun remove(item: CartItem) = viewModelScope.launch {
        shop.removeFromCart(item.key).onFailure { _messages.send(it.message ?: "Couldn't update your cart.") }
    }
}

@Composable
fun CartScreen(onBack: () -> Unit, viewModel: CartViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.4f) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { ScreenHeader(if (state.items.isEmpty()) "Your cart" else "Your cart (${state.items.sumOf { it.quantity }})", onBack = onBack) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (state.items.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                            .navigationBarsPadding()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(formatRupees(state.totals.total), style = MaterialTheme.typography.titleLarge)
                            Text("incl. delivery", style = MaterialTheme.typography.labelSmall, color = OneFeraTheme.extras.muted)
                        }
                        GradientButton("Checkout", onClick = actions.checkout, modifier = Modifier.weight(1.3f), trailingIcon = R.drawable.ic_chevron_right)
                    }
                }
            },
        ) { padding ->
            if (!state.loading && state.items.isEmpty()) {
                EmptyState(
                    icon = R.drawable.ic_cart,
                    title = "Your cart is feeling light",
                    message = "Add something you love and it'll wait for you here.",
                    modifier = Modifier.fillMaxWidth().padding(padding).padding(top = 40.dp),
                    action = { GradientButton("Start shopping", onClick = onBack, modifier = Modifier.width(220.dp)) },
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.items, key = { it.key }) { item ->
                        CartRow(item, onQuantity = { viewModel.setQuantity(item, it) }, onRemove = { viewModel.remove(item) }, onOpen = { actions.openProduct(item.product.id) })
                    }
                    item { TotalsCard(state.totals) }
                }
            }
        }
    }
}

@Composable
private fun CartRow(item: CartItem, onQuantity: (Int) -> Unit, onRemove: () -> Unit, onOpen: () -> Unit) {
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (extras.isDark) extras.glass else MaterialTheme.colorScheme.surface)
            .border(1.dp, extras.glassBorder, shape)
            .clickable(onClick = onOpen)
            .padding(10.dp),
    ) {
        ProductImage(item.product.imageUrl, item.product.title, Modifier.size(92.dp).clip(RoundedCornerShape(16.dp)), placeholder = item.product.brand)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.product.brand, style = MaterialTheme.typography.labelSmall, color = extras.muted)
                    Text(item.product.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Remove ${item.product.title}", tint = extras.muted, modifier = Modifier.size(18.dp))
                }
            }
            if (item.variant.isNotEmpty()) Text("Option: ${item.variant}", style = MaterialTheme.typography.labelSmall, color = extras.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriceLine(item.product.price, item.product.mrp, Modifier.weight(1f))
                QuantityStepper(item.quantity, onQuantity, max = Product.MAX_QUANTITY, min = 1)
            }
        }
    }
}

@Composable
fun TotalsCard(totals: CartTotals, modifier: Modifier = Modifier) {
    val extras = OneFeraTheme.extras
    GlassCard(modifier.fillMaxWidth()) {
        Text("Price details", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        TotalLine("Items", formatRupees(totals.subtotal))
        TotalLine("Delivery", if (totals.deliveryFee == 0) "FREE" else formatRupees(totals.deliveryFee), if (totals.deliveryFee == 0) StatusColors.Success else null)
        if (totals.deliveryFee > 0) {
            Text(
                "Add ${formatRupees(Product.FREE_DELIVERY_ABOVE - totals.subtotal)} more for free delivery",
                style = MaterialTheme.typography.labelSmall,
                color = extras.muted,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = extras.glassBorder)
        TotalLine("Total", formatRupees(totals.total), bold = true)
        if (totals.savings > 0) {
            Spacer(Modifier.size(6.dp))
            Text("You're saving ${formatRupees(totals.savings)} on this order 🎉", style = MaterialTheme.typography.labelLarge, color = StatusColors.Success)
        }
    }
}

@Composable
private fun TotalLine(label: String, value: String, color: Color? = null, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}
