package com.privatepayments.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Which face of the wallet is showing. Drives the whole palette. */
enum class WalletMode { Public, Shielded }

// ---- Palette definition ----------------------------------------------------
// The app has two faces: **Umbra** (Shielded — dark, secretive, purple + lock)
// and **Daylight** (Public — light, joyful, amber + globe). Every color token
// below is resolved per-mode through [LocalUmbraColors]; screens still read
// them as `Umbra.Bg`, `Umbra.Primary`, … so call sites are mode-agnostic.

@Immutable
data class UmbraColors(
    val bg: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceHi: Color,
    val border: Color,
    // Hero accent (purple in Umbra, amber in Daylight) — CTAs, orbs, active bits.
    val primary: Color,
    val primaryDeep: Color,
    val primaryLight: Color,
    val primaryWash: Color,
    val primaryBorder: Color,
    // Public/deposit amber (globe) — stays amber in both, tuned for the surface.
    val public: Color,
    val publicWash: Color,
    val publicBorder: Color,
    // Ink drawn on top of the filled `primary` CTA.
    val iconOnPrimary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textFaint: Color,
    val success: Color,
    val successWash: Color,
    val error: Color,
    val isLight: Boolean,
)

/** 🌙 Umbra — dark, secretive (the original design system). */
val UmbraDark = UmbraColors(
    bg = Color(0xFF08090B),
    surface = Color(0xFF15171C),
    surfaceElevated = Color(0xFF1D2027),
    surfaceHi = Color(0xFF23262E),
    border = Color(0xFF2B2F39),
    primary = Color(0xFF8B6DFF),
    primaryDeep = Color(0xFF5B3DF0),
    primaryLight = Color(0xFFB9A4FF),
    primaryWash = Color(0x1F8B6DFF),
    primaryBorder = Color(0xFF3A2F6E),
    public = Color(0xFFF5A623),
    publicWash = Color(0x1FF5A623),
    publicBorder = Color(0xFF4A3A12),
    iconOnPrimary = Color(0xFF0A0A12),
    textPrimary = Color(0xFFF3F4F7),
    textSecondary = Color(0xFFCFD2D8),
    textMuted = Color(0xFF9EA3AD),
    textFaint = Color(0xFF686D77),
    success = Color(0xFF34D399),
    successWash = Color(0x2434D399),
    error = Color(0xFFF87171),
    isLight = false,
)

/** ☀ Daylight — light, joyful. Warm paper + marigold; the hero accent is amber. */
val UmbraLight = UmbraColors(
    bg = Color(0xFFFBF6EC),          // warm paper
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFF8EA),
    surfaceHi = Color(0xFFFCEFD6),
    border = Color(0xFFEADFCB),
    primary = Color(0xFFE68A0C),     // marigold — the joyful CTA accent
    primaryDeep = Color(0xFFC8730A),
    primaryLight = Color(0xFFFFB84D),
    primaryWash = Color(0x1FE68A0C),
    primaryBorder = Color(0xFFF0D08A),
    public = Color(0xFFE68A0C),
    publicWash = Color(0x1FE68A0C),
    publicBorder = Color(0xFFF0D08A),
    iconOnPrimary = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF1E1912),
    textSecondary = Color(0xFF554E44),
    textMuted = Color(0xFF837A6C),
    textFaint = Color(0xFFA99F8F),
    success = Color(0xFF17936B),
    successWash = Color(0x2217936B),
    error = Color(0xFFD64545),
    isLight = true,
)

val LocalUmbraColors = staticCompositionLocalOf { UmbraDark }

/** Shared timing for the mode switch — colors (here) and content (Home's
 *  AnimatedContent) animate on this same clock so they settle together. */
const val ModeSwitchMillis = 420
val ModeSwitchEasing = FastOutSlowInEasing

// ---- `Umbra` accessor: mode-aware colors + static fonts --------------------
// Colors resolve through the CompositionLocal (so a mode switch repaints the
// whole tree); fonts are constant. Read from composable context only.
object Umbra {
    // Brand families (see Fonts.kt) — constant across modes.
    val Display = SpaceGrotesk
    val Body = PlusJakartaSans
    val Mono = JetBrainsMono

    val Bg: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.bg
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.surface
    val SurfaceElevated: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.surfaceElevated
    val SurfaceHi: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.surfaceHi
    val Border: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.border
    val Primary: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.primary
    val PrimaryDeep: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.primaryDeep
    val PrimaryLight: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.primaryLight
    val PrimaryWash: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.primaryWash
    val PrimaryBorder: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.primaryBorder
    val Public: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.public
    val PublicWash: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.publicWash
    val PublicBorder: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.publicBorder
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.public
    val IconOnPrimary: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.iconOnPrimary
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.textSecondary
    val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.textMuted
    val TextFaint: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.textFaint
    val Success: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.success
    val SuccessWash: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.successWash
    val Error: Color @Composable @ReadOnlyComposable get() = LocalUmbraColors.current.error
}

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

/** Resolves a [WalletMode] to its palette. */
fun paletteFor(mode: WalletMode): UmbraColors = if (mode == WalletMode.Public) UmbraLight else UmbraDark

@Composable
fun UmbraTheme(mode: WalletMode = WalletMode.Shielded, content: @Composable () -> Unit) {
    // Colors snap instantly — the mode switch's visual transition is a circular
    // reveal (see MainActivity's ModeReveal), not a color cross-fade, so there's
    // no muddy in-between tint and no whole-tree recomposition every frame.
    val colors = paletteFor(mode)
    val scheme = if (colors.isLight) {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = colors.iconOnPrimary,
            secondary = colors.primaryLight,
            background = colors.bg,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.textMuted,
            outline = colors.border,
            error = colors.error,
        )
    } else {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = colors.textPrimary,
            secondary = colors.primaryLight,
            background = colors.bg,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.textMuted,
            outline = colors.border,
            error = colors.error,
        )
    }
    CompositionLocalProvider(LocalUmbraColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = UmbraTypography, content = content)
    }
}
