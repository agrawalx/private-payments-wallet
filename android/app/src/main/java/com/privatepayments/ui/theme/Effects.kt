package com.privatepayments.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Reusable visual language (depth + glow + gradient + motion) ──────────────
// The design system reads "glassy/rich" not via blur but via: an ambient purple
// glow on the board, soft drop-shadows for depth, translucent accent washes with
// hairline borders, purple gradient CTAs with a glow halo, and subtle motion.
// These helpers centralize all of that so screens stay terse.

/** Board background + the design's ambient purple glow anchored top-left. */
fun Modifier.umbraScreen(): Modifier = this
    .background(Umbra.Bg)
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                colors = listOf(Umbra.Primary.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * 0.18f, size.height * 0.04f),
                radius = size.minDimension * 1.15f,
            ),
        )
    }

/** Soft drop-shadow card depth (design's `0 24px 64px rgba(0,0,0,.5)`). */
fun Modifier.cardDepth(shape: RoundedCornerShape, elevation: Dp = 14.dp): Modifier =
    this.shadow(elevation, shape, clip = false)

/** Colored glow halo for CTAs / orbs (uses shadow spot+ambient color). */
fun Modifier.glow(color: Color, shape: RoundedCornerShape, elevation: Dp = 16.dp): Modifier =
    this.shadow(elevation, shape, clip = false, ambientColor = color, spotColor = color)

/** A raised surface card: gradient fill + hairline border + depth. */
fun Modifier.elevatedCard(shape: RoundedCornerShape): Modifier = this
    .cardDepth(shape)
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Umbra.SurfaceElevated, Umbra.Surface)))
    .border(1.dp, Umbra.Border, shape)

/** Translucent accent wash + matching hairline (the design's pill/banner look). */
fun Modifier.accentWash(wash: Color, border: Color, shape: RoundedCornerShape): Modifier = this
    .clip(shape)
    .background(wash)
    .border(1.dp, border, shape)

/** The primary purple-gradient CTA with a glow halo, reused across screens. */
@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .then(if (enabled) Modifier.glow(Umbra.Primary, shape, 14.dp) else Modifier)
            .clip(shape)
            .background(
                if (enabled) Brush.linearGradient(listOf(Umbra.Primary, Umbra.PrimaryDeep))
                else SolidColor(Umbra.SurfaceElevated),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) Umbra.IconOnPrimary else Umbra.TextFaint,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
    }
}

/**
 * The "living shield" proof orb: a breathing radial glow, a rotating sweep-gradient
 * ring, and a soft core holding the icon. `accent` is purple (private) or amber
 * (public). `spin` drives the ring/dots only while proving.
 */
@Composable
fun ProofOrb(icon: ImageVector, accent: Color, spin: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val glowA by t.animateFloat(
        0.35f, 0.75f,
        infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "glow",
    )
    val pulse by t.animateFloat(
        1f, 1.06f,
        infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "pulse",
    )
    val ring by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "ring",
    )
    Box(modifier.size(176.dp), contentAlignment = Alignment.Center) {
        // breathing glow halo
        Box(
            Modifier
                .size(176.dp)
                .graphicsLayer { scaleX = pulse; scaleY = pulse; alpha = glowA }
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent)),
                    CircleShape,
                ),
        )
        // rotating sweep-gradient ring (spins only while proving)
        Box(
            Modifier
                .size(128.dp)
                .graphicsLayer { rotationZ = if (spin) ring else 0f }
                .border(
                    2.dp,
                    Brush.sweepGradient(listOf(Color.Transparent, accent, accent.copy(alpha = 0.1f), Color.Transparent)),
                    CircleShape,
                ),
        )
        // soft core + icon
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.28f), Umbra.SurfaceElevated)))
                .border(1.dp, accent.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(40.dp))
        }
    }
}
