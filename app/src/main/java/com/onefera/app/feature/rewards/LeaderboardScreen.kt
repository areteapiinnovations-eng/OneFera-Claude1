package com.onefera.app.feature.rewards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GradientTag
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.component.SelectChip
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.LeaderboardEntry
import com.onefera.app.data.model.LeaderboardScope
import com.onefera.app.data.rewards.RewardsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class LeaderboardUiState(
    val scope: LeaderboardScope = LeaderboardScope.Global,
    val entries: List<LeaderboardEntry>? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LeaderboardViewModel @Inject constructor(rewards: RewardsRepository) : ViewModel() {
    private val scope = MutableStateFlow(LeaderboardScope.Global)

    val state: StateFlow<LeaderboardUiState> = combine(
        scope,
        scope.flatMapLatest { s -> rewards.leaderboard(s).map<List<LeaderboardEntry>, List<LeaderboardEntry>?> { it } },
    ) { s, entries -> LeaderboardUiState(s, entries) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LeaderboardUiState())

    fun onScope(value: LeaderboardScope) {
        scope.value = value
    }
}

@Composable
fun LeaderboardScreen(onBack: () -> Unit, viewModel: LeaderboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.55f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Aura leaderboard ⚡", onBack = onBack)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LeaderboardScope.entries) { s -> SelectChip(s.label, selected = s == state.scope, onClick = { viewModel.onScope(s) }) }
            }
            val entries = state.entries
            when {
                entries == null -> Unit
                entries.isEmpty() -> EmptyState(
                    icon = R.drawable.ic_leaderboard,
                    title = "No one here yet",
                    message = if (state.scope == LeaderboardScope.City) "Add your city in Edit profile to see who's top near you." else "Follow people to compete with your friends.",
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                )
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entries.size >= 3) {
                        item { Podium(entries.take(3), onOpen = actions.openUser) }
                    }
                    items(if (entries.size >= 3) entries.drop(3) else entries, key = { it.user.uid }) { entry ->
                        LeaderRow(entry, onClick = { actions.openUser(entry.user.uid) })
                    }
                    entries.firstOrNull { it.isMe }?.takeIf { it.rank > 3 }?.let { me ->
                        item { Text("You're #${me.rank} · keep that streak going 🔥", style = MaterialTheme.typography.labelLarge, color = OneFeraTheme.extras.muted, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Podium(top: List<LeaderboardEntry>, onOpen: (String) -> Unit) {
    // Display order: 2nd, 1st, 3rd.
    val order = listOf(top[1], top[0], top[2])
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        order.forEach { e ->
            val (medal, avatar, height) = when (e.rank) {
                1 -> Triple("🥇", 76.dp, 120.dp)
                2 -> Triple("🥈", 62.dp, 96.dp)
                else -> Triple("🥉", 62.dp, 80.dp)
            }
            PodiumSpot(e, medal, avatar, height, Modifier.weight(1f), onClick = { onOpen(e.user.uid) })
        }
    }
}

@Composable
private fun PodiumSpot(e: LeaderboardEntry, medal: String, avatar: Dp, height: Dp, modifier: Modifier, onClick: () -> Unit) {
    val extras = OneFeraTheme.extras
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(e.user.avatarUrl, e.user.displayName, size = avatar, ring = true)
        Spacer(Modifier.height(6.dp))
        Text(e.user.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(extras.gradientBrush()),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(medal, style = MaterialTheme.typography.headlineSmall)
                Text("${e.auraPoints}", style = MaterialTheme.typography.titleMedium, color = extras.onGradient)
            }
        }
    }
}

@Composable
private fun LeaderRow(e: LeaderboardEntry, onClick: () -> Unit) {
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(extras.glass)
            .then(if (e.isMe) Modifier.border(1.5.dp, extras.gradientBrush(), shape) else Modifier.border(1.dp, extras.glassBorder, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#${e.rank}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(40.dp))
        Avatar(e.user.avatarUrl, e.user.displayName, size = 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (e.isMe) "${e.user.displayName} (you)" else e.user.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@${e.user.username} · 🔥 ${e.streakDays}", style = MaterialTheme.typography.labelSmall, color = extras.muted, maxLines = 1)
        }
        GradientTag("${e.grade.label} · ${e.auraPoints}")
    }
}
