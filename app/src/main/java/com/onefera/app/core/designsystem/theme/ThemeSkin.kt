package com.onefera.app.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Colour "skins" a user can pick in Settings. Every skin is a gradient that drives buttons,
 * highlights, rings and the selected tab, so the whole app restyles instantly.
 */
enum class ThemeSkin(
    val title: String,
    val subtitle: String,
    val gradient: List<Color>,
    /** Main accent used for icons, links, focused borders and switches. */
    val accent: Color,
    /** Text/icon colour placed directly on top of [gradient]. */
    val onGradient: Color,
) {
    FutureEra(
        title = "Future Era",
        subtitle = "Aqua · electric blue · violet · orchid",
        gradient = BrandColors.LogoOnDark,
        accent = Color(0xFF7C8CFF),
        onGradient = Color.White,
    ),
    CyberNeon(
        title = "Cyber Neon",
        subtitle = "Electric cyan · ultraviolet · magenta",
        gradient = listOf(Color(0xFF00F0FF), Color(0xFF7B2FFF), Color(0xFFFF2BD6)),
        accent = Color(0xFF3DE7FF),
        onGradient = Color.White,
    ),
    SunsetPop(
        title = "Sunset Pop",
        subtitle = "Amber · coral · hot pink",
        gradient = listOf(Color(0xFFFFB547), Color(0xFFFF6B6B), Color(0xFFFF4FA3)),
        accent = Color(0xFFFF7A6B),
        onGradient = Color.White,
    ),
    MatrixLime(
        title = "Matrix Lime",
        subtitle = "Acid lime · mint · deep teal",
        gradient = listOf(Color(0xFFC6FF3D), Color(0xFF2BFF88), Color(0xFF00B3A4)),
        accent = Color(0xFF7DFF5A),
        onGradient = Color(0xFF07140C),
    ),
    AuroraIce(
        title = "Aurora Ice",
        subtitle = "Icy indigo · frost · mint",
        gradient = listOf(Color(0xFF6C7BFF), Color(0xFF7FE7FF), Color(0xFF9BFFD9)),
        accent = Color(0xFF8FA0FF),
        onGradient = Color(0xFF0B1030),
    );

    companion object {
        val Default = FutureEra
        fun fromName(name: String?): ThemeSkin = entries.firstOrNull { it.name == name } ?: Default
    }
}

enum class ThemeMode(val label: String) {
    Dark("Dark"),
    Light("Light"),
    System("System");

    companion object {
        val Default = Dark
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: Default
    }
}
