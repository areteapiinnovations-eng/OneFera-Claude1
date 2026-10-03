package com.onefera.app.feature.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.data.model.Product
import com.onefera.app.data.model.ProductSummary
import com.onefera.app.data.model.formatRupees

/** Product photo on a soft card; shows the brand initial while loading or if the image fails. */
@Composable
fun ProductImage(url: String?, contentDescription: String?, modifier: Modifier = Modifier, placeholder: String = "") {
    val extras = OneFeraTheme.extras
    var failed by remember(url) { mutableStateOf(url == null) }
    Box(
        modifier.background(
            Brush.linearGradient(extras.gradient.map { it.copy(alpha = if (extras.isDark) 0.22f else 0.14f) }),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (failed || url == null) {
            Text(placeholder.take(1).uppercase().ifEmpty { "✦" }, style = MaterialTheme.typography.headlineLarge, color = extras.muted)
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
                onSuccess = { failed = false },
            )
        }
    }
}

@Composable
fun PriceLine(price: Int, mrp: Int, modifier: Modifier = Modifier, large: Boolean = false) {
    val extras = OneFeraTheme.extras
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            formatRupees(price),
            style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall,
        )
        if (mrp > price) {
            Text(
                formatRupees(mrp),
                style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                color = extras.muted,
            )
            val off = ((mrp - price) * 100f / mrp).toInt()
            if (off > 0) Text("$off% off", style = MaterialTheme.typography.labelMedium, color = StatusColors.Success)
        }
    }
}

@Composable
fun RatingBadge(rating: Float, count: Int? = null, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(StatusColors.Success.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(String.format(java.util.Locale.US, "%.1f", rating), style = MaterialTheme.typography.labelMedium)
        Icon(painterResource(R.drawable.ic_star_filled), contentDescription = "stars", tint = StatusColors.Success, modifier = Modifier.size(12.dp))
        if (count != null) Text("(${com.onefera.app.core.common.compactCount(count)})", style = MaterialTheme.typography.labelSmall, color = OneFeraTheme.extras.muted)
    }
}

@Composable
fun WishButton(wished: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val extras = OneFeraTheme.extras
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(role = Role.Checkbox, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (wished) {
            Icon(
                painterResource(R.drawable.ic_heart_filled),
                contentDescription = "Remove from wishlist",
                modifier = Modifier.size(size * 0.55f).gradientTint(extras.gradientBrush()),
            )
        } else {
            Icon(painterResource(R.drawable.ic_heart), contentDescription = "Add to wishlist", tint = Color.White, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/** Grid card used in the shop, search results and wishlist. */
@Composable
fun ProductCard(
    product: Product,
    wished: Boolean,
    onClick: () -> Unit,
    onToggleWish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (extras.isDark) extras.glass else MaterialTheme.colorScheme.surface)
            .border(1.dp, extras.glassBorder, shape)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            ProductImage(product.imageUrl, product.title, Modifier.fillMaxSize(), placeholder = product.brand)
            WishButton(wished, onToggleWish, Modifier.align(Alignment.TopEnd).padding(8.dp))
            when {
                !product.inStock -> CornerLabel("Sold out", StatusColors.Error, Modifier.align(Alignment.TopStart))
                product.isDrop -> CornerLabel("DROP", null, Modifier.align(Alignment.TopStart))
                product.stock in 1..5 -> CornerLabel("Only ${product.stock} left", StatusColors.Warning, Modifier.align(Alignment.TopStart))
            }
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(product.brand, style = MaterialTheme.typography.labelSmall, color = extras.muted, maxLines = 1)
            Text(product.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
            PriceLine(product.price, product.mrp)
            if (product.ratingCount > 0) RatingBadge(product.rating, product.ratingCount)
        }
    }
}

@Composable
private fun CornerLabel(text: String, color: Color?, modifier: Modifier) {
    val extras = OneFeraTheme.extras
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (color == null) extras.onGradient else Color.White,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (color == null) Modifier.background(extras.horizontalGradient()) else Modifier.background(color))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** − 2 + quantity control. */
@Composable
fun QuantityStepper(quantity: Int, onChange: (Int) -> Unit, max: Int, modifier: Modifier = Modifier, min: Int = 1) {
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(extras.glass).border(1.dp, extras.glassBorder, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton(R.drawable.ic_remove, "Decrease quantity", enabled = quantity > min) { onChange(quantity - 1) }
        Text("$quantity", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 6.dp))
        StepperButton(R.drawable.ic_add, "Increase quantity", enabled = quantity < max) { onChange(quantity + 1) }
    }
}

@Composable
private fun StepperButton(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = description,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else OneFeraTheme.extras.muted.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** "Tap to buy" chip shown on posts and reels that tag products. */
@Composable
fun ProductTagChip(product: ProductSummary, onClick: () -> Unit, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (onDark) Color.Black.copy(alpha = 0.45f) else extras.glass)
            .border(1.dp, if (onDark) Color.White.copy(alpha = 0.25f) else extras.glassBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductImage(product.imageUrl, null, Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)), placeholder = product.brand)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                product.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (onDark) Color.White else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                formatRupees(product.price),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = if (onDark) Color.White.copy(alpha = 0.8f) else extras.muted,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(extras.horizontalGradient()).padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(painterResource(R.drawable.ic_cart), contentDescription = null, tint = extras.onGradient, modifier = Modifier.size(14.dp))
            Text("Buy", style = MaterialTheme.typography.labelMedium, color = extras.onGradient)
        }
    }
}

/** Cart icon with a count bubble. */
@Composable
fun CartButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        com.onefera.app.core.designsystem.component.CircleIconButton(
            icon = if (count > 0) R.drawable.ic_cart_filled else R.drawable.ic_cart,
            contentDescription = if (count > 0) "Cart, $count items" else "Cart",
            onClick = onClick,
            size = 38.dp,
        )
        if (count > 0) {
            Text(
                if (count > 9) "9+" else "$count",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(StatusColors.Live)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}
