package com.onefera.app.feature.post

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.onefera.app.feature.shop.ProductTagChip
import com.onefera.app.feature.moderation.BlockDialog
import com.onefera.app.feature.moderation.ReportDialog
import com.onefera.app.data.moderation.ReportTarget
import androidx.compose.foundation.layout.widthIn
import com.onefera.app.R
import com.onefera.app.core.common.compactCount
import com.onefera.app.core.common.timeAgo
import com.onefera.app.core.designsystem.component.Avatar
import com.onefera.app.core.designsystem.component.gradientTint
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.StatusColors
import com.onefera.app.core.media.VideoSurface
import com.onefera.app.core.media.rememberFirstFrameRendered
import com.onefera.app.core.navigation.LocalAppActions
import com.onefera.app.data.model.MediaType
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.PostMedia
import kotlinx.coroutines.launch

/** Callbacks for a post card. */
data class PostCardCallbacks(
    val onLike: () -> Unit,
    val onDoubleTapLike: () -> Unit,
    val onSave: () -> Unit,
    val onComments: () -> Unit,
    val onShare: () -> Unit,
    val onDelete: () -> Unit,
    val onPlayVideo: () -> Unit,
    /** Author only: edit caption/location. */
    val onEdit: () -> Unit = {},
    /** Author only: switch comments on or off. */
    val onToggleComments: () -> Unit = {},
)

@Composable
fun PostCard(
    item: FeedItem,
    isMine: Boolean,
    callbacks: PostCardCallbacks,
    modifier: Modifier = Modifier,
    /** Non-null when this card's video is the one currently playing. */
    activePlayer: Player? = null,
) {
    val post = item.post
    val extras = OneFeraTheme.extras
    val actions = LocalAppActions.current
    var viewerPage by remember(post.id) { mutableStateOf<Int?>(null) }
    viewerPage?.let { page -> PostMediaViewer(post, startIndex = page, onDismiss = { viewerPage = null }) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(if (extras.isDark) extras.glass else MaterialTheme.colorScheme.surface),
    ) {
        PostHeader(post, isMine, callbacks)
        if (post.media.isNotEmpty()) {
            PostMediaView(
                post,
                activePlayer,
                onDoubleTap = callbacks.onDoubleTapLike,
                onPlayVideo = callbacks.onPlayVideo,
                onOpenFullScreen = { viewerPage = it },
            )
        }
        if (post.products.isNotEmpty()) {
            ShopThePost(post, onOpen = actions.openProduct)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            LikeButton(liked = item.liked, onClick = callbacks.onLike)
            if (!post.commentsOff) {
                IconButton(onClick = callbacks.onComments) {
                    Icon(painterResource(R.drawable.ic_chat), contentDescription = "Comments", modifier = Modifier.size(24.dp))
                }
            }
            IconButton(onClick = callbacks.onShare) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = "Share", modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = callbacks.onSave) {
                if (item.saved) {
                    Icon(
                        painterResource(R.drawable.ic_bookmark_filled),
                        contentDescription = "Remove from saved",
                        modifier = Modifier.size(24.dp).gradientTint(extras.gradientBrush()),
                    )
                } else {
                    Icon(painterResource(R.drawable.ic_bookmark), contentDescription = "Save", modifier = Modifier.size(24.dp))
                }
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (post.likeCount > 0) {
                Text("${compactCount(post.likeCount)} ${if (post.likeCount == 1) "like" else "likes"}", style = MaterialTheme.typography.titleSmall)
            }
            if (post.caption.isNotBlank()) {
                Caption(post, onTag = actions.openTag, onAuthor = { actions.openUser(post.authorId) })
            }
            when {
                post.commentsOff -> Text("Comments are off", style = MaterialTheme.typography.bodyMedium, color = extras.muted)
                post.commentCount > 0 -> Text(
                    if (post.commentCount == 1) "View 1 comment" else "View all ${post.commentCount} comments",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.muted,
                    modifier = Modifier.clickable(onClick = callbacks.onComments),
                )
                // Call to action on a quiet post: one tap to the comment box.
                else -> Text(
                    "Add a comment…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extras.muted,
                    modifier = Modifier.clickable(onClickLabel = "Add a comment", onClick = callbacks.onComments),
                )
            }
            Text(
                timeAgo(post.createdAt) + if (post.edited) " · edited" else "",
                style = MaterialTheme.typography.labelSmall,
                color = extras.muted,
            )
        }
    }
}

/** "Shop this post": tagged products, one tap from the product page. */
@Composable
private fun ShopThePost(post: Post, onOpen: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 10.dp),
    ) {
        items(post.products.size) { i ->
            val product = post.products[i]
            ProductTagChip(product, onClick = { onOpen(product.id) }, modifier = Modifier.widthIn(max = 280.dp))
        }
    }
}

@Composable
private fun PostHeader(post: Post, isMine: Boolean, callbacks: PostCardCallbacks) {
    val onShare = callbacks.onShare
    val actions = LocalAppActions.current
    val extras = OneFeraTheme.extras
    var menu by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var blocking by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { actions.openUser(post.authorId) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(post.author.avatarUrl, post.author.displayName, size = 38.dp, ring = true)
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(post.author.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (post.author.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(painterResource(R.drawable.ic_badge_filled), contentDescription = "Verified", modifier = Modifier.size(14.dp).gradientTint(extras.gradientBrush()))
                    }
                }
                val sub = listOfNotNull(post.location.takeIf { it.isNotBlank() }, post.soundName.takeIf { it.isNotBlank() && post.location.isBlank() })
                    .firstOrNull() ?: "@${post.author.username}"
                Text(sub, style = MaterialTheme.typography.labelMedium, color = extras.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more), contentDescription = "Post options", modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text("View profile") }, onClick = { menu = false; actions.openUser(post.authorId) })
                if (isMine) {
                    DropdownMenuItem(
                        text = { Text("Edit post") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_edit), null, modifier = Modifier.size(20.dp)) },
                        onClick = { menu = false; callbacks.onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (post.commentsOff) "Turn on comments" else "Turn off comments") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_chat), null, modifier = Modifier.size(20.dp)) },
                        onClick = { menu = false; callbacks.onToggleComments() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete post", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) },
                        onClick = { menu = false; callbacks.onDelete() },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Report", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; reporting = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Block @${post.author.username}") },
                        onClick = { menu = false; blocking = true },
                    )
                }
            }
        }
    }
    if (reporting) ReportDialog(ReportTarget.Post, post.id, post.authorId, onDismiss = { reporting = false })
    if (blocking) BlockDialog(post.author, onDismiss = { blocking = false })
}

@Composable
private fun PostMediaView(post: Post, activePlayer: Player?, onDoubleTap: () -> Unit, onPlayVideo: () -> Unit, onOpenFullScreen: (Int) -> Unit) {
    val ratio = (post.cover?.aspectRatio ?: 0.8f).coerceIn(0.8f, 1.91f)
    val heart = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { post.media.size }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerInput(post.id) {
                detectTapGestures(
                    onDoubleTap = {
                        onDoubleTap()
                        scope.launch {
                            heart.snapTo(0f)
                            heart.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                            heart.animateTo(0f, tween(300, delayMillis = 350))
                        }
                    },
                    // Photos open full screen on tap; videos play inline and open full screen from the corner button.
                    onTap = { if (post.isVideo) onPlayVideo() else onOpenFullScreen(pager.currentPage) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (post.media.size > 1) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                MediaImage(post.media[page])
            }
            Text(
                "${pager.currentPage + 1}/${post.media.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Row(Modifier.align(Alignment.BottomCenter).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(post.media.size) { i ->
                    Box(
                        Modifier.size(if (i == pager.currentPage) 7.dp else 5.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = if (i == pager.currentPage) 1f else 0.5f)),
                    )
                }
            }
        } else {
            val media = post.media.first()
            if (media.type == MediaType.Video && activePlayer != null) {
                val showVideo = rememberFirstFrameRendered(activePlayer, post.id)
                VideoSurface(activePlayer, Modifier.fillMaxSize())
                if (!showVideo) MediaImage(media)
            } else {
                MediaImage(media)
                if (media.type == MediaType.Video) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                            .semantics { contentDescription = "Play video" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
        if (post.isVideo) {
            IconButton(
                onClick = { onOpenFullScreen(0) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).size(40.dp),
            ) {
                Icon(painterResource(R.drawable.ic_fullscreen), contentDescription = "Full screen", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        // Double-tap heart burst.
        Icon(
            painterResource(R.drawable.ic_heart_filled),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(110.dp)
                .graphicsLayer {
                    alpha = heart.value
                    scaleX = 0.4f + 0.6f * heart.value
                    scaleY = 0.4f + 0.6f * heart.value
                },
        )
    }
}

@Composable
fun MediaImage(media: PostMedia, modifier: Modifier = Modifier) {
    AsyncImage(
        model = media.thumbnailUrl ?: media.url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
fun LikeButton(liked: Boolean, onClick: () -> Unit, size: Int = 26) {
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(liked) {
        if (liked) {
            bounce.snapTo(0.6f)
            bounce.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium))
        }
    }
    IconButton(onClick = onClick) {
        Icon(
            painterResource(if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart),
            contentDescription = if (liked) "Unlike" else "Like",
            tint = if (liked) StatusColors.Live else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(size.dp).scale(bounce.value),
        )
    }
}

@Composable
private fun Caption(post: Post, onTag: (String) -> Unit, onAuthor: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val text = buildAnnotatedString {
        withLink(LinkAnnotation.Clickable("author") { onAuthor() }) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(post.author.username) }
        }
        append("  ")
        var last = 0
        Regex("#[\\p{L}\\p{N}_]+").findAll(post.caption).forEach { match ->
            append(post.caption.substring(last, match.range.first))
            val tag = match.value.removePrefix("#").lowercase()
            withLink(LinkAnnotation.Clickable("tag-$tag", TextLinkStyles(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold))) { onTag(tag) }) {
                append(match.value)
            }
            last = match.range.last + 1
        }
        append(post.caption.substring(last))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable { expanded = !expanded },
    )
}

@Composable
fun PostSkeleton(modifier: Modifier = Modifier) {
    val extras = OneFeraTheme.extras
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(extras.glass).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(extras.glassBorder))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.height(12.dp).width(120.dp).clip(CircleShape).background(extras.glassBorder))
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(extras.glassBorder))
    }
}
