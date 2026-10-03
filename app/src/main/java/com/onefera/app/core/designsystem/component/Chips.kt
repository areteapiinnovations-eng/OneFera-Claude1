package com.onefera.app.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.onefera.app.core.designsystem.theme.OneFeraTheme

private val PillShape = RoundedCornerShape(50)

/** Small glass pill with an optional icon, e.g. the 🔥 streak and ✦ aura counters. */
@Composable
fun InfoPill(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val extras = OneFeraTheme.extras
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(extras.glass)
            .border(1.dp, extras.glassBorder, PillShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            val iconModifier = Modifier.size(16.dp)
            if (iconTint != null) {
                Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = iconModifier)
            } else {
                Icon(painterResource(icon), contentDescription = null, modifier = iconModifier.gradientTint(extras.gradientBrush()))
            }
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Selectable chip: gradient fill when selected, glass when not. */
@Composable
fun SelectChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = OneFeraTheme.extras
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) extras.onGradient else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .clip(PillShape)
            .then(
                if (selected) {
                    Modifier.background(extras.horizontalGradient())
                } else {
                    Modifier.background(extras.glass).border(1.dp, extras.glassBorder, PillShape)
                },
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Gradient-filled label, e.g. "✨ main character era" or "SOON". */
@Composable
fun GradientTag(text: String, modifier: Modifier = Modifier) {
    val extras = OneFeraTheme.extras
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = extras.onGradient,
        modifier = modifier
            .clip(PillShape)
            .background(extras.horizontalGradient())
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
