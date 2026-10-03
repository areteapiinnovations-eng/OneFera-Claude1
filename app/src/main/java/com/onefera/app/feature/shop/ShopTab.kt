package com.onefera.app.feature.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.GradientTag
import com.onefera.app.core.designsystem.component.SelectChip
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.ProductCategory
import com.onefera.app.data.model.ProductFilter
import com.onefera.app.data.model.ProductSort
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopTab(onSearch: () -> Unit, onMessage: (String) -> Unit, viewModel: ShopViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    var showSort by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.messages.collect(onMessage) }

    val browsing = state.filter.category == null
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        fullWidth("header") {
            ShopHeader(cartCount = state.cartCount, onSearch = onSearch)
        }
        fullWidth("categories") {
            CategoryRow(selected = state.filter.category, onSelect = viewModel::onCategory)
        }
        if (browsing) {
            state.drop?.let { drop ->
                fullWidth("drop") { DropBanner(drop, onClick = { actions.openProduct(drop.id) }) }
            }
            if (state.trending.isNotEmpty()) {
                fullWidth("trending") {
                    ProductRail("Trending rn 🔥", state.trending, state.wishlist, viewModel::toggleWish)
                }
            }
            if (state.topPicks.isNotEmpty()) {
                fullWidth("picks") {
                    ProductRail("Top picks for you ✨", state.topPicks, state.wishlist, viewModel::toggleWish)
                }
            }
        }
        fullWidth("results-header") {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.filter.category?.let { "${it.emoji} ${it.label}" } ?: "Explore everything",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                ToolButton(R.drawable.ic_sort, state.filter.sort.label.substringBefore(':')) { showSort = true }
                Spacer(Modifier.width(8.dp))
                ToolButton(R.drawable.ic_tune, if (state.filter.activeCount > 0) "Filters · ${state.filter.activeCount}" else "Filters") { showFilters = true }
            }
        }
        if (!state.loading && state.results.isEmpty()) {
            fullWidth("empty") {
                val clearFilters: (@Composable () -> Unit)? = if (state.filter.activeCount > 0) {
                    { GlassButton("Clear filters", onClick = { viewModel.clearFilters() }, height = 44.dp) }
                } else {
                    null
                }
                EmptyState(
                    icon = R.drawable.ic_shop_filled,
                    title = "Nothing here yet",
                    message = "Try another category or loosen your filters.",
                    action = clearFilters,
                )
            }
        }
        items(state.results, key = { it.id }) { product ->
            ProductCard(
                product = product,
                wished = product.id in state.wishlist,
                onClick = { actions.openProduct(product.id) },
                onToggleWish = { viewModel.toggleWish(product) },
            )
        }
    }

    if (showSort) {
        ModalBottomSheet(
            onDismissRequest = { showSort = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Text("Sort by", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
                ProductSort.entries.forEach { sort ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(role = Role.RadioButton) { viewModel.onSort(sort); showSort = false }
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(sort.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (sort == state.filter.sort) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = "Selected", modifier = Modifier.size(20.dp).gradientTint(OneFeraTheme.extras.gradientBrush()))
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(
            onDismissRequest = { showFilters = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            FilterSheet(state.filter, onApply = { viewModel.onFilter(it); showFilters = false })
        }
    }
}

private fun LazyGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun ShopHeader(cartCount: Int, onSearch: () -> Unit) {
    val actions = LocalAppActions.current
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(50)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(44.dp)
                .clip(shape)
                .background(extras.glass)
                .border(1.dp, extras.glassBorder, shape)
                .clickable(role = Role.Button, onClick = onSearch)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = extras.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Search drops, brands…", style = MaterialTheme.typography.bodyMedium, color = extras.muted, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        com.onefera.app.core.designsystem.component.CircleIconButton(R.drawable.ic_heart, "Wishlist", onClick = actions.openWishlist, size = 38.dp)
        Spacer(Modifier.width(6.dp))
        com.onefera.app.core.designsystem.component.CircleIconButton(R.drawable.ic_package, "Your orders", onClick = actions.openOrders, size = 38.dp)
        Spacer(Modifier.width(6.dp))
        CartButton(cartCount, onClick = actions.openCart)
    }
}

@Composable
private fun CategoryRow(selected: ProductCategory?, onSelect: (ProductCategory?) -> Unit) {
    val extras = OneFeraTheme.extras
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(ProductCategory.entries) { category ->
            val isSelected = category == selected
            Column(
                Modifier
                    .width(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Tab) { onSelect(category) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .then(
                            if (isSelected) {
                                Modifier.background(extras.gradientBrush())
                            } else {
                                Modifier.background(extras.glass).border(1.dp, extras.glassBorder, CircleShape)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(category.emoji, style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    category.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else extras.muted,
                )
            }
        }
    }
}

@Composable
private fun DropBanner(product: Product, onClick: () -> Unit) {
    val extras = OneFeraTheme.extras
    var remaining by remember { mutableLongStateOf(ShopViewModel.millisUntilNextDrop()) }
    LaunchedEffect(Unit) {
        while (true) {
            remaining = ShopViewModel.millisUntilNextDrop()
            delay(1_000)
        }
    }
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(shape)
            .background(extras.gradientBrush())
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1.1f).fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "⚡ DROP OF THE DAY",
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.onGradient,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Text(product.title, style = MaterialTheme.typography.titleLarge, color = extras.onGradient, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${com.onefera.app.data.model.formatRupees(product.price)}  ·  ${product.discountPercent}% off",
                        style = MaterialTheme.typography.titleSmall,
                        color = extras.onGradient,
                    )
                }
                Text("Ends in ${countdown(remaining)}", style = MaterialTheme.typography.labelLarge, color = extras.onGradient)
            }
            Box(Modifier.weight(0.9f).fillMaxSize().padding(top = 14.dp, end = 14.dp, bottom = 14.dp)) {
                ProductImage(product.imageUrl, product.title, Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)), placeholder = product.brand)
            }
        }
    }
}

private fun countdown(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

@Composable
private fun ProductRail(title: String, products: List<Product>, wishlist: Set<String>, onToggleWish: (Product) -> Unit) {
    val actions = LocalAppActions.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(products, key = { it.id }) { product ->
                ProductCard(
                    product = product,
                    wished = product.id in wishlist,
                    onClick = { actions.openProduct(product.id) },
                    onToggleWish = { onToggleWish(product) },
                    modifier = Modifier.width(160.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolButton(icon: Int, text: String, onClick: () -> Unit) {
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(extras.glass)
            .border(1.dp, extras.glassBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

private val PRICE_CAPS = listOf<Int?>(null, 500, 1_000, 2_500, 10_000, 50_000)
private val RATINGS = listOf(0f, 3.5f, 4f, 4.5f)

@Composable
private fun FilterSheet(initial: ProductFilter, onApply: (ProductFilter) -> Unit) {
    var maxPrice by remember { mutableStateOf(initial.maxPrice) }
    var minRating by remember { mutableStateOf(initial.minRating) }
    var inStock by remember { mutableStateOf(initial.inStockOnly) }
    Column(
        Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            GradientTag("${listOf(maxPrice != null, minRating > 0f, inStock).count { it }} active")
        }
        Text("Price", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PRICE_CAPS.forEach { cap ->
                SelectChip(
                    text = cap?.let { "Under ${com.onefera.app.data.model.formatRupees(it)}" } ?: "Any price",
                    selected = maxPrice == cap,
                    onClick = { maxPrice = cap },
                )
            }
        }
        Text("Rating", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RATINGS.forEach { r ->
                SelectChip(text = if (r == 0f) "Any" else "$r★ & up", selected = minRating == r, onClick = { minRating = r })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("In stock only", style = MaterialTheme.typography.titleSmall)
                Text("Hide sold-out items", style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
            }
            Switch(checked = inStock, onCheckedChange = { inStock = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("Reset", onClick = { maxPrice = null; minRating = 0f; inStock = false }, modifier = Modifier.weight(1f))
            GradientButton(
                "Show results",
                onClick = { onApply(ProductFilter(maxPrice = maxPrice, minRating = minRating, inStockOnly = inStock)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
