package com.onefera.app.feature.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import com.onefera.app.R
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GlassButton
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.component.SelectChip
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.media.rememberVideoPlayer
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.UserProfile
import com.onefera.app.data.model.UserSummary
import com.onefera.app.data.social.FeedScope
import com.onefera.app.feature.post.CommentsSheet
import com.onefera.app.feature.post.EditPostDialog
import com.onefera.app.feature.post.FeedItem
import com.onefera.app.feature.post.PostCard
import com.onefera.app.feature.post.PostCardCallbacks
import com.onefera.app.feature.post.PostSkeleton
import com.onefera.app.feature.post.findPost
import com.onefera.app.feature.post.sharePost

@Composable
fun FeedTab(
    profile: UserProfile?,
    isDemoMode: Boolean,
    onAddStory: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedScope by viewModel.selectedScope.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var commentsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<FeedItem?>(null) }
    var editing by remember { mutableStateOf<FeedItem?>(null) }
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    var activeVideo by remember { mutableStateOf<String?>(null) }
    val player = rememberVideoPlayer(loop = true)

    LaunchedEffect(viewModel) { viewModel.messages.collect { onMessage(it) } }

    // Play the selected video; stop it once its card scrolls off screen.
    LaunchedEffect(activeVideo) {
        val post = state.items.findPost(activeVideo)
        if (post == null) {
            player.stop()
        } else {
            player.setMediaItem(MediaItem.fromUri(post.cover!!.url))
            player.prepare()
            player.play()
        }
    }
    val activeVisible by remember {
        derivedStateOf { activeVideo == null || listState.layoutInfo.visibleItemsInfo.any { it.key == activeVideo } }
    }
    LaunchedEffect(activeVisible) { if (!activeVisible) activeVideo = null }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "stories") {
            StoriesRow(
                me = profile,
                groups = state.stories,
                seenIds = state.seenStoryIds,
                onAddStory = onAddStory,
                onOpen = { actions.openStories(it.author.uid) },
            )
        }
        item(key = "scope") {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectChip("For You ✨", selected = selectedScope == FeedScope.ForYou, onClick = { viewModel.setScope(FeedScope.ForYou) })
                SelectChip("Following", selected = selectedScope == FeedScope.Following, onClick = { viewModel.setScope(FeedScope.Following) })
            }
        }
        if (isDemoMode) {
            item(key = "demo") {
                GlassCard(Modifier.padding(horizontal = 16.dp), contentPadding = 12.dp) {
                    Text("🧪 Demo mode: sample creators and posts live on this phone only.", style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
                }
            }
        }
        when {
            state.loading -> items(2) { PostSkeleton(Modifier.padding(horizontal = 12.dp)) }
            state.items.isEmpty() -> item(key = "empty") {
                EmptyState(
                    icon = R.drawable.ic_home_filled,
                    title = if (state.scope == FeedScope.Following) "Your circle is quiet" else "Nothing here yet",
                    message = if (state.scope == FeedScope.Following) "Follow a few people below and their drops land here." else "Be the first to post something today ✨",
                    action = { GradientButton(text = "Create a post", onClick = { actions.createPost(false) }, modifier = Modifier.width(220.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> state.items.forEachIndexed { index, item ->
                item(key = item.post.id) {
                    PostCard(
                        item = item,
                        isMine = item.post.authorId == state.myUid,
                        activePlayer = if (activeVideo == item.post.id) player else null,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        callbacks = PostCardCallbacks(
                            onLike = { viewModel.toggleLike(item) },
                            onDoubleTapLike = { viewModel.like(item) },
                            onSave = { viewModel.toggleSave(item) },
                            onComments = { commentsFor = item.post.id },
                            onShare = { sharePost(context, item.post) },
                            onDelete = { confirmDelete = item },
                            onEdit = { editing = item },
                            onToggleComments = { viewModel.toggleComments(item) },
                            onPlayVideo = {
                                if (activeVideo == item.post.id) {
                                    if (player.isPlaying) player.pause() else player.play()
                                } else {
                                    activeVideo = item.post.id
                                }
                            },
                        ),
                    )
                }
                if (index == 2 && state.suggestions.isNotEmpty()) {
                    item(key = "suggestions") { SuggestionsRow(state.suggestions, onFollow = viewModel::follow) }
                }
            }
        }
        if (!state.loading && state.items.size in 0..2 && state.suggestions.isNotEmpty()) {
            item(key = "suggestions-bottom") { SuggestionsRow(state.suggestions, onFollow = viewModel::follow) }
        }
    }

    state.items.findPost(commentsFor)?.let { post ->
        CommentsSheet(post = post, onDismiss = { commentsFor = null })
    }
    editing?.let { item ->
        EditPostDialog(
            post = item.post,
            saving = saving,
            onSave = { edit -> viewModel.edit(item, edit) { editing = null } },
            onDismiss = { editing = null },
        )
    }
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this post?") },
            text = { Text("It disappears for everyone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item)
                    confirmDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SuggestionsRow(users: List<UserSummary>, onFollow: (UserSummary) -> Unit) {
    val actions = LocalAppActions.current
    Column {
        Text("People to follow 🙌", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(users, key = { it.uid }) { user ->
                GlassCard(Modifier.width(150.dp), contentPadding = 12.dp) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Avatar(user.avatarUrl, user.displayName, size = 64.dp, ring = true, modifier = Modifier.padding(bottom = 6.dp))
                        Text(user.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@${user.username}", style = MaterialTheme.typography.labelSmall, color = OneFeraTheme.extras.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        GlassButton(text = "View", onClick = { actions.openUser(user.uid) }, height = 34.dp, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        GradientButtonSmall(text = if (user.isPrivate) "Request" else "Follow", onClick = { onFollow(user) })
                    }
                }
            }
        }
    }
}

@Composable
private fun GradientButtonSmall(text: String, onClick: () -> Unit) {
    // GradientButton fills the width; the outer height wins over its default 56 dp.
    GradientButton(text = text, onClick = onClick, modifier = Modifier.height(34.dp))
}
