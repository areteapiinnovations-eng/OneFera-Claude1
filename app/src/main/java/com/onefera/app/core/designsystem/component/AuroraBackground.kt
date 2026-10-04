package com.onefera.app.core.designsystem.component

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import kotlin.math.cos
import kotlin.math.sin

/** True when the user has turned animations off in system settings. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Slowly drifting aurora glow in the active skin's colours. It is drawn with a few radial
 * gradients (no bitmaps, no video), so it costs almost nothing in APK size or battery.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val extras = OneFeraTheme.extras
    val colors = extras.gradient
    val base = MaterialTheme.colorScheme.background
    val glow = if (extras.isDark) 0.34f * intensity else 0.24f * intensity
    val reducedMotion = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase = if (reducedMotion) {
        0.6f
    } else {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(durationMillis = 22_000, easing = LinearEasing), RepeatMode.Restart),
            label = "auroraPhase",
        ).value
    }

    Box(
        modifier = modifier
            .background(base)
            .drawBehind {
                val w = size.width
                val h = size.height
                val r = maxOf(w, h) * 0.75f
                fun blob(color: Color, cx: Float, cy: Float, radius: Float) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color.copy(alpha = glow), color.copy(alpha = 0f)),
                            center = Offset(cx, cy),
                            radius = radius,
                        ),
                        radius = radius,
                        center = Offset(cx, cy),
                    )
                }
                blob(colors.first(), w * (0.15f + 0.12f * cos(phase)), h * (0.12f + 0.08f * sin(phase)), r)
                blob(colors[colors.size / 2], w * (0.95f + 0.10f * sin(phase * 1.3f)), h * (0.45f + 0.10f * cos(phase)), r * 0.85f)
                blob(colors.last(), w * (0.30f + 0.15f * sin(phase * 0.7f)), h * (1.02f + 0.06f * cos(phase * 1.1f)), r * 0.9f)
            },
        content = content,
    )
}
