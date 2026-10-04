package com.onefera.app.feature.shop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.ProductSummary
import com.onefera.app.data.model.formatRupees

/** "Tag products" row on the Create screen. */
@Composable
fun TagProductsCard(products: List<ProductSummary>, onAdd: () -> Unit, onRemove: (ProductSummary) -> Unit) {
    val extras = OneFeraTheme.extras
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onAdd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_sell), contentDescription = null, modifier = Modifier.size(22.dp).gradientTint(extras.gradientBrush()))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Tag products", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (products.isEmpty()) "Let people shop what's in your post" else "${products.size} tagged · tap to edit",
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.muted,
                )
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = extras.muted, modifier = Modifier.size(20.dp))
        }
        products.forEach { product ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductImage(product.imageUrl, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), placeholder = product.brand)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(product.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatRupees(product.price), style = MaterialTheme.typography.labelSmall, color = extras.muted)
                }
                IconButton(onClick = { onRemove(product) }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove ${product.title}", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductPickerSheet(
    catalogue: List<Product>,
    selected: List<ProductSummary>,
    max: Int,
    onDone: (List<ProductSummary>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(selected) }
    val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    val shown = catalogue.filter { p ->
        terms.isEmpty() || terms.all { t -> p.title.lowercase().contains(t) || p.brand.lowercase().contains(t) || p.category.label.lowercase().contains(t) }
    }.take(60)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tag products", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Text("${picked.size}/$max", style = MaterialTheme.typography.labelLarge, color = OneFeraTheme.extras.muted)
            }
            Spacer(Modifier.height(8.dp))
            OneFeraTextField(
                value = query,
                onValueChange = { query = it.take(40) },
                label = "Search products or brands",
                leadingIcon = R.drawable.ic_search,
                imeAction = ImeAction.Search,
            )
            LazyColumn(Modifier.fillMaxWidth().height(380.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(shown, key = { it.id }) { product ->
                    val isPicked = picked.any { it.id == product.id }
                    val canPick = isPicked || picked.size < max
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = canPick) {
                                picked = if (isPicked) picked.filterNot { it.id == product.id } else picked + product.toSummary()
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProductImage(product.imageUrl, null, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), placeholder = product.brand)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(product.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${product.brand} · ${formatRupees(product.price)}", style = MaterialTheme.typography.labelSmall, color = OneFeraTheme.extras.muted)
                        }
                        Checkbox(checked = isPicked, onCheckedChange = null, enabled = canPick)
                    }
                }
            }
            GradientButton(
                if (picked.isEmpty()) "Done" else "Tag ${picked.size} ${if (picked.size == 1) "product" else "products"}",
                onClick = { onDone(picked) },
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}
