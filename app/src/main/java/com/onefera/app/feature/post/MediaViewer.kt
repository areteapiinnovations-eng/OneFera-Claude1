package com.onefera.app.feature.post

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import coil3.compose.AsyncImage
import com.onefera.app.R
import com.onefera.app.core.media.VideoSurface
import com.onefera.app.core.media.rememberFirstFrameRendered
import com.onefera.app.core.media.rememberVideoPlayer
import com.onefera.app.data.model.MediaType
import com.onefera.app.data.model.Post
import com.onefera.app.data.model.PostMedia

/**
 * Full-screen viewer for a post's media: swipe between photos, pinch to zoom, and videos with
 * their own player (tap to pause, mute toggle). The feed's player keeps running untouched.
 */
@Composable
fun PostMediaViewer(post: Post, startIndex: Int = 0, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (post.media.size - 1).coerceAtLeast(0))) { post.media.size }
        var muted by rememberSaveable { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { post.media[it].url }) { page ->
                val media = post.media[page]
                if (media.type == MediaType.Video) {
                    FullScreenVideo(media, active = page == pager.settledPage, muted = muted)
                } else {
                    ZoomableImage(media)
                }
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back", tint = Color.White) }
                if (post.media.size > 1) {
                    Text(
                        "${pager.currentPage + 1}/${post.media.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (post.media.getOrNull(pager.currentPage)?.type == MediaType.Video) {
                    IconButton(onClick = { muted = !muted }) {
                        Icon(painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on), contentDescription = if (muted) "Unmute" else "Mute", tint = Color.White)
                    }
                } else {
                    Box(Modifier.size(48.dp))
                }
            }
            if (post.caption.isNotBlank()) {
                Text(
                    post.caption,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding().padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(media: PostMedia) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = media.url,
        contentDescription = "Photo",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(media.url) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(media.url) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                })
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

@Composable
private fun FullScreenVideo(media: PostMedia, active: Boolean, muted: Boolean) {
    val player = rememberVideoPlayer(loop = true)
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(media.url) {
        player.setMediaItem(MediaItem.fromUri(media.url))
        player.prepare()
    }
    LaunchedEffect(active, paused) { if (active && !paused) player.play() else player.pause() }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    val rendered = rememberFirstFrameRendered(player, media.url)
    Box(Modifier.fillMaxSize().pointerInput(media.url) { detectTapGestures(onTap = { paused = !paused }) }, contentAlignment = Alignment.Center) {
        VideoSurface(player, Modifier.fillMaxSize(), fill = false)
        if (!rendered) {
            AsyncImage(model = media.thumbnailUrl ?: media.url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (paused) {
            Icon(painterResource(R.drawable.ic_play), contentDescription = "Paused", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(84.dp))
        }
    }
}
