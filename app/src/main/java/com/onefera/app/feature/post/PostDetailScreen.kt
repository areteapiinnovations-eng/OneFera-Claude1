package com.onefera.app.feature.post

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.navigation.toRoute
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.ScreenHeader
import com.onefera.app.core.media.rememberVideoPlayer
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.model.Comment
import com.onefera.app.data.model.PostEdit
import com.onefera.app.data.social.PostRepository
import com.onefera.app.navigation.PostDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.onefera.app.data.moderation.ModerationRepository
import javax.inject.Inject

data class PostDetailUiState(
    val loading: Boolean = true,
    val item: FeedItem? = null,
    val comments: List<Comment> = emptyList(),
    val myUid: String? = null,
    val draft: String = "",
    val sending: Boolean = false,
    val error: String? = null,
    val deleted: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class PostDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val posts: PostRepository,
    private val actions: PostActions,
    auth: AuthRepository,
    moderation: ModerationRepository,
) : ViewModel() {
    private val blocked = moderation.blockedIds()
    private val postId = savedStateHandle.toRoute<PostDetailRoute>().postId
    private val form = MutableStateFlow(PostDetailUiState())

    val state: StateFlow<PostDetailUiState> = combine(
        posts.post(postId).map { listOfNotNull(it) }.withInteractions(posts, blocked).map { it.firstOrNull() },
        combine(posts.comments(postId), blocked) { list, hidden -> list.filter { it.author.uid !in hidden } },
        auth.session.map { (it as? SessionState.SignedIn)?.uid },
        form,
    ) { item, comments, uid, f ->
        f.copy(loading = false, item = item, comments = comments, myUid = uid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PostDetailUiState())

    private fun current() = state.value.item

    fun toggleLike() = current()?.let { viewModelScope.launch { actions.toggleLike(it) } }
    fun like() = current()?.let { viewModelScope.launch { actions.like(it) } }
    fun toggleSave() = current()?.let { viewModelScope.launch { actions.toggleSave(it) } }
    fun delete() = current()?.let { item ->
        viewModelScope.launch { actions.delete(item).onSuccess { form.update { it.copy(deleted = true) } } }
    }

    fun edit(edit: PostEdit, onDone: () -> Unit) = current()?.let { item ->
        if (form.value.saving) return@let
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            actions.edit(item, edit)
                .onSuccess { form.update { it.copy(saving = false) }; onDone() }
                .onFailure { e -> form.update { it.copy(saving = false, message = e.message ?: "Couldn't save your changes") } }
        }
    }

    fun toggleComments() = current()?.let { item ->
        viewModelScope.launch { actions.toggleComments(item).onFailure { e -> form.update { it.copy(message = e.message) } } }
    }

    fun commentAction(result: suspend () -> Result<Unit>) = viewModelScope.launch {
        result().onFailure { e -> form.update { it.copy(message = e.message ?: "Something went wrong") } }
    }

    fun editComment(comment: Comment, text: String) = commentAction { posts.editComment(comment, text) }
    fun deleteComment(comment: Comment) = commentAction { posts.deleteComment(comment) }
    fun messageShown() = form.update { it.copy(message = null) }

    fun onDraftChange(v: String) = form.update { it.copy(draft = v.take(500), error = null) }

    fun send(text: String = form.value.draft) {
        val post = current()?.post ?: return
        if (text.isBlank() || form.value.sending) return
        form.update { it.copy(sending = true) }
        viewModelScope.launch {
            posts.addComment(post, text)
                .onSuccess { form.update { it.copy(sending = false, draft = "") } }
                .onFailure { e -> form.update { it.copy(sending = false, error = e.message) } }
        }
    }
}

@Composable
fun PostDetailScreen(onBack: () -> Unit, viewModel: PostDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val player = rememberVideoPlayer(loop = true)
    var playing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(state.message) {
        state.message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show(); viewModel.messageShown() }
    }
    LaunchedEffect(playing) {
        val url = state.item?.post?.cover?.url
        if (playing && url != null) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.play()
        } else {
            player.pause()
        }
    }

    AuroraBackground(Modifier.fillMaxSize(), intensity = 0.45f) {
        Column(Modifier.fillMaxSize().imePadding()) {
            ScreenHeader(title = "Post", onBack = onBack)
            val item = state.item
            when {
                state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                item == null -> EmptyState(R.drawable.ic_info, "Post not available", "It may have been deleted or is private.", Modifier.fillMaxWidth())
                else -> {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        item(key = "post") {
                            PostCard(
                                item = item,
                                isMine = item.post.authorId == state.myUid,
                                activePlayer = if (playing) player else null,
                                callbacks = PostCardCallbacks(
                                    onLike = { viewModel.toggleLike() },
                                    onDoubleTapLike = { viewModel.like() },
                                    onSave = { viewModel.toggleSave() },
                                    onComments = {},
                                    onShare = { sharePost(context, item.post) },
                                    onDelete = { confirmDelete = true },
                                    onPlayVideo = { playing = !playing },
                                    onEdit = { editing = true },
                                    onToggleComments = { viewModel.toggleComments() },
                                ),
                            )
                        }
                        item(key = "comments-title") {
                            Text(
                                if (state.comments.isEmpty()) "No comments yet, start the convo ✨" else "Comments",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(state.comments, key = { it.id }) { comment ->
                            val actions = LocalAppActions.current
                            CommentRow(
                                comment = comment,
                                myUid = state.myUid,
                                postAuthorId = item.post.authorId,
                                onAuthor = { actions.openUser(comment.author.uid) },
                                onEdit = { text -> viewModel.editComment(comment, text) },
                                onDelete = { viewModel.deleteComment(comment) },
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                    Column(Modifier.navigationBarsPadding()) {
                        if (item.post.commentsOff) {
                            Text(
                                "Comments are off for this post",
                                style = MaterialTheme.typography.bodyMedium,
                                color = com.onefera.app.core.designsystem.theme.OneFeraTheme.extras.muted,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        } else CommentComposer(
                            draft = state.draft,
                            sending = state.sending,
                            error = state.error,
                            onDraftChange = viewModel::onDraftChange,
                            onSend = { viewModel.send() },
                            onQuick = { viewModel.send(it) },
                        )
                    }
                }
            }
        }
    }

    val editingItem = state.item
    if (editing && editingItem != null) {
        EditPostDialog(
            post = editingItem.post,
            saving = state.saving,
            onSave = { edit -> viewModel.edit(edit) { editing = false } },
            onDismiss = { editing = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this post?") },
            text = { Text("It disappears for everyone. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}
