package com.onefera.app.feature.story

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.chat.ChatRepository
import com.onefera.app.data.chat.OutgoingMessage
import com.onefera.app.data.model.Story
import com.onefera.app.data.model.StoryAudience
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.moderation.ReportTarget
import com.onefera.app.feature.moderation.ReportDialog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import com.onefera.app.R
import com.onefera.app.core.common.timeAgo
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.data.model.StoryGroup
import com.onefera.app.data.social.StoryRepository
import com.onefera.app.navigation.StoryViewerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class StoryViewerUiState(val loading: Boolean = true, val groups: List<StoryGroup> = emptyList(), val startIndex: Int = 0)

@HiltViewModel
class StoryViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val stories: StoryRepository,
    private val chat: ChatRepository,
    private val seen: SeenStories,
) : ViewModel() {
    private val authorUid = savedStateHandle.toRoute<StoryViewerRoute>().authorUid
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<StoryViewerUiState> = stories.storyGroups().map { groups ->
        StoryViewerUiState(false, groups, groups.indexOfFirst { it.author.uid == authorUid }.coerceAtLeast(0))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StoryViewerUiState())

    fun markSeen(story: Story) {
        seen.markSeen(story.id)
        viewModelScope.launch { stories.markViewed(story) }
    }

    fun viewers(storyId: String): Flow<List<UserSummary>> = stories.viewers(storyId)

    fun delete(story: Story, onDone: () -> Unit) = viewModelScope.launch {
        stories.deleteStory(story)
            .onSuccess { _messages.send("Story deleted"); onDone() }
            .onFailure { _messages.send(it.message ?: "Couldn't delete the story") }
    }

    /** Sends a reply to the story's author as a direct message. */
    fun reply(story: Story, text: String) = viewModelScope.launch {
        val body = text.trim()
        if (body.isEmpty()) return@launch
        chat.openConversation(story.author)
            .mapCatching { cid -> chat.send(cid, OutgoingMessage("Replied to your story: $body")).getOrThrow() }
            .onSuccess { _messages.send("Reply sent to @${story.author.username}") }
            .onFailure { _messages.send(it.message ?: "Couldn't send your reply") }
    }
}

private const val STORY_DURATION_MS = 5_000

@Composable
fun StoryViewerScreen(onClose: () -> Unit, viewModel: StoryViewerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            return@Box
        }
        if (state.groups.isEmpty()) {
            LaunchedEffect(Unit) { onClose() }
            return@Box
        }
        // Snapshot the groups when the viewer opens so new stories don't shift the position.
        val groups = remember { state.groups }
        var groupIndex by remember { mutableIntStateOf(state.startIndex.coerceIn(0, groups.lastIndex)) }
        var storyIndex by remember { mutableIntStateOf(0) }
        var paused by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var showViewers by remember { mutableStateOf(false) }
        var replying by remember { mutableStateOf(false) }
        var reporting by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }
        var reply by remember { mutableStateOf("") }
        val progress = remember { Animatable(0f) }
        val group = groups[groupIndex]
        val story = group.stories[storyIndex.coerceIn(0, group.stories.lastIndex)]

        fun next() {
            when {
                storyIndex < group.stories.lastIndex -> storyIndex++
                groupIndex < groups.lastIndex -> { groupIndex++; storyIndex = 0 }
                else -> onClose()
            }
        }
        fun previous() {
            when {
                storyIndex > 0 -> storyIndex--
                groupIndex > 0 -> { groupIndex--; storyIndex = 0 }
            }
        }

        LaunchedEffect(story.id) {
            viewModel.markSeen(story)
            progress.snapTo(0f)
        }
        val holding = paused || menu || showViewers || replying || reporting || confirmDelete
        LaunchedEffect(story.id, holding) {
            if (!holding) {
                val remaining = ((1f - progress.value) * STORY_DURATION_MS).toInt()
                progress.animateTo(1f, tween(remaining, easing = LinearEasing))
                next()
            }
        }

        AsyncImage(
            model = story.mediaUrl,
            contentDescription = "Story by ${story.author.displayName}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(story.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val pressedAt = System.currentTimeMillis()
                        paused = true
                        val up = waitForUpOrCancellation()
                        paused = false
                        val wasTap = System.currentTimeMillis() - pressedAt < 250
                        if (up != null && wasTap) {
                            if (down.position.x < size.width / 3f) previous() else next()
                        }
                    }
                },
        )
        Box(
            Modifier.fillMaxWidth().height(160.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))),
        )
        Column(Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                group.stories.forEachIndexed { i, _ ->
                    val fill = when {
                        i < storyIndex -> 1f
                        i == storyIndex -> progress.value
                        else -> 0f
                    }
                    Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f))) {
                        Box(Modifier.fillMaxWidth(fill).height(3.dp).background(Color.White))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(story.author.avatarUrl, story.author.displayName, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (group.isMine) "Your story" else story.author.username, color = Color.White, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Text(timeAgo(story.createdAt), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                if (story.audience == StoryAudience.FOLLOWERS) {
                    Spacer(Modifier.width(8.dp))
                    Icon(painterResource(R.drawable.ic_lock_filled), contentDescription = "Followers only", tint = Color.White, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(painterResource(R.drawable.ic_more), contentDescription = "Story options", tint = Color.White)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (group.isMine) {
                            DropdownMenuItem(text = { Text("Delete story", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
                        } else {
                            DropdownMenuItem(text = { Text("View profile") }, onClick = { menu = false; actions.openUser(story.author.uid) })
                            DropdownMenuItem(text = { Text("Report", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; reporting = true })
                        }
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Close stories", tint = Color.White)
                }
            }
        }

        // Bottom: mentions, then "seen by" for the author or a reply box for everyone else.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp)) {
            if (story.mentions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    story.mentions.take(4).forEach { m ->
                        Text(
                            "@${m.username}",
                            color = Color.Black,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clip(CircleShape).background(Color.White).clickable { actions.openUser(m.uid) }.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            if (group.isMine) {
                val viewers by remember(story.id) { viewModel.viewers(story.id) }.collectAsStateWithLifecycle(emptyList())
                Row(
                    Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable { showViewers = true }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_visibility), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (viewers.isEmpty()) "No views yet" else "Seen by ${viewers.size}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
                if (showViewers) {
                    AlertDialog(
                        onDismissRequest = { showViewers = false },
                        title = { Text("Seen by") },
                        text = {
                            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                                if (viewers.isEmpty()) item { Text("Nobody yet. Views show up here as people watch.") }
                                items(viewers, key = { it.uid }) { v ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { showViewers = false; actions.openUser(v.uid) }.padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Avatar(v.avatarUrl, v.displayName, size = 34.dp)
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(v.displayName, style = MaterialTheme.typography.titleSmall)
                                            Text("@${v.username}", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showViewers = false }) { Text("Close") } },
                    )
                }
            } else if (story.allowReplies) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = reply,
                        onValueChange = { reply = it.take(500) },
                        placeholder = { Text("Reply to @${story.author.username}…", color = Color.White.copy(alpha = 0.7f)) },
                        singleLine = true,
                        shape = CircleShape,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { viewModel.reply(story, reply); reply = ""; replying = false }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.6f),
                            cursorColor = Color.White,
                        ),
                        modifier = Modifier.weight(1f).onFocusChanged { replying = it.isFocused },
                    )
                    if (reply.isNotBlank()) {
                        IconButton(onClick = { viewModel.reply(story, reply); reply = "" }) {
                            Icon(painterResource(R.drawable.ic_send), contentDescription = "Send reply", tint = Color.White)
                        }
                    }
                }
            }
        }
        if (reporting) ReportDialog(ReportTarget.Story, story.id, story.author.uid, onDismiss = { reporting = false })
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete this story?") },
                text = { Text("It disappears for everyone right away.") },
                confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(story) { onClose() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
            )
        }
    }
}
