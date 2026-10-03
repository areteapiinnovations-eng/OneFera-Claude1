package com.onefera.app.feature.auth

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.onefera.app.core.designsystem.component.AuroraBackground
import com.onefera.app.core.designsystem.component.GlassCard
import com.onefera.app.core.designsystem.component.rememberReducedMotion
import com.onefera.app.core.designsystem.theme.OneFeraTheme

/**
 * Background for the sign-in / sign-up screens.
 *
 * Always draws the animated aurora (zero assets). If a clip named `auth_background` is placed
 * in `app/src/main/res/raw/` (short, muted, ~720p, under 2 MB), it plays softly on top, looped
 * and muted, behind a dark scrim so text stays readable. It is skipped when the user has turned
 * system animations off.
 */
@Composable
fun AuthBackground(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    @SuppressLint("DiscouragedApi")
    val videoRes = remember { context.resources.getIdentifier("auth_background", "raw", context.packageName) }
    AuroraBackground(Modifier.fillMaxSize()) {
        if (videoRes != 0 && !reducedMotion) {
            LoopingVideo(Uri.parse("android.resource://${context.packageName}/$videoRes"))
            val scrim = MaterialTheme.colorScheme.background
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(scrim.copy(alpha = 0.55f), scrim.copy(alpha = 0.92f)))),
            )
        }
        content()
    }
}

@Composable
private fun LoopingVideo(uri: Uri) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                this.player = player
            }
        },
    )
}

/** Scrollable, keyboard-aware column used by all auth screens. */
@Composable
fun AuthColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        content = content,
    )
}

/** Shown when the app was built without Firebase config. */
@Composable
fun DemoModeNotice(lines: List<String>, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier, contentPadding = 14.dp) {
        Text("🧪 Demo mode", style = MaterialTheme.typography.titleSmall)
        lines.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = OneFeraTheme.extras.muted)
        }
    }
}

@Composable
fun FormError(message: String?) {
    if (message != null) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}
