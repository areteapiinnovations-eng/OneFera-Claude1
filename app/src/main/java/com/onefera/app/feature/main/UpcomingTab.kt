package com.onefera.app.feature.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientTag
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme

private data class Upcoming(val title: String, val message: String, val features: List<String>)

private fun upcomingFor(tab: MainTab): Upcoming = when (tab) {
    MainTab.Shop -> Upcoming(
        "The shop is stocking up 🛍️",
        "Drops, deals and creator stores, all one tap from your feed.",
        listOf("Categories & Drop of the Day", "Search, filters and sort", "Product pages, wishlist & cart", "Secure checkout & order tracking"),
    )
    MainTab.Search -> Upcoming(
        "Find anything, anyone 🔎",
        "Search people, products and tags in one place.",
        listOf("Top, Accounts, Shop and Tags tabs", "Recent searches", "Trending hashtags"),
    )
    MainTab.Chats -> Upcoming(
        "DMs are loading 💬",
        "Real-time chats with your circle and your favourite sellers.",
        listOf("Reply to a specific message", "Photo & file attachments", "Quick-vibe replies", "Online and typing status"),
    )
    MainTab.Near -> Upcoming(
        "What's around you 📍",
        "Discover creators and sellers nearby, only if you choose to share your location.",
        listOf("Nearby people", "Nearby stores & pickups", "Distance filters, privacy first"),
    )
    MainTab.Reels -> Upcoming(
        "Reels are rolling in 🎬",
        "Full-screen vertical video with sounds, reactions and tap-to-buy.",
        listOf("Swipe-up vertical player", "Trending sounds", "Duets & reactions", "Tap-to-buy product tags"),
    )
    else -> Upcoming("Coming soon ✨", "This space is getting built right now.", emptyList())
}

@Composable
fun UpcomingTab(tab: MainTab) {
    val content = upcomingFor(tab)
    val extras = OneFeraTheme.extras
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.size(24.dp))
        EmptyState(icon = tab.selectedIcon, title = content.title, message = content.message)
        if (content.features.isNotEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("On the way", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    GradientTag("SOON")
                }
                Spacer(Modifier.size(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    content.features.forEach { feature ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(R.drawable.ic_sparkle_filled),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp).gradientTint(extras.gradientBrush()),
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(feature, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
