package com.onefera.app.feature.near

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.GradientTag
import com.onefera.app.core.designsystem.component.SelectChip
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.component.rememberReducedMotion
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.Geo
import com.onefera.app.data.model.NearRadius
import com.onefera.app.data.model.NearbyPerson
import com.onefera.app.data.model.NearbyStore
import com.onefera.app.feature.shop.ProductImage
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NearTab(onMessage: (String) -> Unit, viewModel: NearViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> viewModel.onPermissionResult(granted) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { onMessage(it) } }
    LaunchedEffect(Unit) { viewModel.start() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(NearMode.entries) { m -> SelectChip(m.label, selected = m == state.mode, onClick = { viewModel.onMode(m) }) }
                items(NearRadius.entries) { r -> SelectChip(r.label, selected = r == state.radius, onClick = { viewModel.onRadius(r) }) }
            }
        }
        if (!state.hasPermission) {
            item {
                EmptyState(
                    icon = R.drawable.ic_near_filled,
                    title = "See what's around you 📍",
                    message = "OneFera uses your approximate location (about 1 km) to show nearby creators and stores. Your exact spot is never stored or shown.",
                    modifier = Modifier.fillMaxWidth(),
                    action = {
                        GradientButton(
                            "Allow approximate location",
                            onClick = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                            modifier = Modifier.width(280.dp),
                        )
                    },
                )
            }
            return@LazyColumn
        }
        if (state.usingDemoLocation) {
            item { Text("Location unavailable, showing a demo spot in Mumbai.", style = MaterialTheme.typography.labelMedium, color = StatusColors.Warning) }
        }
        item {
            Radar(
                people = if (state.mode == NearMode.People) state.people else emptyList(),
                stores = if (state.mode == NearMode.Stores) state.stores else emptyList(),
                radiusKm = state.radius.km,
                scanning = state.loading,
            )
        }
        item { PrivacyCard(state, onVisible = viewModel::setVisible, onStore = viewModel::setStoreShared) }
        when (state.mode) {
            NearMode.People -> {
                if (state.isMinor) {
                    item {
                        EmptyState(
                            icon = R.drawable.ic_shield,
                            title = "Nearby people is 18+",
                            message = "To keep everyone safe, Near only shows stores for members under 18.",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (!state.loading && state.people.isEmpty()) {
                    item { EmptyState(R.drawable.ic_near, "No one nearby right now", "Try a wider radius, or check back later.", Modifier.fillMaxWidth()) }
                } else {
                    items(state.people, key = { "p-${it.user.uid}" }) { person -> PersonRow(person) }
                }
            }
            NearMode.Stores -> {
                if (!state.loading && state.stores.isEmpty()) {
                    item { EmptyState(R.drawable.ic_storefront, "No stores nearby yet", "Try a wider radius. Sellers can list their store from here too.", Modifier.fillMaxWidth()) }
                } else {
                    items(state.stores, key = { "s-${it.seller.uid}" }) { store -> StoreRow(store) }
                }
            }
        }
    }
}

@Composable
private fun Radar(people: List<NearbyPerson>, stores: List<NearbyStore>, radiusKm: Int, scanning: Boolean) {
    val extras = OneFeraTheme.extras
    val reduced = rememberReducedMotion()
    val sweep by rememberInfiniteTransition(label = "radar").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (scanning) 1_400 else 4_000, easing = LinearEasing)),
        label = "sweep",
    )
    val gradient = extras.gradient
    val ring = extras.glassBorder
    val dots = people.map { Triple(it.user, it.distanceKm, it.bearing) } + stores.map { Triple(it.seller, it.distanceKm, it.bearing) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(extras.glass)
            .semantics { contentDescription = "Radar: ${dots.size} nearby within $radiusKm km" },
    ) {
        val size = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            val c = center
            for (i in 1..3) drawCircle(ring, radius = r * i / 3f, center = c, style = Stroke(width = 2f))
            if (!reduced) {
                rotate(sweep, c) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(gradient.first().copy(alpha = 0f), gradient.last().copy(alpha = 0.45f)), c),
                        startAngle = -60f,
                        sweepAngle = 60f,
                        useCenter = true,
                    )
                }
            }
            drawCircle(Brush.linearGradient(gradient), radius = 10f, center = c)
            drawCircle(ring, radius = 18f, center = c, style = Stroke(width = 3f), alpha = 0.6f)
            // Keep the centre marker visually distinct from the avatars.
            drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 1f)
            drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 1f)
        }
        val avatar = 40.dp
        dots.take(10).forEach { (user, km, bearing) ->
            val fraction = (km / radiusKm).coerceIn(0.12, 0.92).toFloat()
            val angle = Math.toRadians(bearing - 90)
            val x = (size / 2) + (size / 2) * fraction * cos(angle).toFloat() - avatar / 2
            val y = (size / 2) + (size / 2) * fraction * sin(angle).toFloat() - avatar / 2
            Avatar(user.avatarUrl, user.displayName, size = avatar, ring = true, modifier = Modifier.offset(x, y))
        }
        Text(
            "$radiusKm km",
            style = MaterialTheme.typography.labelSmall,
            color = extras.muted,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun PrivacyCard(state: NearUiState, onVisible: (Boolean) -> Unit, onStore: (Boolean) -> Unit) {
    val extras = OneFeraTheme.extras
    GlassCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        if (!state.isMinor) {
            ToggleRow(
                title = "Show me on Near",
                subtitle = if (state.settings.visible) "Visible to people nearby (approx. 1 km)" else "Hidden. You can still look around",
                checked = state.settings.visible,
                onChange = onVisible,
            )
        }
        if (state.isSeller) {
            if (!state.isMinor) Spacer(Modifier.height(10.dp))
            ToggleRow(
                title = "List my store on Near",
                subtitle = "Shoppers nearby can discover your listings",
                checked = state.settings.shareStore,
                onChange = onStore,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_shield), contentDescription = null, tint = extras.muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Approximate distance only. Private accounts and under-18s are never shown.", style = MaterialTheme.typography.labelSmall, color = extras.muted)
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PersonRow(person: NearbyPerson) {
    val extras = OneFeraTheme.extras
    val actions = LocalAppActions.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(extras.glass)
            .border(1.dp, extras.glassBorder, shape)
            .clickable { actions.openUser(person.user.uid) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(person.user.avatarUrl, person.user.displayName, size = 48.dp, ring = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(person.user.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(Geo.label(person.distanceKm), person.vibe.ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = extras.muted,
                maxLines = 1,
            )
        }
        GradientTag("⚡ ${person.auraPoints}")
    }
}

@Composable
private fun StoreRow(store: NearbyStore) {
    val extras = OneFeraTheme.extras
    val actions = LocalAppActions.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(extras.glass)
            .border(1.dp, extras.glassBorder, shape)
            .clickable { actions.openStore(store.seller.uid) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ProductImage(store.coverUrl, null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)), placeholder = store.seller.displayName)
            Avatar(store.seller.avatarUrl, store.seller.displayName, size = 24.dp, modifier = Modifier.align(Alignment.BottomEnd).offset(6.dp, 6.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(store.seller.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Geo.label(store.distanceKm)} · ${store.listings} ${if (store.listings == 1) "item" else "items"}",
                style = MaterialTheme.typography.labelSmall,
                color = extras.muted,
            )
        }
        Icon(painterResource(R.drawable.ic_storefront), contentDescription = null, modifier = Modifier.size(22.dp).gradientTint(extras.gradientBrush()))
    }
}
