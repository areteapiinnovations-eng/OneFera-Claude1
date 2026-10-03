package com.onefera.app.feature.main

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientTag
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.model.AuraGrade
import com.onefera.app.data.model.UserProfile

/** Streak tiers from the product spec: 3-day warm-up, 7-day weekly regular, 30-day flame tier. */
private val streakTiers = listOf(3, 7, 30)

private data class Drop(val emoji: String, val title: String, val body: String, val tab: MainTab)

private val drops = listOf(
    Drop("📸", "Feed & stories", "Post moments, tag products, react and duet.", MainTab.Home),
    Drop("🎬", "Reels", "Full-screen vertical video with tap-to-buy tags.", MainTab.Reels),
    Drop("🛍️", "Shop & cart", "Categories, drops of the day, wishlist and checkout.", MainTab.Shop),
    Drop("💬", "Chats", "DMs with replies, attachments and quick vibes.", MainTab.Chats),
    Drop("📍", "Near you", "Creators and sellers around you.", MainTab.Near),
)

@Composable
fun HomeTab(profile: UserProfile?, isDemoMode: Boolean, onOpenTab: (MainTab) -> Unit) {
    val extras = OneFeraTheme.extras
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            val firstName = profile?.displayName?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: "there"
            Column {
                Text("Hey $firstName 👋", style = MaterialTheme.typography.headlineLarge)
                Text("Your future era starts here. Keep the streak alive ✦", style = MaterialTheme.typography.bodyLarge, color = extras.muted)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StreakCard(days = profile?.streakDays ?: 0, modifier = Modifier.weight(1f))
                AuraCard(points = profile?.auraPoints ?: 0, modifier = Modifier.weight(1f), onClick = { onOpenTab(MainTab.You) })
            }
        }
        if (isDemoMode) {
            item {
                GlassCard(contentPadding = 14.dp) {
                    Text("🧪 Demo mode", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Running without Firebase. Add google-services.json to go live; see README.",
                        style = MaterialTheme.typography.bodySmall,
                        color = extras.muted,
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dropping soon", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                GradientTag("NEXT UP")
            }
        }
        items(drops, key = { it.title }) { drop ->
            GlassCard(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { onOpenTab(drop.tab) },
                contentPadding = 14.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) { Text(drop.emoji, style = MaterialTheme.typography.titleLarge) }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(drop.title, style = MaterialTheme.typography.titleMedium)
                        Text(drop.body, style = MaterialTheme.typography.bodySmall, color = extras.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = extras.muted)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun StreakCard(days: Int, modifier: Modifier = Modifier) {
    val extras = OneFeraTheme.extras
    val nextTier = streakTiers.firstOrNull { it > days } ?: streakTiers.last()
    GlassCard(modifier = modifier, contentPadding = 14.dp) {
        Text("DAILY STREAK", style = MaterialTheme.typography.labelSmall, color = extras.muted)
        Spacer(Modifier.height(6.dp))
        Text("🔥 $days ${if (days == 1) "day" else "days"}", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        ProgressBar(fraction = (days.toFloat() / nextTier).coerceIn(0f, 1f))
        Spacer(Modifier.height(6.dp))
        Text(
            if (days >= streakTiers.last()) "Flame tier unlocked" else "${nextTier - days} more to the $nextTier-day tier",
            style = MaterialTheme.typography.labelMedium,
            color = extras.muted,
        )
    }
}

@Composable
private fun AuraCard(points: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val extras = OneFeraTheme.extras
    val grade = AuraGrade.forPoints(points)
    GlassCard(modifier = modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick), contentPadding = 14.dp) {
        Text("AURA SCORE", style = MaterialTheme.typography.labelSmall, color = extras.muted)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$points", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.size(6.dp))
            Text(
                "· ${grade.label}",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.gradientTint(extras.horizontalGradient()),
            )
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(fraction = points.toFloat() / AuraGrade.MAX_POINTS)
        Spacer(Modifier.height(6.dp))
        Text(grade.description, style = MaterialTheme.typography.labelMedium, color = extras.muted)
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val extras = OneFeraTheme.extras
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(extras.glassBorder)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(CircleShape)
                .background(extras.horizontalGradient()),
        )
    }
}
