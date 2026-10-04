package com.onefera.app.feature.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.onefera.app.R
import com.onefera.app.core.common.timeAgo
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.AppNotification
import com.onefera.app.data.model.NotificationType
import com.onefera.app.data.social.NotificationRepository
import com.onefera.app.data.social.SocialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationsUiState(val loading: Boolean = true, val items: List<AppNotification> = emptyList())

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val notifications: NotificationRepository,
    private val social: SocialRepository,
) : ViewModel() {
    val state: StateFlow<NotificationsUiState> = notifications.notifications().map { NotificationsUiState(false, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationsUiState())

    fun markRead() = viewModelScope.launch {
        delay(1_500)
        notifications.markAllRead()
    }

    fun respond(n: AppNotification, accept: Boolean) = viewModelScope.launch { social.respondToRequest(n.actor.uid, accept) }
}

@Composable
fun NotificationsScreen(onBack: () -> Unit, viewModel: NotificationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    LaunchedEffect(Unit) { viewModel.markRead() }
    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.45f) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = "Notifications", onBack = onBack)
            if (!state.loading && state.items.isEmpty()) {
                EmptyState(
                    icon = R.drawable.ic_notifications_filled,
                    title = "All caught up ✨",
                    message = "Likes, follows and comments will show up here.",
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                )
                return@Column
            }
            val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            val (recent, earlier) = state.items.partition { it.createdAt >= dayAgo }
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
                listOf("New" to recent, "Earlier" to earlier).forEach { (title, list) ->
                    if (list.isNotEmpty()) {
                        item(key = "h-$title") {
                            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                        }
                        items(list, key = { it.id }) { n ->
                            NotificationRow(
                                n = n,
                                onClick = {
                                    val postId = n.postId
                                    if (postId != null) actions.openPost(postId) else actions.openUser(n.actor.uid)
                                },
                                onActor = { actions.openUser(n.actor.uid) },
                                onAccept = { viewModel.respond(n, true) },
                                onDecline = { viewModel.respond(n, false) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    n: AppNotification,
    onClick: () -> Unit,
    onActor: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val extras = OneFeraTheme.extras
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (!n.read) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(n.actor.avatarUrl, n.actor.displayName, size = 48.dp, modifier = Modifier.clip(CircleShape).clickable(onClick = onActor))
            val badge = when (n.type) {
                NotificationType.Like -> R.drawable.ic_heart_filled to StatusColors.Live
                NotificationType.Comment -> R.drawable.ic_chat_filled to MaterialTheme.colorScheme.primary
                else -> R.drawable.ic_person_add to MaterialTheme.colorScheme.secondary
            }
            Box(
                Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(badge.first), contentDescription = null, tint = badge.second, modifier = Modifier.size(13.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(n.actor.username.ifBlank { n.actor.displayName }) }
                    append(" ")
                    append(n.text)
                    withStyle(SpanStyle(color = extras.muted)) { append("  ${timeAgo(n.createdAt)}") }
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
            )
            if (n.type == NotificationType.FollowRequest) {
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton(text = "Confirm", onClick = onAccept, modifier = Modifier.width(110.dp).padding(0.dp).height36())
                    GlassButton(text = "Delete", onClick = onDecline, height = 36.dp, modifier = Modifier.width(100.dp))
                }
            }
        }
        if (n.postThumbUrl != null) {
            Spacer(Modifier.width(10.dp))
            AsyncImage(
                model = n.postThumbUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
    }
}

private fun Modifier.height36(): Modifier = this.then(Modifier.size(width = 110.dp, height = 36.dp))
