package com.onefera.app.feature.shop

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.onefera.app.R
import com.onefera.app.core.common.AppLinks
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.CircleIconButton
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.SelectChip
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.formatRupees
import com.onefera.app.data.shop.ShopRepository
import com.onefera.app.navigation.ProductRoute
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

data class ProductUiState(
    val loading: Boolean = true,
    val product: Product? = null,
    val wished: Boolean = false,
    val cartCount: Int = 0,
    val similar: List<Product> = emptyList(),
    val wishlist: Set<String> = emptySet(),
)

@HiltViewModel
class ProductViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val shop: ShopRepository,
) : ViewModel() {
    private val productId = savedStateHandle.toRoute<ProductRoute>().productId
    private val _events = Channel<ProductEvent>(Channel.BUFFERED)
    val events: Flow<ProductEvent> = _events.receiveAsFlow()

    val state: StateFlow<ProductUiState> = combine(shop.catalogue(), shop.product(productId), shop.wishlistIds(), shop.cart()) { all, product, wished, cart ->
        ProductUiState(
            loading = false,
            product = product,
            wished = productId in wished,
            cartCount = cart.sumOf { it.quantity },
            similar = product?.let { p -> all.filter { it.category == p.category && it.id != p.id && it.inStock }.take(8) }.orEmpty(),
            wishlist = wished,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProductUiState())

    var adding by mutableStateOf(false)
        private set

    fun toggleWish(target: Product? = state.value.product) {
        val product = target ?: return
        val wished = product.id !in state.value.wishlist
        viewModelScope.launch {
            shop.setWishlisted(product, wished)
                .onSuccess { if (wished) _events.send(ProductEvent.Message("Saved to your wishlist 💜")) }
                .onFailure { _events.send(ProductEvent.Message(it.message ?: "Try again.")) }
        }
    }

    fun addToCart(variant: String, quantity: Int, buyNow: Boolean) {
        val product = state.value.product ?: return
        if (adding) return
        adding = true
        viewModelScope.launch {
            shop.addToCart(product, variant, quantity)
                .onSuccess { _events.send(if (buyNow) ProductEvent.GoToCart else ProductEvent.Message("Added to cart 🛒")) }
                .onFailure { _events.send(ProductEvent.Message(it.message ?: "Couldn't add to cart.")) }
            adding = false
        }
    }
}

sealed interface ProductEvent {
    data class Message(val text: String) : ProductEvent
    data object GoToCart : ProductEvent
}

@Composable
fun ProductScreen(onBack: () -> Unit, viewModel: ProductViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var variant by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableIntStateOf(1) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ProductEvent.Message -> {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(event.text)
                }
                ProductEvent.GoToCart -> actions.openCart()
            }
        }
    }

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.4f) {
        val product = state.product
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (product != null) {
                    BuyBar(
                        product = product,
                        busy = viewModel.adding,
                        needsVariant = product.variants.isNotEmpty() && variant.isEmpty(),
                        onAdd = { viewModel.addToCart(variant, quantity, buyNow = false) },
                        onBuy = { viewModel.addToCart(variant, quantity, buyNow = true) },
                    )
                }
            },
        ) { padding ->
            when {
                product != null -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Gallery(product, onBack = onBack, wished = state.wished, onWish = { viewModel.toggleWish() }, cartCount = state.cartCount, onShare = { shareProduct(context, product) })
                    Details(
                        product = product,
                        variant = variant,
                        onVariant = { variant = it },
                        quantity = quantity,
                        onQuantity = { quantity = it },
                    )
                    if (state.similar.isNotEmpty()) {
                        Text("More like this", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 10.dp))
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.similar.size) { i ->
                                val p = state.similar[i]
                                ProductCard(p, wished = p.id in state.wishlist, onClick = { actions.openProduct(p.id) }, onToggleWish = { viewModel.toggleWish(p) }, modifier = Modifier.width(150.dp))
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
                state.loading -> Box(Modifier.fillMaxSize())
                else -> Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().padding(16.dp)) {
                    CircleIconButton(R.drawable.ic_arrow_back, "Back", onClick = onBack)
                    EmptyState(R.drawable.ic_shop_filled, "This product is gone", "It may have been removed by the seller.", Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Gallery(product: Product, onBack: () -> Unit, wished: Boolean, onWish: () -> Unit, cartCount: Int, onShare: () -> Unit) {
    val actions = LocalAppActions.current
    val images = product.images.ifEmpty { listOf("") }
    val pager = rememberPagerState { images.size }
    Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            ProductImage(images[page].ifEmpty { null }, product.title, Modifier.fillMaxSize(), placeholder = product.brand)
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(R.drawable.ic_arrow_back, "Back", onClick = onBack)
            Spacer(Modifier.weight(1f))
            CircleIconButton(R.drawable.ic_share, "Share", onClick = onShare, size = 38.dp)
            Spacer(Modifier.width(6.dp))
            CartButton(cartCount, onClick = actions.openCart)
        }
        WishButton(wished, onWish, Modifier.align(Alignment.BottomEnd).padding(16.dp), size = 44.dp)
        if (images.size > 1) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(images.size) { i ->
                    Box(
                        Modifier
                            .size(if (i == pager.currentPage) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (i == pager.currentPage) 1f else 0.5f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun Details(product: Product, variant: String, onVariant: (String) -> Unit, quantity: Int, onQuantity: (Int) -> Unit) {
    val extras = OneFeraTheme.extras
    val actions = LocalAppActions.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(product.brand.uppercase(), style = MaterialTheme.typography.labelMedium, color = extras.muted)
            Text(product.title, style = MaterialTheme.typography.headlineSmall)
            if (product.ratingCount > 0) RatingBadge(product.rating, product.ratingCount)
        }
        PriceLine(product.price, product.mrp, large = true)
        Text(
            when {
                !product.inStock -> "Sold out · check back soon"
                product.stock <= 5 -> "Hurry, only ${product.stock} left!"
                else -> "In stock"
            },
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !product.inStock -> StatusColors.Error
                product.stock <= 5 -> StatusColors.Warning
                else -> StatusColors.Success
            },
        )
        if (product.variants.isNotEmpty()) {
            Text("Choose an option", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                product.variants.forEach { v -> SelectChip(v, selected = v == variant, onClick = { onVariant(v) }) }
            }
        }
        if (product.inStock) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quantity", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                QuantityStepper(quantity, onQuantity, max = minOf(Product.MAX_QUANTITY, product.stock))
            }
        }
        GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
            InfoRow(R.drawable.ic_shipping, if (product.freeDelivery) "Free delivery" else "Delivery ${formatRupees(Product.DELIVERY_FEE)} · free above ${formatRupees(Product.FREE_DELIVERY_ABOVE)}", "Usually arrives in 3–5 days")
            Spacer(Modifier.height(10.dp))
            InfoRow(R.drawable.ic_shield, "Secure payments", "UPI, cards, net banking or cash on delivery")
        }
        if (product.highlights.isNotEmpty()) {
            Text("Highlights", style = MaterialTheme.typography.titleMedium)
            product.highlights.forEach { h ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(painterResource(R.drawable.ic_sparkle_filled), contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(16.dp).gradientTint(extras.gradientBrush()))
                    Spacer(Modifier.width(8.dp))
                    Text(h, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (product.description.isNotBlank()) {
            Text("About this item", style = MaterialTheme.typography.titleMedium)
            Text(product.description, style = MaterialTheme.typography.bodyMedium, color = extras.muted)
        }
        if (product.sellerId.isNotEmpty()) {
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(extras.glass)
                    .border(1.dp, extras.glassBorder, shape)
                    .clickable(role = Role.Button) { actions.openUser(product.sellerId) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(product.seller.avatarUrl, product.seller.displayName.ifEmpty { product.brand }, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sold by", style = MaterialTheme.typography.labelSmall, color = extras.muted)
                    Text(product.seller.displayName.ifEmpty { product.brand }, style = MaterialTheme.typography.titleSmall)
                }
                Icon(painterResource(R.drawable.ic_storefront), contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(icon: Int, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp).gradientTint(OneFeraTheme.extras.gradientBrush()))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
        }
    }
}

@Composable
private fun BuyBar(product: Product, busy: Boolean, needsVariant: Boolean, onAdd: () -> Unit, onBuy: () -> Unit) {
    val extras = OneFeraTheme.extras
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)).navigationBarsPadding().padding(12.dp)) {
        if (needsVariant && product.inStock) {
            Text("Pick an option above to continue", style = MaterialTheme.typography.labelMedium, color = extras.muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("Add to cart", onClick = onAdd, enabled = product.inStock && !needsVariant && !busy, leadingIcon = R.drawable.ic_cart, modifier = Modifier.weight(1f))
            GradientButton(
                if (product.inStock) "Buy now" else "Sold out",
                onClick = onBuy,
                enabled = product.inStock && !needsVariant,
                loading = busy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun shareProduct(context: Context, product: Product) {
    val text = "${product.title} for ${formatRupees(product.price)} on OneFera 🛍️\n${AppLinks.product(product.id)}"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share product"))
}
