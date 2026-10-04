package com.onefera.app.feature.seller

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.model.Order
import com.onefera.app.data.model.formatRupees
import com.onefera.app.data.seller.SellerRepository
import com.onefera.app.data.seller.nextForSeller
import com.onefera.app.feature.shop.ProductImage
import com.onefera.app.feature.shop.StatusBadge
import com.onefera.app.feature.shop.Timeline
import com.onefera.app.feature.shop.formatDate
import com.onefera.app.navigation.SellerOrderRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SellerOrderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    auth: AuthRepository,
    private val seller: SellerRepository,
) : ViewModel() {
    private val orderId = savedStateHandle.toRoute<SellerOrderRoute>().orderId
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<Pair<String, Order?>> = combine(auth.uidFlow(), seller.sellerOrder(orderId)) { uid, order -> uid.orEmpty() to order }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "" to null)

    var busy by mutableStateOf(false)
        private set

    fun advance() {
        if (busy) return
        val next = state.value.second?.status?.nextForSeller() ?: return
        busy = true
        viewModelScope.launch {
            seller.advanceOrder(orderId)
                .onSuccess { _messages.send("Marked as ${next.label.lowercase()} ✅ The buyer has been notified.") }
                .onFailure { _messages.send(it.message ?: "Couldn't update this order.") }
            busy = false
        }
    }
}

@Composable
fun SellerOrderScreen(onBack: () -> Unit, viewModel: SellerOrderViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val (sellerId, order) = state
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val extras = OneFeraTheme.extras

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.4f) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { ScreenHeader("Order to fulfil", onBack = onBack) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                val next = order?.status?.nextForSeller()
                if (next != null) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        GradientButton("Mark as ${next.label.lowercase()}", onClick = viewModel::advance, loading = viewModel.busy)
                    }
                }
            },
        ) { padding ->
            order?.let { o ->
                val mine = o.items.filter { it.product.sellerId == sellerId }
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Order ${o.id}", style = MaterialTheme.typography.titleMedium)
                                Text("Placed ${formatDate(o.createdAt)}", style = MaterialTheme.typography.labelSmall, color = extras.muted)
                            }
                            StatusBadge(o.status)
                        }
                        Spacer(Modifier.height(14.dp))
                        Timeline(o)
                    }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Your items", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        mine.forEach { item ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                ProductImage(item.product.imageUrl, null, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)), placeholder = item.product.title)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.product.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull("Qty ${item.quantity}", item.variant.ifEmpty { null }).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = extras.muted,
                                    )
                                }
                                Text(formatRupees(item.price * item.quantity), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Text("You earn", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(formatRupees(mine.sumOf { it.price * it.quantity }), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Ship to", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("${o.address.name} · ${o.address.phone}", style = MaterialTheme.typography.bodyMedium)
                        Text(o.address.oneLine(), style = MaterialTheme.typography.bodySmall, color = extras.muted)
                        Spacer(Modifier.height(8.dp))
                        Text("Payment: ${o.paymentMethod.label}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
