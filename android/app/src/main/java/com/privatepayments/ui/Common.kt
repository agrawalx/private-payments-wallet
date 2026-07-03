package com.privatepayments.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra

fun copyToClipboard(ctx: Context, label: String, text: String) {
    val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clip.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
}

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** A labeled value card with a tap-to-copy affordance (mono value). */
@Composable
fun CopyCard(label: String, value: String, copyLabel: String = label, mono: Boolean = true) {
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Umbra.Surface)
            .clickable { haptics.tick(); copyToClipboard(ctx, copyLabel, value) }.padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Umbra.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.ContentCopy, "Copy", tint = Umbra.PrimaryLight, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            value,
            color = Umbra.TextPrimary,
            fontFamily = if (mono) Umbra.Mono else Umbra.Body,
            fontSize = 13.sp, lineHeight = 19.sp,
        )
    }
}

/** A label/value row used by the review-style screens (Confirm, receipts). */
@Composable
internal fun DetailRow(label: String, value: String, valueColor: Color, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Umbra.TextMuted, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(
            value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            fontFamily = if (mono) Umbra.Mono else Umbra.Body, maxLines = 1,
        )
    }
}

@Composable
internal fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Umbra.Border))
}

/** A breathing placeholder box (alpha 0.4↔0.8) — caller supplies size + clip. */
@Composable
internal fun ShimmerBox(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val alpha by t.animateFloat(0.4f, 0.8f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "shimmerAlpha")
    Box(modifier.graphicsLayer { this.alpha = alpha }.background(Umbra.Surface))
}

/** A full-width row-shaped skeleton, standing in for an [ActivityRow] while loading. */
@Composable
internal fun ShimmerRow() {
    ShimmerBox(Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(16.dp)))
}

/** Coarse network status the poll loop reports; drives [SyncBanner]. */
enum class SyncState { Syncing, Ok, Unreachable }

data class SyncStatus(val state: SyncState, val message: String, val relayerOk: Boolean? = null)

/** The small dot + status line shown under the top bar on Home/Activity/People. */
@Composable
internal fun SyncBanner(status: SyncStatus, onRetry: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Umbra.SurfaceElevated).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot = when (status.state) {
            SyncState.Ok -> Umbra.Success
            SyncState.Syncing -> Umbra.Warning
            SyncState.Unreachable -> Umbra.Error
        }
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(status.message, color = Umbra.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (status.state == SyncState.Unreachable) {
            Text(
                "Retry", color = Umbra.Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onRetry).padding(start = 8.dp),
            )
        }
    }
}
