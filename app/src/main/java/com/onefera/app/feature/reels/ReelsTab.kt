package com.onefera.app.feature.reels

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import coil3.compose.AsyncImage
import com.onefera.app.R
import com.onefera.app.core.common.compactCount
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.EmptyState
import com.onefera.app.core.designsystem.component.GradientButton
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.core.media.VideoSurface
import com.onefera.app.core.media.rememberFirstFrameRendered
import com.onefera.app.core.media.rememberVideoPlayer
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.feature.shop.ProductTagChip
import com.onefera.app.data.auth.AuthRepository
import com.onefera.app.data.auth.SessionState
import com.onefera.app.data.model.MediaType
import com.onefera.app.data.social.PostRepository
import com.onefera.app.data.social.SocialRepository
import com.onefera.app.feature.post.CommentsSheet
import com.onefera.app.feature.post.FeedItem
import com.onefera.app.feature.post.PostActions
import com.onefera.app.feature.post.findPost
import com.onefera.app.feature.post.sharePost
import com.onefera.app.feature.post.withInteractions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.onefera.app.data.moderation.ModerationRepository
import javax.inject.Inject

data class ReelsUiState(
    val loading: Boolean = true,
    val reels: List<FeedItem> = emptyList(),
    val following: Set<String> = emptySet(),
    val myUid: String? = null,
)

@HiltViewModel
class ReelsViewModel @Inject constructor(
    posts: PostRepository,
    private val social: SocialRepository,
    private val actions: PostActions,
    auth: AuthRepository,
    moderation: ModerationRepository,
) : ViewModel() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val state: StateFlow<ReelsUiState> = combine(
        posts.reels().withInteractions(posts, moderation.blockedIds()),
        social.followingIds(),
        auth.session.map { (it as? SessionState.SignedIn)?.uid },
    ) { reels, following, uid ->
        ReelsUiState(false, reels.filter { it.post.cover?.type == MediaType.Video }, following, uid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReelsUiState())

    fun toggleLike(item: FeedItem) = viewModelScope.launch { actions.toggleLike(item) }
    fun like(item: FeedItem) = viewModelScope.launch { actions.like(item) }
    fun toggleSave(item: FeedItem) = viewModelScope.launch {
        actions.toggleSave(item).onSuccess { _messages.emit(if (item.saved) "Removed from saved" else "Saved ✨") }
    }
    fun follow(item: FeedItem) = viewModelScope.launch {
        social.follow(item.post.author)
            .onSuccess { _messages.emit("Following @${item.post.author.username} ✨") }
            .onFailure { _messages.emit(it.message ?: "Couldn't follow right now") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReelsTab(onMessage: (String) -> Unit, viewModel: ReelsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    val player = rememberVideoPlayer(loop = true)
    var muted by rememberSaveable { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var commentsFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) { viewModel.messages.collect { onMessage(it) } }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    // Reels are always on black, so default text/icons are white in both themes.
    CompositionLocalProvider(LocalContentColor provides Color.White) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            state.reels.isEmpty() -> EmptyState(
                icon = R.drawable.ic_reels_filled,
                title = "No reels yet",
                message = "Post a vertical video and it lands right here 🎬",
                action = { GradientButton(text = "Create a reel", onClick = { actions.createPost(true) }, modifier = Modifier.width(220.dp)) },
                modifier = Modifier.align(Alignment.Center),
            )
            else -> {
                val pager = rememberPagerState { state.reels.size }
                val current = state.reels.getOrNull(pager.settledPage)
                LaunchedEffect(current?.post?.id) {
                    val url = current?.post?.cover?.url ?: return@LaunchedEffect
                    paused = false
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                    player.play()
                }
                LaunchedEffect(paused) { if (paused) player.pause() else if (current != null) player.play() }
                VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { state.reels[it].post.id }) { page ->
                    val item = state.reels[page]
                    ReelPage(
                        item = item,
                        isCurrent = page == pager.settledPage,
                        player = player,
                        paused = paused,
                        showFollow = item.post.authorId != state.myUid && item.post.authorId !in state.following,
                        onTogglePause = { paused = !paused },
                        onLike = { viewModel.toggleLike(item) },
                        onDoubleTap = { viewModel.like(item) },
                        onComments = { commentsFor = item.post.id },
                        onShare = { sharePost(context, item.post) },
                        onSave = { viewModel.toggleSave(item) },
                        onFollow = { viewModel.follow(item) },
                        onAuthor = { actions.openUser(item.post.authorId) },
                        onTag = actions.openTag,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Reels", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.weight(1f))
            RailIcon(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on, if (muted) "Unmute" else "Mute") { muted = !muted }
            Spacer(Modifier.width(8.dp))
            RailIcon(R.drawable.ic_add, "Create a reel") { actions.createPost(true) }
        }
    }
    }

    state.reels.findPost(commentsFor)?.let { post -> CommentsSheet(post = post, onDismiss = { commentsFor = null }) }
}

@Composable
private fun ReelPage(
    item: FeedItem,
    isCurrent: Boolean,
    player: androidx.media3.common.Player,
    paused: Boolean,
    showFollow: Boolean,
    onTogglePause: () -> Unit,
    onLike: () -> Unit,
    onDoubleTap: () -> Unit,
    onComments: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onFollow: () -> Unit,
    onAuthor: () -> Unit,
    onTag: (String) -> Unit,
) {
    val post = item.post
    val heart = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(post.id) {
                detectTapGestures(
                    onTap = { onTogglePause() },
                    onDoubleTap = {
                        onDoubleTap()
                        scope.launch {
                            heart.snapTo(0f)
                            heart.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                            heart.animateTo(0f, tween(300, delayMillis = 300))
                        }
                    },
                )
            },
    ) {
        AsyncImage(
            model = post.cover?.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isCurrent) {
            val showVideo = rememberFirstFrameRendered(player, post.id)
            // The poster is drawn over the video surface until the first frame has rendered.
            VideoSurface(player, Modifier.fillMaxSize())
            if (!showVideo) {
                AsyncImage(model = post.cover?.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)),
            ),
        )
        if (isCurrent && paused) {
            Icon(painterResource(R.drawable.ic_play), contentDescription = "Paused", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.Center).size(84.dp))
        }
        Icon(
            painterResource(R.drawable.ic_heart_filled),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.align(Alignment.Center).size(120.dp).graphicsLayer {
                alpha = heart.value
                scaleX = 0.4f + 0.6f * heart.value
                scaleY = 0.4f + 0.6f * heart.value
            },
        )
        // Right action rail.
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            RailAction(
                icon = if (item.liked) R.drawable.ic_heart_filled else R.drawable.ic_heart,
                label = compactCount(post.likeCount),
                tint = if (item.liked) StatusColors.Live else Color.White,
                description = if (item.liked) "Unlike" else "Like",
                onClick = onLike,
            )
            RailAction(R.drawable.ic_chat, compactCount(post.commentCount), description = "Comments", onClick = onComments)
            RailAction(R.drawable.ic_share, "Share", description = "Share", onClick = onShare)
            RailAction(if (item.saved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark, if (item.saved) "Saved" else "Save", description = "Save", onClick = onSave)
        }
        // Author, caption and sound.
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 88.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            post.products.firstOrNull()?.let { product ->
                val openProduct = LocalAppActions.current.openProduct
                ProductTagChip(product, onClick = { openProduct(product.id) }, onDark = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(post.author.avatarUrl, post.author.displayName, size = 36.dp, modifier = Modifier.clip(CircleShape).clickable(onClick = onAuthor))
                Spacer(Modifier.width(10.dp))
                Text(post.author.username, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.clickable(onClick = onAuthor))
                if (showFollow) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Follow",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                            .clickable(onClick = onFollow)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            if (post.caption.isNotBlank()) {
                Text(post.caption, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (post.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    post.tags.take(3).forEach { tag ->
                        Text("#$tag", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { onTag(tag) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_music), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    post.soundName.ifBlank { "Original audio" },
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.width(200.dp).basicMarquee(),
                )
            }
        }
    }
}

@Composable
private fun RailAction(icon: Int, label: String, description: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(CircleShape).clickable(onClick = onClick)) {
        Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RailIcon(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
