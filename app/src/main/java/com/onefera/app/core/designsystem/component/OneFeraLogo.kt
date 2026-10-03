package com.onefera.app.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.Sora

/**
 * The OneFera mark (chat bubble linked to a shopping tag, plus a spark) drawn as vector paths,
 * so it stays crisp at any size and can be recoloured to match the active theme.
 */
private object MarkGeometry {
    const val WIDTH = 1458f
    const val HEIGHT = 1212f
    const val STROKE = 120f
    val strokes = listOf(
        "M370,762 L283,762 L195,850 L150,850 L150,315 A165,165 0 0 1 315,150 L665,150 " +
            "A165,165 0 0 1 830,315 L830,588 A174,174 0 0 1 660,762",
        "M690,462 L650,462 A135,135 0 0 0 515,597 L515,935 A125,125 0 0 0 640,1060 L1012,1060 L1157,915",
        "M982,462 L1010,462 L1157,609",
    )
    const val TAG = "M1115,485 L1352.4,719.6 A60,60 0 0 1 1352.4,804.4 L1115,1045 Z " +
        "M1191,762 A62,62 0 1 0 1315,762 A62,62 0 1 0 1191,762 Z"
    const val SPARKLE = "M1135,128 Q1158,218 1247,243 Q1158,268 1135,357 Q1112,268 1023,243 Q1112,218 1135,128 Z"
}

@Composable
fun OneFeraMark(
    modifier: Modifier = Modifier,
    colors: List<Color> = OneFeraTheme.extras.logoGradient,
    sparkleColor: Color = Color(0xFF8B7BFF),
) {
    val paths = remember {
        val parser = PathParser()
        Triple(
            MarkGeometry.strokes.map { parser.parsePathString(it).toPath() },
            parser.parsePathString(MarkGeometry.TAG).toPath().apply { fillType = PathFillType.EvenOdd },
            parser.parsePathString(MarkGeometry.SPARKLE).toPath(),
        )
    }
    Canvas(modifier.aspectRatio(MarkGeometry.WIDTH / MarkGeometry.HEIGHT)) {
        val brush = Brush.linearGradient(colors, start = Offset(90f, 90f), end = Offset(1368f, 1120f))
        val factor = size.width / MarkGeometry.WIDTH
        scale(factor, factor, pivot = Offset.Zero) {
            val stroke = Stroke(width = MarkGeometry.STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
            paths.first.forEach { drawPath(it, brush, style = stroke) }
            drawPath(paths.second, brush)
            drawPath(paths.third, sparkleColor)
        }
    }
}

/** Horizontal lock-up: mark + "OneFera" wordmark. */
@Composable
fun OneFeraWordmark(
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    textColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(height * 0.28f),
    ) {
        OneFeraMark(Modifier.height(height))
        Text(
            text = "OneFera",
            color = textColor,
            style = TextStyle(
                fontFamily = Sora,
                fontWeight = FontWeight.Bold,
                fontSize = (height.value * 0.78f).sp,
                letterSpacing = (-0.02).em,
            ),
        )
    }
}

/** "CONNECT. SHOP. SELL." tagline in the wide-tracked brand style. */
@Composable
fun OneFeraTagline(modifier: Modifier = Modifier, color: Color = OneFeraTheme.extras.muted) {
    Text(
        text = "CONNECT. SHOP. SELL.",
        modifier = modifier,
        color = color,
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.32.em, fontWeight = FontWeight.Medium),
    )
}
