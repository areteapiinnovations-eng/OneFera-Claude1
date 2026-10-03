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
import javax.inject.Inject

data class CommentsUiState(
    val loading: Boolean = true,
    val comments: List<Comment> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CommentsViewModel @Inject constructor(private val posts: PostRepository) : ViewModel() {
    private val post = MutableStateFlow<Post?>(null)
    private val form = MutableStateFlow(CommentsUiState())

    val state: StateFlow<CommentsUiState> = post.filterNotNull()
        .flatMapLatest { p -> posts.comments(p.id) }
        .let { commentsFlow ->
            kotlinx.coroutines.flow.combine(commentsFlow, form) { list, f -> f.copy(loading = false, comments = list) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CommentsUiState())

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
                    CommentList(state.comments, onDismiss)
                }
            }
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

@Composable
fun CommentList(comments: List<Comment>, onNavigate: () -> Unit = {}, modifier: Modifier = Modifier) {
    val actions = LocalAppActions.current
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(comments, key = { it.id }) { comment ->
            CommentRow(comment) {
                onNavigate()
                actions.openUser(comment.author.uid)
            }
        }
    }
}

@Composable
fun CommentRow(comment: Comment, onAuthor: () -> Unit) {
    val extras = OneFeraTheme.extras
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Avatar(comment.author.avatarUrl, comment.author.displayName, size = 34.dp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onAuthor))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.author.username, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.clickable(onClick = onAuthor))
                Spacer(Modifier.width(6.dp))
                Text(timeAgo(comment.createdAt), style = MaterialTheme.typography.labelSmall, color = extras.muted)
            }
            Text(comment.text, style = MaterialTheme.typography.bodyMedium)
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
