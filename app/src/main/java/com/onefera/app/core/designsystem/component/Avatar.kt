package com.onefera.app.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.onefera.app.core.designsystem.theme.OneFeraTheme
import com.onefera.app.core.designsystem.theme.Sora

/**
 * Circular avatar. Shows the photo when there is one, otherwise the user's initials on the
 * skin gradient. [ring] adds the gradient "story ring".
 */
@Composable
fun Avatar(
    imageUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    ring: Boolean = false,
) {
    val extras = OneFeraTheme.extras
    val ringWidth = if (ring) (size.value * 0.05f).coerceAtLeast(2f).dp else 0.dp
    Box(
        modifier = modifier
            .size(size)
            .then(if (ring) Modifier.border(ringWidth, extras.gradientBrush(), CircleShape) else Modifier)
            .padding(if (ring) ringWidth * 2 else 0.dp)
            .clip(CircleShape)
            .background(extras.gradientBrush()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            color = extras.onGradient,
            fontFamily = Sora,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.34f).sp,
            style = MaterialTheme.typography.titleMedium,
        )
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "$name's photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
    }
}

internal fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "✦" }
