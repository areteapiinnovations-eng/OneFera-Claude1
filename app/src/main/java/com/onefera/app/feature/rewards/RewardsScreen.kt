package com.onefera.app.feature.rewards

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.GradientTag
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.component.rememberReducedMotion
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.model.AuraGrade
import com.onefera.app.data.model.BoxStatus
import com.onefera.app.data.model.Coupon
import com.onefera.app.data.model.MysteryReward
import com.onefera.app.data.model.Rewards
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.rewards.RewardsRepository
import com.onefera.app.data.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class RewardsUiState(
    val profile: UserProfile? = null,
    val box: BoxStatus = BoxStatus(),
    val coupons: List<Coupon> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RewardsViewModel @Inject constructor(
    auth: AuthRepository,
    users: UserRepository,
    private val rewards: RewardsRepository,
) : ViewModel() {
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<RewardsUiState> = combine(
        auth.uidFlow().flatMapLatest { uid -> if (uid == null) flowOf(null) else users.observeProfile(uid) },
        rewards.boxStatus(),
        rewards.coupons(),
    ) { profile, box, coupons -> RewardsUiState(profile, box, coupons) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RewardsUiState())

    var opening by mutableStateOf(false)
        private set
    var reward by mutableStateOf<MysteryReward?>(null)
        private set

    fun checkIn() = viewModelScope.launch {
        rewards.checkIn()
            .onSuccess { r -> if (!r.alreadyCheckedIn) _messages.send("🔥 Day ${r.streak}! +${r.auraGained} Aura · your box is ready") }
            .onFailure { _messages.send(it.message ?: "Couldn't check in.") }
    }

    fun openBox() {
        if (opening) return
        opening = true
        viewModelScope.launch {
            rewards.openMysteryBox()
                .onSuccess { reward = it }
                .onFailure { _messages.send(it.message ?: "Couldn't open the box.") }
            opening = false
        }
    }

    fun dismissReward() {
        reward = null
    }
}

@Composable
fun RewardsScreen(onBack: () -> Unit, viewModel: RewardsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    val profile = state.profile

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.55f) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { ScreenHeader("Rewards ✨", onBack = onBack) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StreakCard(profile, state.box, onCheckIn = { viewModel.checkIn() })
                MysteryBoxCard(state.box, opening = viewModel.opening, onOpen = viewModel::openBox)
                AuraCard(profile?.auraPoints ?: 0, onLeaderboard = actions.openLeaderboard)
                CouponsCard(state.coupons)
                MembershipTeaser(profile, onOpen = actions.openMembership)
            }
        }
    }

    viewModel.reward?.let { reward ->
        AlertDialog(
            onDismissRequest = viewModel::dismissReward,
            icon = { Text(if (reward.coupon != null) "🎟️" else "⚡", style = MaterialTheme.typography.displaySmall) },
            title = { Text("You got ${reward.headline}!", textAlign = TextAlign.Center) },
            text = {
                Text(
                    reward.coupon?.let { "${it.condition}. It's waiting at checkout for ${Rewards.COUPON_DAYS} days." }
                        ?: "Your Aura just levelled up. Keep the streak going for more boxes.",
                    textAlign = TextAlign.Center,
                )
            },
            confirmButton = { TextButton(onClick = viewModel::dismissReward) { Text("Let's go") } },
        )
    }
}

@Composable
private fun StreakCard(profile: UserProfile?, box: BoxStatus, onCheckIn: () -> Unit) {
    val extras = OneFeraTheme.extras
    val streak = profile?.streakDays ?: 0
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(extras.gradientBrush()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔥", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("$streak-day streak", style = MaterialTheme.typography.headlineSmall, color = extras.onGradient)
                Text(
                    if (box.checkedInToday) "Checked in today. See you tomorrow!" else "Check in to keep it alive",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.onGradient.copy(alpha = 0.9f),
                )
            }
        }
        // This week: the dot for day N of the 7-day cycle lights up; day 7 pays a bonus.
        val inCycle = if (streak == 0) 0 else ((streak - 1) % 7) + 1
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..7).forEach { day ->
                val done = day <= inCycle
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (done) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (day == 7) "🎁" else "$day",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (done) Color.Black else extras.onGradient,
                    )
                }
            }
        }
        Text("+${Rewards.CHECK_IN_AURA} Aura a day, +${Rewards.WEEK_BONUS_AURA} bonus every 7th day", style = MaterialTheme.typography.labelMedium, color = extras.onGradient)
        if (!box.checkedInToday) {
            Text(
                "Check in now",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = onCheckIn).padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun MysteryBoxCard(box: BoxStatus, opening: Boolean, onOpen: () -> Unit) {
    val extras = OneFeraTheme.extras
    val reduced = rememberReducedMotion()
    val wiggle = rememberInfiniteTransition(label = "box")
    val angle by wiggle.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(if (opening) 90 else 900, easing = LinearEasing), RepeatMode.Reverse),
        label = "angle",
    )
    val ready = box.available > 0
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (ready) extras.gradientBrush() else androidx.compose.ui.graphics.SolidColor(extras.glassBorder)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "🎁",
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.rotate(if (ready && !reduced) angle else 0f).scale(if (opening) 1.15f else 1f),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mystery Box", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    GradientTag("${box.available}/${box.allowed}")
                }
                Text(
                    when {
                        !box.checkedInToday -> "Check in to unlock today's box"
                        ready -> "Aura, coupons or free delivery inside"
                        else -> "All opened. New boxes drop at midnight"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = extras.muted,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        GradientButton(if (opening) "Opening…" else "Open box", onClick = onOpen, enabled = ready, loading = opening)
    }
}

@Composable
private fun AuraCard(points: Int, onLeaderboard: () -> Unit) {
    val extras = OneFeraTheme.extras
    val grade = AuraGrade.forPoints(points)
    val next = AuraGrade.entries.firstOrNull { it.minPoints > points }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_bolt_filled), contentDescription = null, modifier = Modifier.size(24.dp).gradientTint(extras.gradientBrush()))
            Spacer(Modifier.width(8.dp))
            Text("Aura $points", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            GradientTag("Grade ${grade.label}")
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { points / AuraGrade.MAX_POINTS.toFloat() },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            next?.let { "${it.minPoints - points} Aura to grade ${it.label} · ${it.description}" } ?: "Max grade reached. Legend 👑",
            style = MaterialTheme.typography.labelMedium,
            color = extras.muted,
        )
        Spacer(Modifier.height(4.dp))
        Text("Earn Aura by posting, getting likes, comments and followers, and keeping your streak.", style = MaterialTheme.typography.bodySmall, color = extras.muted)
        Spacer(Modifier.height(12.dp))
        GlassButton("See the leaderboard", onClick = onLeaderboard, leadingIcon = R.drawable.ic_leaderboard, height = 46.dp, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CouponsCard(coupons: List<Coupon>) {
    val extras = OneFeraTheme.extras
    val date = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    GlassCard(Modifier.fillMaxWidth()) {
        Text("Your coupons", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (coupons.isEmpty()) {
            Text("Open Mystery Boxes to win coupons. They show up at checkout automatically.", style = MaterialTheme.typography.bodySmall, color = extras.muted)
        }
        coupons.forEach { c ->
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape).border(1.dp, extras.gradientBrush(), shape).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🎟️", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.titleSmall)
                    Text(c.condition, style = MaterialTheme.typography.labelSmall, color = extras.muted)
                }
                Text("till ${date.format(Date(c.expiresAt))}", style = MaterialTheme.typography.labelSmall, color = StatusColors.Warning)
            }
        }
    }
}

@Composable
private fun MembershipTeaser(profile: UserProfile?, onOpen: () -> Unit) {
    val extras = OneFeraTheme.extras
    val active = profile?.membership?.active()
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.5.dp, extras.gradientBrush(), shape).background(extras.glass).clickable(onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_crown_filled), contentDescription = null, modifier = Modifier.size(28.dp).gradientTint(extras.gradientBrush()))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (active != null && active != com.onefera.app.data.model.MembershipPlan.None) "You're on ${active.label} 💎" else "Go OneFera+",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("2 boxes a day, double streak Aura, free delivery", style = MaterialTheme.typography.bodySmall, color = extras.muted)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = extras.muted, modifier = Modifier.size(20.dp))
    }
}
