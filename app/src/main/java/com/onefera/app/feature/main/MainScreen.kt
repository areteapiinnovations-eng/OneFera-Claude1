package com.onefera.app.feature.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
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
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.UserProfile
import com.onefera.app.feature.chat.ChatsTab
import com.onefera.app.feature.feed.FeedTab
import com.onefera.app.feature.profile.ProfileTab
import com.onefera.app.feature.reels.ReelsTab
import com.onefera.app.feature.search.SearchTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onEditProfile: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unread by viewModel.unreadCount.collectAsStateWithLifecycle()
    val storyUpload by viewModel.storyUpload.collectAsStateWithLifecycle()
    val unreadChats by viewModel.unreadChats.collectAsStateWithLifecycle()
    val askNotifications by viewModel.shouldAskNotificationPermission.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(askNotifications) {
        if (askNotifications && Build.VERSION.SDK_INT >= 33) {
            viewModel.onNotificationPromptShown()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val actions = LocalAppActions.current
    var tab by rememberSaveable { mutableStateOf(MainTab.Home) }
    var showCreate by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val tabStates = rememberSaveableStateHolder()
    val pickStory = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.addStory(uri)
    }
    val addStory = { pickStory.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }

    val immersive = tab == MainTab.Reels
    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.55f) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (!immersive) {
                    Column {
                        MainTopBar(
                            profile = state.profile,
                            unread = unread,
                            onCreate = { showCreate = true },
                            onStreak = { tab = MainTab.Home },
                            onAura = { tab = MainTab.You },
                            onNotifications = actions.openNotifications,
                        )
                        storyUpload?.let { progress ->
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
                        }
                    }
                }
            },
            bottomBar = { MainBottomBar(selected = tab, profile = state.profile, unreadChats = unreadChats, onSelect = { tab = it }) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AnimatedContent(targetState = tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { current ->
                    tabStates.SaveableStateProvider(current.name) {
                        when (current) {
                            MainTab.Home -> FeedTab(
                                profile = state.profile,
                                isDemoMode = viewModel.isDemoMode,
                                onAddStory = addStory,
                                onMessage = viewModel::showMessage,
                            )
                            MainTab.Search -> SearchTab()
                            MainTab.Chats -> ChatsTab()
                            MainTab.Reels -> ReelsTab(onMessage = viewModel::showMessage)
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

    if (showCreate) {
        ModalBottomSheet(
            onDismissRequest = { showCreate = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Create", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                CreateOption(R.drawable.ic_photo, "Post", "Photos or a video for your feed") { showCreate = false; actions.createPost(false) }
                CreateOption(R.drawable.ic_reels_filled, "Reel", "Full-screen vertical video") { showCreate = false; actions.createPost(true) }
                CreateOption(R.drawable.ic_story, "Story", "Disappears after 24 hours") { showCreate = false; addStory() }
            }
        }
    }
}

@Composable
private fun CreateOption(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    val extras = OneFeraTheme.extras
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(extras.glass)
            .border(1.dp, extras.glassBorder, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(extras.gradientBrush()), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = extras.onGradient, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = extras.muted)
        }
    }
}

@Composable
private fun MainTopBar(
    profile: UserProfile?,
    unread: Int,
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
        InfoPill(text = "${profile?.streakDays ?: 0}", icon = R.drawable.ic_fire_filled, iconTint = Color(0xFFFF8A3D), onClick = onStreak)
        Spacer(Modifier.width(6.dp))
        InfoPill(text = "${profile?.auraPoints ?: 0}", icon = R.drawable.ic_bolt_filled, onClick = onAura)
        Spacer(Modifier.width(6.dp))
        Box {
            CircleIconButton(
                icon = if (unread > 0) R.drawable.ic_notifications_filled else R.drawable.ic_bell,
                contentDescription = if (unread > 0) "Notifications, $unread unread" else "Notifications",
                onClick = onNotifications,
                size = 38.dp,
            )
            if (unread > 0) {
                Text(
                    if (unread > 9) "9+" else "$unread",
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
}

@Composable
private fun MainBottomBar(selected: MainTab, profile: UserProfile?, unreadChats: Int, onSelect: (MainTab) -> Unit) {
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
                    badge = if (tab == MainTab.Chats) unreadChats else 0,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(tab: MainTab, selected: Boolean, profile: UserProfile?, badge: Int, onClick: () -> Unit, modifier: Modifier) {
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
            Box {
                val iconModifier = Modifier.size(26.dp)
                if (selected) {
                    Icon(painterResource(tab.selectedIcon), contentDescription = null, modifier = iconModifier.gradientTint(extras.gradientBrush()))
                } else {
                    Icon(painterResource(tab.icon), contentDescription = null, tint = extras.muted, modifier = iconModifier)
                }
                if (badge > 0) {
                    Text(
                        if (badge > 9) "9+" else "$badge",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 8.dp, y = (-4).dp)
                            .clip(CircleShape)
                            .background(StatusColors.Live)
                            .padding(horizontal = 4.dp),
                    )
                }
            }
        }
        Text(tab.label, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.onSurface else extras.muted, maxLines = 1)
    }
}
