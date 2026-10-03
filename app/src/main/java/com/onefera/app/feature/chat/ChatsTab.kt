package com.onefera.app.feature.chat

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.onefera.app.R
import com.onefera.app.core.common.timeAgo
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.CircleIconButton
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.chat.ChatRepository
import com.onefera.app.data.model.Conversation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ChatsUiState(val loading: Boolean = true, val conversations: List<Conversation> = emptyList(), val myUid: String = "")

@HiltViewModel
class ChatsViewModel @Inject constructor(chat: ChatRepository, auth: AuthRepository) : ViewModel() {
    val state: StateFlow<ChatsUiState> = combine(
        chat.conversations(),
        auth.session.map { (it as? SessionState.SignedIn)?.uid.orEmpty() },
    ) { list, uid -> ChatsUiState(false, list, uid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatsUiState())
}

@Composable
fun ChatsTab(viewModel: ChatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    var query by rememberSaveable { mutableStateOf("") }
    val shown = state.conversations.filter { c ->
        val other = c.other(state.myUid)
        query.isBlank() || other.displayName.contains(query, true) || other.username.contains(query, true)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Messages 💬", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            CircleIconButton(icon = R.drawable.ic_edit, contentDescription = "New message", onClick = actions.newChat, filled = true)
        }
        if (state.conversations.isNotEmpty()) {
            OneFeraTextField(
                value = query,
                onValueChange = { query = it },
                label = "Search chats",
                leadingIcon = R.drawable.ic_search,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        when {
            state.loading -> Unit
            state.conversations.isEmpty() -> EmptyState(
                icon = R.drawable.ic_chat_filled,
                title = "No chats yet",
                message = "Slide into someone's DMs (respectfully) ✨",
                action = { GradientButton(text = "Start a chat", onClick = actions.newChat, modifier = Modifier.width(220.dp)) },
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(shown, key = { it.id }) { c -> ConversationRow(c, state.myUid) { actions.openChat(c.id) } }
            }
        }
    }
}

@Composable
private fun ConversationRow(c: Conversation, me: String, onClick: () -> Unit) {
    val extras = OneFeraTheme.extras
    val other = c.other(me)
    val unread = c.unreadFor(me)
    val typing = c.isTyping(other.uid)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(other.avatarUrl, other.displayName, size = 54.dp, ring = unread > 0)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                other.displayName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val preview = when {
                typing -> "typing…"
                c.lastSenderId == me -> "You: ${c.lastMessage}"
                else -> c.lastMessage
            }
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal),
                color = if (unread > 0 || typing) MaterialTheme.colorScheme.onSurface else extras.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (typing) Modifier.gradientTint(extras.horizontalGradient()) else Modifier,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(timeAgo(c.lastMessageAt), style = MaterialTheme.typography.labelSmall, color = extras.muted)
            if (unread > 0) {
                Text(
                    if (unread > 9) "9+" else "$unread",
                    color = extras.onGradient,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clip(CircleShape).background(extras.gradientBrush()).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            } else {
                Spacer(Modifier.size(16.dp))
            }
        }
    }
}

/** Small green dot shown on avatars of people who are active right now. */
@Composable
fun OnlineDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(StatusColors.Success)
            .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
    )
}

