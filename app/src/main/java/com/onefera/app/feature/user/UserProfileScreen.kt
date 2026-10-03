package com.onefera.app.feature.user

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.onefera.app.R
import com.onefera.app.core.common.compactCount
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.GradientTag
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.AccountMode
import com.onefera.app.data.model.FollowState
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.model.toSummary
import com.onefera.app.data.social.PostRepository
import com.onefera.app.data.social.SocialRepository
import com.onefera.app.data.user.UserRepository
import com.onefera.app.feature.post.postGridItems
import com.onefera.app.navigation.UserProfileRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UserProfileUiState(
    val loading: Boolean = true,
    val profile: UserProfile? = null,
    val followState: FollowState = FollowState.None,
    val posts: List<Post> = emptyList(),
) {
    val canSeePosts: Boolean
        get() = profile != null && (!profile.isPrivate || followState == FollowState.Following || followState == FollowState.Self)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UserProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    users: UserRepository,
    posts: PostRepository,
    private val social: SocialRepository,
) : ViewModel() {
    val uid: String = savedStateHandle.toRoute<UserProfileRoute>().uid
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val state: StateFlow<UserProfileUiState> = combine(users.observeProfile(uid), social.followState(uid)) { profile, follow -> profile to follow }
        .flatMapLatest { (profile, follow) ->
            val base = UserProfileUiState(loading = false, profile = profile, followState = follow)
            if (base.canSeePosts) posts.userPosts(uid).let { flow -> combine(flow, flowOf(base)) { list, b -> b.copy(posts = list) } } else flowOf(base)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserProfileUiState())

    fun follow() = viewModelScope.launch {
        val profile = state.value.profile ?: return@launch
        social.follow(profile.toSummary())
            .onSuccess { _messages.emit(if (it == FollowState.Requested) "Request sent 💌" else "Following @${profile.username} ✨") }
            .onFailure { _messages.emit(it.message ?: "Couldn't follow right now") }
    }

    fun unfollow() = viewModelScope.launch { social.unfollow(uid).onFailure { _messages.emit(it.message ?: "Try again") } }
    fun cancelRequest() = viewModelScope.launch { social.cancelRequest(uid) }
    fun message() = viewModelScope.launch { _messages.emit("DMs are coming in the next update 💬") }
}

@Composable
fun UserProfileScreen(onBack: () -> Unit, viewModel: UserProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val snackbar = remember { SnackbarHostState() }
    var confirmUnfollow by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.5f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = state.profile?.username?.let { "@$it" } ?: "", onBack = onBack)
            val profile = state.profile
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                profile == null -> EmptyState(R.drawable.ic_person, "Account not found", "It may have been removed.", Modifier.fillMaxWidth())
                else -> LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    item {
                        ProfileHeaderCard(
                            profile = profile,
                            followState = state.followState,
                            onFollow = viewModel::follow,
                            onUnfollow = { confirmUnfollow = true },
                            onCancelRequest = viewModel::cancelRequest,
                            onMessage = viewModel::message,
                            onFollowers = { actions.openFollowList(profile.uid, true) },
                            onFollowing = { actions.openFollowList(profile.uid, false) },
                        )
                    }
                    item { Spacer(Modifier.height(14.dp)) }
                    if (!state.canSeePosts) {
                        item {
                            EmptyState(
                                R.drawable.ic_lock_filled,
                                "This account is private 🔒",
                                "Follow @${profile.username} to see their posts and reels.",
                                Modifier.fillMaxWidth(),
                            )
                        }
                    } else if (state.posts.isEmpty()) {
                        item { EmptyState(R.drawable.ic_grid, "No posts yet", "When @${profile.username} posts, it'll land here.", Modifier.fillMaxWidth()) }
                    } else {
                        postGridItems(state.posts, onOpen = { actions.openPost(it.id) })
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    if (confirmUnfollow) {
        AlertDialog(
            onDismissRequest = { confirmUnfollow = false },
            title = { Text("Unfollow @${state.profile?.username}?") },
            text = { if (state.profile?.isPrivate == true) Text("You'll need to request again to see their posts.") },
            confirmButton = { TextButton(onClick = { confirmUnfollow = false; viewModel.unfollow() }) { Text("Unfollow") } },
            dismissButton = { TextButton(onClick = { confirmUnfollow = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProfileHeaderCard(
    profile: UserProfile,
    followState: FollowState,
    onFollow: () -> Unit,
    onUnfollow: () -> Unit,
    onCancelRequest: () -> Unit,
    onMessage: () -> Unit,
    onFollowers: () -> Unit,
    onFollowing: () -> Unit,
) {
    val extras = OneFeraTheme.extras
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(profile.avatarUrl, profile.displayName, size = 84.dp, ring = true)
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GradientTag("Aura ${profile.auraGrade.label} · ${profile.auraPoints}")
                    if (profile.accountMode == AccountMode.Seller) GradientTag("🏪 Seller")
                }
                if (profile.city.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("📍 ${profile.city}", style = MaterialTheme.typography.labelMedium, color = extras.muted)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("Posts", profile.postsCount, Modifier.weight(1f))
            Stat("Followers", profile.followersCount, Modifier.weight(1f), onFollowers)
            Stat("Following", profile.followingCount, Modifier.weight(1f), onFollowing)
        }
        if (profile.bio.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(profile.bio, style = MaterialTheme.typography.bodyMedium)
        }
        if (profile.vibe.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            GradientTag("✨ ${profile.vibe.lowercase()} era")
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (followState) {
                FollowState.None -> GradientButton(text = if (profile.isPrivate) "Request to follow" else "Follow", onClick = onFollow, modifier = Modifier.weight(1f).height(44.dp))
                FollowState.Requested -> GlassButton(text = "Requested", onClick = onCancelRequest, height = 44.dp, modifier = Modifier.weight(1f))
                FollowState.Following -> GlassButton(text = "Following ✓", onClick = onUnfollow, height = 44.dp, modifier = Modifier.weight(1f))
                FollowState.Self -> Unit
            }
            if (followState != FollowState.Self) {
                GlassButton(text = "Message", onClick = onMessage, leadingIcon = R.drawable.ic_chat, height = 44.dp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val extras = OneFeraTheme.extras
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(compactCount(value), style = MaterialTheme.typography.titleLarge, modifier = Modifier.gradientTint(extras.horizontalGradient()))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = extras.muted)
    }
}
