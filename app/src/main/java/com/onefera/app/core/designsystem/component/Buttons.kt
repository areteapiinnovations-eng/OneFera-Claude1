package com.onefera.app.core.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.onefera.app.core.designsystem.theme.OneFeraTheme

private val ButtonShape = RoundedCornerShape(18.dp)

/** Primary call-to-action: full-width pill filled with the active skin gradient. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    @DrawableRes trailingIcon: Int? = null,
) {
    val extras = OneFeraTheme.extras
    val interaction = remember { MutableInteractionSource() }
    val active = enabled && !loading
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .pressScale(interaction)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(ButtonShape)
            .background(extras.horizontalGradient())
            .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = extras.onGradient, strokeWidth = 2.5.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.labelLarge, color = extras.onGradient)
                if (trailingIcon != null) {
                    Icon(painterResource(trailingIcon), contentDescription = null, tint = extras.onGradient, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Secondary action: translucent "glass" pill with a hairline border. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes leadingIcon: Int? = null,
    height: Dp = 56.dp,
) {
    val extras = OneFeraTheme.extras
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(height)
            .pressScale(interaction)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(ButtonShape)
            .background(extras.glass)
            .border(BorderStroke(1.dp, extras.glassBorder), ButtonShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (leadingIcon != null) {
            Icon(painterResource(leadingIcon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Round icon button, either glass or filled with the gradient. */
@Composable
fun CircleIconButton(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    size: Dp = 40.dp,
    tint: Color = if (filled) OneFeraTheme.extras.onGradient else MaterialTheme.colorScheme.onSurface,
) {
    val extras = OneFeraTheme.extras
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .pressScale(interaction, 0.9f)
            .clip(CircleShape)
            .then(
                if (filled) {
                    Modifier.background(extras.gradientBrush())
                } else {
                    Modifier.background(extras.glass).border(1.dp, extras.glassBorder, CircleShape)
                },
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}
