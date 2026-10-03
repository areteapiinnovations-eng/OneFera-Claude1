package com.onefera.app.feature.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.CircleIconButton
import com.onefera.app.core.designsystem.component.InfoPill
import com.onefera.app.core.designsystem.component.OneFeraWordmark
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.component.pressScale
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.model.UserProfile
import com.onefera.app.feature.profile.ProfileTab

@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onEditProfile: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(MainTab.Home) }
    val snackbar = remember { SnackbarHostState() }
    val tabStates = rememberSaveableStateHolder()

    LaunchedEffect(viewModel) {
        viewModel.messages.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.55f) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                MainTopBar(
                    profile = state.profile,
                    onCreate = { viewModel.showMessage("Posting & reels land in the next build 🎬") },
                    onStreak = { tab = MainTab.Home },
                    onAura = { tab = MainTab.You },
                    onNotifications = { viewModel.showMessage("Notifications are coming in the next build 🔔") },
                )
            },
            bottomBar = {
                MainBottomBar(selected = tab, profile = state.profile, onSelect = { tab = it })
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "tab",
                ) { current ->
                    tabStates.SaveableStateProvider(current.name) {
                        when (current) {
                            MainTab.Home -> HomeTab(
                                profile = state.profile,
                                isDemoMode = viewModel.isDemoMode,
                                onOpenTab = { tab = it },
                            )
                            MainTab.You -> ProfileTab(
                                state = state,
                                onEditProfile = onEditProfile,
                                onOpenSettings = onOpenSettings,
                                onToggleAccountMode = viewModel::toggleAccountMode,
                                onSignOut = viewModel::signOut,
                                onMessage = viewModel::showMessage,
                            )
                            else -> UpcomingTab(current)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainTopBar(
    profile: UserProfile?,
    onCreate: () -> Unit,
    onStreak: () -> Unit,
    onAura: () -> Unit,
    onNotifications: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(icon = R.drawable.ic_add, contentDescription = "Create", onClick = onCreate, filled = true, size = 38.dp)
        Spacer(Modifier.width(10.dp))
        OneFeraWordmark(height = 22.dp)
        Spacer(Modifier.weight(1f))
        InfoPill(
            text = "${profile?.streakDays ?: 0}",
            icon = R.drawable.ic_fire_filled,
            iconTint = Color(0xFFFF8A3D),
            onClick = onStreak,
        )
        Spacer(Modifier.width(6.dp))
        InfoPill(text = "${profile?.auraPoints ?: 0}", icon = R.drawable.ic_bolt_filled, onClick = onAura)
        Spacer(Modifier.width(6.dp))
        CircleIconButton(icon = R.drawable.ic_bell, contentDescription = "Notifications", onClick = onNotifications, size = 38.dp)
    }
}

@Composable
private fun MainBottomBar(selected: MainTab, profile: UserProfile?, onSelect: (MainTab) -> Unit) {
    val extras = OneFeraTheme.extras
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (extras.isDark) 0.94f else 0.97f)),
    ) {
        HorizontalDivider(color = extras.glassBorder)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainTab.entries.forEach { tab ->
                BottomBarItem(
                    tab = tab,
                    selected = tab == selected,
                    profile = profile,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(tab: MainTab, selected: Boolean, profile: UserProfile?, onClick: () -> Unit, modifier: Modifier) {
    val extras = OneFeraTheme.extras
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .pressScale(interaction, 0.88f)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (tab == MainTab.You) {
            Avatar(imageUrl = profile?.avatarUrl, name = profile?.displayName ?: "You", size = 28.dp, ring = selected)
        } else {
            val iconModifier = Modifier.size(26.dp)
            if (selected) {
                Icon(painterResource(tab.selectedIcon), contentDescription = null, modifier = iconModifier.gradientTint(extras.gradientBrush()))
            } else {
                Icon(painterResource(tab.icon), contentDescription = null, tint = extras.muted, modifier = iconModifier)
            }
        }
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface else extras.muted,
            maxLines = 1,
        )
    }
}
