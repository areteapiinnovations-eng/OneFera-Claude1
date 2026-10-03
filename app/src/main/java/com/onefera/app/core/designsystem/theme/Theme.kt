package com.onefera.app.core.designsystem.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** OneFera-specific design tokens that Material's ColorScheme has no slot for. */
@Immutable
data class OneFeraExtras(
    val skin: ThemeSkin,
    val isDark: Boolean,
    val gradient: List<Color>,
    val onGradient: Color,
    val logoGradient: List<Color>,
    val glass: Color,
    val glassBorder: Color,
    val muted: Color,
    val success: Color,
    val warning: Color,
    val live: Color,
) {
    /** Diagonal brand gradient brush, sized for typical buttons and cards. */
    fun gradientBrush(start: Offset = Offset.Zero, end: Offset = Offset.Infinite): Brush =
        Brush.linearGradient(gradient, start = start, end = end)

    fun horizontalGradient(): Brush = Brush.horizontalGradient(gradient)
}

val LocalOneFera = staticCompositionLocalOf {
    OneFeraExtras(
        skin = ThemeSkin.Default,
        isDark = true,
        gradient = ThemeSkin.Default.gradient,
        onGradient = Color.White,
        logoGradient = BrandColors.LogoOnDark,
        glass = Color(0x14FFFFFF),
        glassBorder = Color(0x1FFFFFFF),
        muted = NightPalette.OnSurfaceMuted,
        success = StatusColors.Success,
        warning = StatusColors.Warning,
        live = StatusColors.Live,
    )
}

/** Shortcut: `OneFeraTheme.extras.gradient` etc. */
object OneFeraTheme {
    val extras: OneFeraExtras
        @Composable get() = LocalOneFera.current
}

val OneFeraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun OneFeraTheme(
    skin: ThemeSkin = ThemeSkin.Default,
    mode: ThemeMode = ThemeMode.Default,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val colorScheme = if (dark) {
        darkColorScheme(
            primary = skin.accent,
            onPrimary = skin.onGradient,
            primaryContainer = skin.gradient.first().copy(alpha = 0.22f),
            onPrimaryContainer = NightPalette.OnSurface,
            secondary = skin.gradient[skin.gradient.size / 2],
            onSecondary = skin.onGradient,
            tertiary = skin.gradient.last(),
            onTertiary = skin.onGradient,
            background = NightPalette.Background,
            onBackground = NightPalette.OnSurface,
            surface = NightPalette.Surface,
            onSurface = NightPalette.OnSurface,
            surfaceVariant = NightPalette.SurfaceHigh,
            onSurfaceVariant = NightPalette.OnSurfaceMuted,
            surfaceContainerLowest = NightPalette.Background,
            surfaceContainerLow = NightPalette.Surface,
            surfaceContainer = NightPalette.Surface,
            surfaceContainerHigh = NightPalette.SurfaceHigh,
            surfaceContainerHighest = NightPalette.SurfaceHighest,
            outline = NightPalette.Outline,
            outlineVariant = NightPalette.OutlineSoft,
            error = StatusColors.Error,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = skin.accent.darken(0.25f),
            onPrimary = Color.White,
            primaryContainer = skin.gradient.first().copy(alpha = 0.18f),
            onPrimaryContainer = DayPalette.OnSurface,
            secondary = skin.gradient[skin.gradient.size / 2].darken(0.2f),
            onSecondary = Color.White,
            tertiary = skin.gradient.last().darken(0.2f),
            onTertiary = Color.White,
            background = DayPalette.Background,
            onBackground = DayPalette.OnSurface,
            surface = DayPalette.Surface,
            onSurface = DayPalette.OnSurface,
            surfaceVariant = DayPalette.SurfaceHigh,
            onSurfaceVariant = DayPalette.OnSurfaceMuted,
            surfaceContainerLowest = DayPalette.Surface,
            surfaceContainerLow = DayPalette.Surface,
            surfaceContainer = DayPalette.SurfaceHigh,
            surfaceContainerHigh = DayPalette.SurfaceHigh,
            surfaceContainerHighest = DayPalette.SurfaceHighest,
            outline = DayPalette.Outline,
            outlineVariant = DayPalette.OutlineSoft,
            error = Color(0xFFD62D52),
            onError = Color.White,
        )
    }
    val extras = OneFeraExtras(
        skin = skin,
        isDark = dark,
        gradient = skin.gradient,
        onGradient = skin.onGradient,
        logoGradient = if (dark) BrandColors.LogoOnDark else BrandColors.LogoOnLight,
        glass = if (dark) Color(0x12FFFFFF) else Color(0xB3FFFFFF),
        glassBorder = if (dark) NightPalette.OutlineSoft else DayPalette.Outline,
        muted = if (dark) NightPalette.OnSurfaceMuted else DayPalette.OnSurfaceMuted,
        success = StatusColors.Success,
        warning = StatusColors.Warning,
        live = StatusColors.Live,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalOneFera provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = OneFeraTypography,
            shapes = OneFeraShapes,
        ) {
            // Screens draw straight onto the aurora background rather than a Material Surface,
            // so set the default text/icon colour here (otherwise it falls back to black).
            CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground, content = content)
        }
    }
}

private fun Color.darken(fraction: Float): Color = Color(
    red = red * (1f - fraction),
    green = green * (1f - fraction),
    blue = blue * (1f - fraction),
    alpha = alpha,
)
