package com.onefera.app.feature.near

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.GradientTag
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.chat.ChatRepository
import com.onefera.app.data.model.MembershipPlan
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.model.toSummary
import com.onefera.app.data.shop.ShopRepository
import com.onefera.app.data.user.UserRepository
import com.onefera.app.feature.shop.ProductCard
import com.onefera.app.navigation.StoreRoute
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

data class StoreUiState(
    val loading: Boolean = true,
    val seller: UserProfile? = null,
    val products: List<Product> = emptyList(),
    val wishlist: Set<String> = emptySet(),
)

@HiltViewModel
class StoreViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    users: UserRepository,
    private val shop: ShopRepository,
    private val chat: ChatRepository,
) : ViewModel() {
    val sellerId = savedStateHandle.toRoute<StoreRoute>().sellerId
    private val _events = Channel<String>(Channel.BUFFERED)
    /** A conversation id to open, or a message starting with "!" to show. */
    val events: Flow<String> = _events.receiveAsFlow()

    val state: StateFlow<StoreUiState> = combine(users.observeProfile(sellerId), shop.catalogue(), shop.wishlistIds()) { seller, all, wished ->
        StoreUiState(false, seller, all.filter { it.sellerId == sellerId }.sortedByDescending { it.soldCount }, wished)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoreUiState())

    fun toggleWish(product: Product) = viewModelScope.launch {
        shop.setWishlisted(product, product.id !in state.value.wishlist)
    }

    fun message() = viewModelScope.launch {
        val seller = state.value.seller ?: return@launch
        chat.openConversation(seller.toSummary())
            .onSuccess { _events.send(it) }
            .onFailure { _events.send("!" + (it.message ?: "Couldn't open the chat.")) }
    }
}

@Composable
fun StoreScreen(onBack: () -> Unit, viewModel: StoreViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            if (e.startsWith("!")) Toast.makeText(context, e.drop(1), Toast.LENGTH_SHORT).show() else actions.openChat(e)
        }
    }
    val seller = state.seller
    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.45f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(seller?.displayName?.let { "$it's store" } ?: "Store", onBack = onBack)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (seller != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(seller.avatarUrl, seller.displayName, size = 64.dp, ring = true)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(seller.displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false))
                                        if (seller.membership.active() == MembershipPlan.SellerPro) {
                                            Spacer(Modifier.width(6.dp))
                                            GradientTag("PRO")
                                        }
                                    }
                                    Text("@${seller.username}" + if (seller.city.isNotBlank()) " · ${seller.city}" else "", style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
                                    Text("${state.products.size} items · ⚡ ${seller.auraPoints}", style = MaterialTheme.typography.labelMedium, color = OneFeraTheme.extras.muted)
                                }
                            }
                            if (seller.bio.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(seller.bio, style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                GradientButton("Message", onClick = { viewModel.message() }, modifier = Modifier.weight(1f))
                                GlassButton("Profile", onClick = { actions.openUser(seller.uid) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (!state.loading && state.products.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(R.drawable.ic_storefront, "Nothing listed yet", "Check back soon, new drops land here first.", Modifier.fillMaxWidth())
                    }
                }
                items(state.products, key = { it.id }) { p ->
                    ProductCard(p, wished = p.id in state.wishlist, onClick = { actions.openProduct(p.id) }, onToggleWish = { viewModel.toggleWish(p) })
                }
            }
        }
    }
}
