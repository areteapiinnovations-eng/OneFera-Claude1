package com.onefera.app.feature.post

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import com.onefera.app.data.firebase.uidFlow
import com.onefera.app.data.auth.AuthRepository
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.onefera.app.core.designsystem.component.OneFeraTextField
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.Comment
import com.onefera.app.data.model.Post
import com.onefera.app.data.social.PostRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.onefera.app.data.moderation.ModerationRepository
import com.onefera.app.feature.moderation.ReportDialog
import com.onefera.app.data.moderation.ReportTarget
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import javax.inject.Inject

data class CommentsUiState(
    val loading: Boolean = true,
    val comments: List<Comment> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
    val error: String? = null,
    val myUid: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CommentsViewModel @Inject constructor(
    private val posts: PostRepository,
    moderation: ModerationRepository,
    auth: AuthRepository,
) : ViewModel() {
    private val post = MutableStateFlow<Post?>(null)
    private val form = MutableStateFlow(CommentsUiState())

    val state: StateFlow<CommentsUiState> = post.filterNotNull()
        .flatMapLatest { p -> kotlinx.coroutines.flow.combine(posts.comments(p.id), moderation.blockedIds()) { list, hidden -> list.filter { it.author.uid !in hidden } } }
        .let { commentsFlow ->
            kotlinx.coroutines.flow.combine(commentsFlow, form, auth.uidFlow()) { list, f, uid -> f.copy(loading = false, comments = list, myUid = uid) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommentsUiState())

    fun editComment(comment: Comment, text: String) = viewModelScope.launch {
        posts.editComment(comment, text).onFailure { e -> form.update { it.copy(error = e.message) } }
    }

    fun deleteComment(comment: Comment) = viewModelScope.launch {
        posts.deleteComment(comment).onFailure { e -> form.update { it.copy(error = e.message) } }
    }

    fun bind(target: Post) {
        if (post.value?.id != target.id) post.value = target
    }

    fun onDraftChange(text: String) = form.update { it.copy(draft = text.take(500), error = null) }

    fun send(text: String = form.value.draft) {
        val target = post.value ?: return
        if (text.isBlank() || form.value.sending) return
        form.update { it.copy(sending = true) }
        viewModelScope.launch {
            posts.addComment(target, text)
                .onSuccess { form.update { it.copy(sending = false, draft = "") } }
                .onFailure { e -> form.update { it.copy(sending = false, error = e.message) } }
        }
    }
}

private val quickReactions = listOf("🔥", "😍", "💯", "😂", "👏", "✨", "🙌", "no cap 🔥", "slay 💅")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(post: Post, onDismiss: () -> Unit) {
    val viewModel: CommentsViewModel = hiltViewModel(key = "comments-${post.id}")
    LaunchedEffect(post.id) { viewModel.bind(post) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxHeight(0.85f).imePadding().navigationBarsPadding()) {
            Text(
                "Comments",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
            )
            HorizontalDivider(color = OneFeraTheme.extras.glassBorder)
            Box(Modifier.weight(1f)) {
                if (!state.loading && state.comments.isEmpty()) {
                    EmptyState(
                        icon = R.drawable.ic_chat,
                        title = "No comments yet",
                        message = "Be the first to drop a vibe ✨",
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    val actions = LocalAppActions.current
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(state.comments, key = { it.id }) { comment ->
                            CommentRow(
                                comment = comment,
                                myUid = state.myUid,
                                postAuthorId = post.authorId,
                                onAuthor = { onDismiss(); actions.openUser(comment.author.uid) },
                                onEdit = { text -> viewModel.editComment(comment, text) },
                                onDelete = { viewModel.deleteComment(comment) },
                            )
                        }
                    }
                }
            }
            if (post.commentsOff) {
                Text(
                    "Comments are off for this post",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OneFeraTheme.extras.muted,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            } else {
                CommentComposer(
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

/**
 * One comment. Long-press opens its actions: the author can edit or delete it, the post's author
 * can delete it (moderating their own post), everyone else can report it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CommentRow(
    comment: Comment,
    myUid: String?,
    postAuthorId: String,
    onAuthor: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val extras = OneFeraTheme.extras
    val mine = myUid != null && comment.author.uid == myUid
    val canDelete = mine || (myUid != null && postAuthorId == myUid)
    var menu by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (reporting) ReportDialog(ReportTarget.Comment, "${comment.postId}/${comment.id}", comment.author.uid, onDismiss = { reporting = false })
    if (editing) {
        var text by remember(comment.id) { mutableStateOf(comment.text) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Edit comment") },
            text = { OneFeraTextField(value = text, onValueChange = { text = it.take(500) }, label = "Comment", singleLine = false) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank() && text.trim() != comment.text, onClick = { editing = false; onEdit(text) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete comment?") },
            text = { Text(if (mine) "It disappears for everyone." else "It's removed from your post for everyone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = "Comment options")
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Avatar(comment.author.avatarUrl, comment.author.displayName, size = 34.dp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onAuthor))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(comment.author.username, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.clickable(onClick = onAuthor))
                    Spacer(Modifier.width(6.dp))
                    Text(timeAgo(comment.createdAt) + if (comment.edited) " · edited" else "", style = MaterialTheme.typography.labelSmall, color = extras.muted)
                }
                Text(comment.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (mine) DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; editing = true })
            if (canDelete) DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
            if (!mine) DropdownMenuItem(text = { Text("Report") }, onClick = { menu = false; reporting = true })
        }
    }
}

@Composable
fun CommentComposer(
    draft: String,
    sending: Boolean,
    error: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onQuick: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        HorizontalDivider(color = OneFeraTheme.extras.glassBorder)
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(quickReactions) { reaction ->
                com.onefera.app.core.designsystem.component.SelectChip(text = reaction, selected = false, onClick = { onQuick(reaction) })
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OneFeraTextField(
                value = draft,
                onValueChange = onDraftChange,
                label = "Add a comment…",
                error = error,
                singleLine = false,
                imeAction = ImeAction.Send,
                onImeAction = onSend,
                modifier = Modifier.weight(1f).heightIn(max = 140.dp),
            )
            Spacer(Modifier.width(8.dp))
            CircleIconButton(
                icon = R.drawable.ic_send,
                contentDescription = "Send comment",
                onClick = onSend,
                filled = draft.isNotBlank() && !sending,
                size = 46.dp,
            )
        }
    }
}

/** Keeps the [Post] referenced by an open comments sheet up to date. */
fun List<FeedItem>.findPost(id: String?): Post? = firstOrNull { it.post.id == id }?.post
