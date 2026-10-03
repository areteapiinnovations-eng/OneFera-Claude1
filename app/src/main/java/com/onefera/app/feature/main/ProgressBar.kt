package com.onefera.app.feature.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.onefera.app.core.designsystem.theme.OneFeraTheme

/** Thin rounded progress bar filled with the skin gradient. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val extras = OneFeraTheme.extras
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(extras.glassBorder)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(CircleShape)
                .background(extras.horizontalGradient()),
        )
    }
}
