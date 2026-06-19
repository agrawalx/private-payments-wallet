package com.privatepayments.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---- Umbra palette (exact tokens from the design system) -------------------
object Umbra {
    val Bg = Color(0xFF08090B)            // board
    val Surface = Color(0xFF15171C)       // surface
    val SurfaceElevated = Color(0xFF1D2027) // raised
    val SurfaceHi = Color(0xFF23262E)
    val Border = Color(0xFF2B2F39)

    // Private = purple + lock (internal sends / withdrawals, unlinkable on-chain)
    val Primary = Color(0xFF8B6DFF)
    val PrimaryDeep = Color(0xFF5B3DF0)
    val PrimaryLight = Color(0xFFB9A4FF)
    val PrimaryWash = Color(0x1F8B6DFF)   // rgba(139,109,255,.12) chip/wash

    // Public = amber + globe (deposits — reveals address + amount on-chain)
    val Public = Color(0xFFF5A623)
    val PublicWash = Color(0x1FF5A623)    // rgba(245,166,35,.12)
    val PublicBorder = Color(0xFF4A3A12)  // amber-dark hairline behind deposit
    val Warning = Public

    // Icon ink on the filled purple gradient (the Send action) — near-black.
    val IconOnPrimary = Color(0xFF0A0A12)

    val TextPrimary = Color(0xFFF3F4F7)
    val TextSecondary = Color(0xFFCFD2D8)
    val TextMuted = Color(0xFF9EA3AD)
    val TextFaint = Color(0xFF686D77)

    val Success = Color(0xFF34D399)
    val SuccessWash = Color(0x2434D399)   // rgba(52,211,153,.14)
    val Error = Color(0xFFF87171)

    // Brand families (see Fonts.kt).
    val Display = SpaceGrotesk
    val Body = PlusJakartaSans
    val Mono = JetBrainsMono
}

private val UmbraColorScheme = darkColorScheme(
    primary = Umbra.Primary,
    onPrimary = Umbra.TextPrimary,
    secondary = Umbra.PrimaryLight,
    background = Umbra.Bg,
    onBackground = Umbra.TextPrimary,
    surface = Umbra.Surface,
    onSurface = Umbra.TextPrimary,
    surfaceVariant = Umbra.SurfaceElevated,
    onSurfaceVariant = Umbra.TextMuted,
    outline = Umbra.Border,
    error = Umbra.Error,
)

// Type scale mirrors the design — Space Grotesk for balances/display, Plus
// Jakarta Sans for UI/body (see Fonts.kt).
private val Display = SpaceGrotesk
private val Body = PlusJakartaSans

private val UmbraTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp),
)

@Composable
fun UmbraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UmbraColorScheme,
        typography = UmbraTypography,
        content = content,
    )
}
