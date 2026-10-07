package com.onefera.app.feature.profile

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.onefera.app.R
import com.onefera.app.core.common.AppLinks
import com.onefera.app.core.common.compactCount
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.CircleIconButton
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.GradientTag
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.model.AccountMode
import com.onefera.app.data.model.SellerStatus
import com.onefera.app.data.model.MembershipPlan
import com.onefera.app.data.model.AuraGrade
import com.onefera.app.data.model.UserProfile
import com.onefera.app.feature.main.MainUiState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.feature.main.ProgressBar
import com.onefera.app.feature.post.postGridItems
import kotlinx.coroutines.launch

private enum class ProfileSection(@DrawableRes val icon: Int, val label: String) {
    Posts(R.drawable.ic_grid, "Posts"),
    Reels(R.drawable.ic_movie, "Reels"),
    Saved(R.drawable.ic_bookmark, "Saved"),
}

@Composable
fun ProfileTab(
    state: MainUiState,
    onEditProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleAccountMode: () -> Unit,
    onSignOut: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val profile = state.profile
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        profile == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = R.drawable.ic_person_filled,
                title = "Finish your profile ✨",
                message = "Pick a name and handle so your circle can find you.",
                action = { GradientButton(text = "Set up profile", onClick = onEditProfile, modifier = Modifier.width(220.dp)) },
            )
        }
        else -> ProfileContent(
            profile = profile,
            onMenu = { showMenu = true },
            onEditProfile = onEditProfile,
            onShare = { shareProfile(context, profile) },
        )
    }

    if (showMenu && profile != null) {
        ProfileMenuSheet(
            profile = profile,
            onDismiss = { showMenu = false },
            onShare = { shareProfile(context, profile) },
            onEditProfile = onEditProfile,
            onOpenSettings = onOpenSettings,
            onToggleAccountMode = onToggleAccountMode,
            onComingSoon = onMessage,
            onLogout = { confirmLogout = true },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("You can log back in anytime. Your streak won't miss you for long 🔥") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onSignOut()
                }) { Text("Log out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Stay") } },
        )
    }
}

@Composable
private fun ProfileContent(
    profile: UserProfile,
    onMenu: () -> Unit,
    onEditProfile: () -> Unit,
    onShare: () -> Unit,
    contentViewModel: MyContentViewModel = hiltViewModel(),
) {
    val extras = OneFeraTheme.extras
    val actions = LocalAppActions.current
    val content by contentViewModel.content.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(ProfileSection.Posts) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(imageUrl = profile.avatarUrl, name = profile.displayName, size = 78.dp, ring = true)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.displayName, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            if (profile.verified) {
                                Spacer(Modifier.width(4.dp))
                                Icon(painterResource(R.drawable.ic_badge_filled), contentDescription = "Verified", modifier = Modifier.size(18.dp).gradientTint(extras.gradientBrush()))
                            }
                        }
                        Text("@${profile.username}", style = MaterialTheme.typography.bodyMedium, color = extras.muted)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            GradientTag("Aura ${profile.auraGrade.label} · ${profile.auraPoints}")
                            if (profile.accountMode == AccountMode.Seller) GradientTag("🏪 Seller")
                            val plan = profile.membership.active()
                            if (plan != MembershipPlan.None) GradientTag(if (plan == MembershipPlan.SellerPro) "PRO" else "+")
                        }
                        if (profile.city.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_location), contentDescription = null, tint = extras.muted, modifier = Modifier.size(14.dp))
                                Text(profile.city, style = MaterialTheme.typography.labelMedium, color = extras.muted)
                            }
                        }
                    }
                    CircleIconButton(icon = R.drawable.ic_more, contentDescription = "Profile menu", onClick = onMenu, size = 36.dp)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    Stat("Posts", profile.postsCount, Modifier.weight(1f))
                    Stat("Followers", profile.followersCount, Modifier.weight(1f)) { actions.openFollowList(profile.uid, true) }
                    Stat("Following", profile.followingCount, Modifier.weight(1f)) { actions.openFollowList(profile.uid, false) }
                    Stat("Views", profile.profileViews, Modifier.weight(1f))
                }
                if (profile.bio.isNotBlank() || profile.vibe.isNotBlank()) Spacer(Modifier.height(14.dp))
                if (profile.bio.isNotBlank()) Text(profile.bio, style = MaterialTheme.typography.bodyMedium)
                if (profile.vibe.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    GradientTag("✨ ${profile.vibe.lowercase()} era")
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton(text = "Edit profile", onClick = onEditProfile, leadingIcon = R.drawable.ic_edit, height = 44.dp, modifier = Modifier.weight(1f))
                    GlassButton(text = "Share", onClick = onShare, leadingIcon = R.drawable.ic_share, height = 44.dp, modifier = Modifier.weight(1f))
                }
            }
        }
        if (profile.accountMode == AccountMode.Seller) {
            item { SellerHubCard(onOpen = actions.openSellerHub) }
        }
        item { AuraScoreCard(profile.auraPoints) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileSection.entries.forEach { s ->
                    val selected = s == section
                    val shape = RoundedCornerShape(16.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(shape)
                            .then(if (selected) Modifier.border(1.5.dp, extras.gradientBrush(), shape) else Modifier.border(1.dp, extras.glassBorder, shape))
                            .background(extras.glass)
                            .clickable { section = s },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(s.icon),
                            contentDescription = s.label,
                            tint = if (selected) MaterialTheme.colorScheme.onSurface else extras.muted,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
        val shown = when (section) {
            ProfileSection.Posts -> content.posts
            ProfileSection.Reels -> content.reels
            ProfileSection.Saved -> content.saved
        }
        val emptyAction: (@Composable () -> Unit)? = when (section) {
            ProfileSection.Posts -> {
                { GlassButton(text = "Create a post", onClick = { actions.createPost(false) }) }
            }
            ProfileSection.Reels -> {
                { GlassButton(text = "Create a reel", onClick = { actions.createPost(true) }) }
            }
            ProfileSection.Saved -> null
        }
        if (!content.loading && shown.isEmpty()) {
            item {
                EmptyState(
                    icon = section.icon,
                    title = when (section) {
                        ProfileSection.Posts -> "No posts yet"
                        ProfileSection.Reels -> "No reels yet"
                        ProfileSection.Saved -> "Nothing saved yet"
                    },
                    message = when (section) {
                        ProfileSection.Posts -> "Your first drop is one tap away ✨"
                        ProfileSection.Reels -> "Post a vertical video and share it as a reel 🎬"
                        ProfileSection.Saved -> "Tap the bookmark on any post to keep it here."
                    },
                    action = emptyAction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            postGridItems(shown, onOpen = { actions.openPost(it.id) }, keyPrefix = section.name)
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val extras = OneFeraTheme.extras
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(compactCount(value), style = MaterialTheme.typography.titleLarge, modifier = Modifier.gradientTint(extras.horizontalGradient()))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = extras.muted)
    }
}

@Composable
private fun AuraScoreCard(points: Int) {
    val extras = OneFeraTheme.extras
    val grade = AuraGrade.forPoints(points)
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .border(2.dp, extras.gradientBrush(), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(grade.label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.gradientTint(extras.gradientBrush()))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Aura score", style = MaterialTheme.typography.titleMedium)
                Text("$points / ${AuraGrade.MAX_POINTS} · ${grade.description.lowercase()}", style = MaterialTheme.typography.labelMedium, color = extras.muted)
                Spacer(Modifier.height(8.dp))
                ProgressBar(points.toFloat() / AuraGrade.MAX_POINTS)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Earn aura by posting, getting likes, keeping your streak and completing shop orders.",
            style = MaterialTheme.typography.bodySmall,
            color = extras.muted,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileMenuSheet(
    profile: UserProfile,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleAccountMode: () -> Unit,
    onComingSoon: (String) -> Unit,
    onLogout: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }
    val isSeller = profile.accountMode == AccountMode.Seller
    val actions = LocalAppActions.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PROFILE MENU", style = MaterialTheme.typography.labelMedium, color = OneFeraTheme.extras.muted, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            MenuRow(R.drawable.ic_share, "Share profile") { close(onShare) }
            MenuRow(R.drawable.ic_edit, "Edit profile") { close(onEditProfile) }
            MenuRow(R.drawable.ic_settings, "Settings") { close(onOpenSettings) }
            MenuRow(R.drawable.ic_sparkle_filled, "Rewards & Mystery Box") { close(actions.openRewards) }
            MenuRow(R.drawable.ic_leaderboard, "Aura leaderboard") { close(actions.openLeaderboard) }
            MenuRow(R.drawable.ic_crown_filled, "Membership") { close(actions.openMembership) }
            if (isSeller) MenuRow(R.drawable.ic_storefront, "Seller hub") { close(actions.openSellerHub) }
            when {
                profile.canUseSellerMode -> MenuRow(R.drawable.ic_swap, if (isSeller) "Switch to personal" else "Switch to seller") { close(onToggleAccountMode) }
                profile.sellerStatus == SellerStatus.Pending -> MenuRow(R.drawable.ic_storefront, "Seller registration: under review") { close(actions.becomeSeller) }
                profile.sellerStatus == SellerStatus.Rejected -> MenuRow(R.drawable.ic_storefront, "Seller registration: needs changes") { close(actions.becomeSeller) }
                !profile.isMinor -> MenuRow(R.drawable.ic_storefront, "Become a Seller") { close(actions.becomeSeller) }
            }
            MenuRow(R.drawable.ic_logout, "Log out", tint = MaterialTheme.colorScheme.error) { close(onLogout) }
        }
    }
}

/** Shortcut to the Seller hub, shown on your profile while in Seller mode. */
@Composable
private fun SellerHubCard(onOpen: () -> Unit) {
    val extras = OneFeraTheme.extras
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(extras.gradientBrush())
            .clickable(onClick = onOpen)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_storefront), contentDescription = null, tint = extras.onGradient, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Seller hub", style = MaterialTheme.typography.titleMedium, color = extras.onGradient)
            Text("Listings, orders and sales in one place", style = MaterialTheme.typography.bodySmall, color = extras.onGradient.copy(alpha = 0.85f))
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = extras.onGradient, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun MenuRow(@DrawableRes icon: Int, label: String, soon: Boolean = false, tint: Color? = null, onClick: () -> Unit) {
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(extras.glass)
            .border(1.dp, extras.glassBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tint != null) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        } else {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp).gradientTint(extras.gradientBrush()))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = tint ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (soon) GradientTag("SOON")
    }
}

private fun shareProfile(context: Context, profile: UserProfile) {
    val text = "Catch me on OneFera ✦ @${profile.username}\n${AppLinks.profile(profile.username)}"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share your profile"))
}
