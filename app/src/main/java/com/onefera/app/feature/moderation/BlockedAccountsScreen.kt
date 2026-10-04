package com.onefera.app.feature.moderation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.moderation.ModerationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class BlockedAccountsViewModel @Inject constructor(moderation: ModerationRepository) : ViewModel() {
    val blocked: StateFlow<List<UserSummary>?> = moderation.blockedUsers().map<List<UserSummary>, List<UserSummary>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun BlockedAccountsScreen(
    onBack: () -> Unit,
    viewModel: BlockedAccountsViewModel = hiltViewModel(),
    moderation: ModerationViewModel = hiltViewModel(),
) {
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()
    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.4f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Blocked accounts", onBack = onBack)
            val list = blocked
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    icon = R.drawable.ic_shield,
                    title = "No one blocked",
                    message = "Block someone from their profile, a post or a chat. They aren't notified.",
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                )
                else -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    items(list, key = { it.uid }) { user ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(user.avatarUrl, user.displayName, size = 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(user.displayName.ifEmpty { "OneFera member" }, style = MaterialTheme.typography.titleSmall)
                                if (user.username.isNotEmpty()) {
                                    Text("@${user.username}", style = MaterialTheme.typography.labelSmall, color = OneFeraTheme.extras.muted)
                                }
                            }
                            GlassButton("Unblock", onClick = { moderation.unblock(user) }, height = 38.dp)
                        }
                    }
                }
            }
        }
    }
}
