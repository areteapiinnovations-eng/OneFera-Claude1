package com.onefera.app.feature.user

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.social.SocialRepository
import com.onefera.app.navigation.FollowListRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FollowListUiState(
    val loading: Boolean = true,
    val users: List<UserSummary> = emptyList(),
    val following: Set<String> = emptySet(),
    val myUid: String? = null,
)

@HiltViewModel
class FollowListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val social: SocialRepository,
    auth: AuthRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<FollowListRoute>()
    val showFollowers: Boolean = route.followers

    val state: StateFlow<FollowListUiState> = combine(
        if (route.followers) social.followers(route.uid) else social.following(route.uid),
        social.followingIds(),
        auth.session,
    ) { users, following, session ->
        FollowListUiState(false, users, following, (session as? SessionState.SignedIn)?.uid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FollowListUiState())

    fun follow(user: UserSummary) = viewModelScope.launch { social.follow(user) }
}

@Composable
fun FollowListScreen(onBack: () -> Unit, viewModel: FollowListViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.45f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = if (viewModel.showFollowers) "Followers" else "Following", onBack = onBack)
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.users.isEmpty() -> EmptyState(
                    R.drawable.ic_person,
                    if (viewModel.showFollowers) "No followers yet" else "Not following anyone yet",
                    "Your circle grows one vibe at a time 🫶",
                    Modifier.fillMaxWidth(),
                )
                else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(state.users, key = { it.uid }) { user ->
                        val trailing: (@Composable () -> Unit)? = when {
                            user.uid == state.myUid -> null
                            user.uid in state.following -> {
                                { GlassButton(text = "Following", onClick = { actions.openUser(user.uid) }, height = 36.dp, modifier = Modifier.width(110.dp)) }
                            }
                            else -> {
                                { GradientButton(text = "Follow", onClick = { viewModel.follow(user) }, modifier = Modifier.width(96.dp).height(36.dp)) }
                            }
                        }
                        UserRow(user = user, onClick = { actions.openUser(user.uid) }, trailing = trailing)
                    }
                }
            }
        }
    }
}
