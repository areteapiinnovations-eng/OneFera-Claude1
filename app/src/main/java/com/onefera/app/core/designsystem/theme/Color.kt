package com.onefera.app.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/** Core OneFera brand colours, taken from the OneFera logo gradient. */
object BrandColors {
    val Aqua = Color(0xFF2EE6D6)
    val Electric = Color(0xFF4F8CFF)
    val Violet = Color(0xFFA259FF)
    val Orchid = Color(0xFFE163E5)
    val Spark = Color(0xFF8B7BFF)

    /** Deeper version of the brand gradient that stays readable on light backgrounds. */
    val LogoOnLight = listOf(Color(0xFF18A9B8), Color(0xFF3B6EF0), Color(0xFF8A3FF0), Color(0xFFB640D0))
    val LogoOnDark = listOf(Aqua, Electric, Violet, Orchid)
}

internal object NightPalette {
    val Background = Color(0xFF0B0B14)
    val Surface = Color(0xFF13131F)
    val SurfaceHigh = Color(0xFF1B1B2A)
    val SurfaceHighest = Color(0xFF242437)
    val OnSurface = Color(0xFFF4F3FF)
    val OnSurfaceMuted = Color(0xFFA3A2BF)
    val Outline = Color(0xFF34344A)
    val OutlineSoft = Color(0x1FFFFFFF)
}

internal object DayPalette {
    val Background = Color(0xFFF6F5FC)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceHigh = Color(0xFFF0EEF9)
    val SurfaceHighest = Color(0xFFE6E3F4)
    val OnSurface = Color(0xFF12111D)
    val OnSurfaceMuted = Color(0xFF615F7A)
    val Outline = Color(0xFFD5D2E6)
    val OutlineSoft = Color(0x14000000)
}

object StatusColors {
    val Success = Color(0xFF2BD98B)
    val Warning = Color(0xFFFFB547)
    val Error = Color(0xFFFF5A7A)
    val Live = Color(0xFFFF3D6E)
}
