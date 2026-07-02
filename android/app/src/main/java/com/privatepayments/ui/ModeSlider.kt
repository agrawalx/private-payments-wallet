package com.privatepayments.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.WalletMode
import com.privatepayments.ui.theme.glow

/**
 * The two-faced switcher: ☀ **Public** (Daylight) ⇄ 🔒 **Shielded** (Umbra).
 * A pill with a spring-loaded accent thumb; tap either half or drag to flip.
 * `onChange` reports the **window-absolute** point of the tap/thumb (the side
 * chosen), so the caller can anchor a circular-reveal transition there.
 */
@Composable
fun ModeSlider(mode: WalletMode, onChange: (WalletMode, Offset) -> Unit, modifier: Modifier = Modifier) {
    val isPublic = mode == WalletMode.Public
    val thumb by animateFloatAsState(
        if (isPublic) 0f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "thumb",
    )
    val shape = RoundedCornerShape(50)
    var bounds by remember { mutableStateOf<Rect?>(null) }

    fun anchorFor(public: Boolean): Offset {
        val b = bounds ?: return Offset.Zero
        return Offset(if (public) b.left + b.width * 0.25f else b.left + b.width * 0.75f, b.center.y)
    }

    BoxWithConstraints(
        modifier
            .width(232.dp)
            .height(40.dp)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .clip(shape)
            .background(Umbra.SurfaceElevated)
            .border(1.dp, Umbra.Border, shape)
            .pointerInput(isPublic) {
                detectHorizontalDragGestures { _, dragAmount ->
                    android.util.Log.d("ModeSlider", "drag dragAmount=$dragAmount isPublic=$isPublic")
                    if (dragAmount > 8f && !isPublic) onChange(WalletMode.Public, anchorFor(true))
                    else if (dragAmount < -8f && isPublic) onChange(WalletMode.Shielded, anchorFor(false))
                }
            },
    ) {
        val half = maxWidth / 2
        // Spring-loaded accent thumb (amber left / purple right).
        Box(
            Modifier
                .offset(x = half * thumb)
                .padding(3.dp)
                .width(half - 6.dp)
                .fillMaxHeight()
                .glow(Umbra.Primary, RoundedCornerShape(50), 8.dp)
                .clip(RoundedCornerShape(50))
                .background(Umbra.Primary),
        )
        Row(Modifier.fillMaxSize()) {
            Segment("Public", Icons.Filled.WbSunny, isPublic, Modifier.weight(1f)) {
                android.util.Log.d("ModeSlider", "tap Public segment isPublic=$isPublic")
                onChange(WalletMode.Public, anchorFor(true))
            }
            Segment("Shielded", Icons.Filled.Lock, !isPublic, Modifier.weight(1f)) {
                android.util.Log.d("ModeSlider", "tap Shielded segment isPublic=$isPublic")
                onChange(WalletMode.Shielded, anchorFor(false))
            }
        }
    }
}

@Composable
private fun Segment(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ink = if (selected) Umbra.IconOnPrimary else Umbra.TextMuted
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
