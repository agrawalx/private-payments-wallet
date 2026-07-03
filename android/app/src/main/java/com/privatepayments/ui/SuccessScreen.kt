package com.privatepayments.ui

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra
import kotlin.random.Random

/**
 * The "it worked" screen at the end of every deposit/send/withdraw. Shows the
 * verb + amount, a tap-to-copy tx hash, an explorer link, a one-tap share of
 * the receipt, and — for private (ZK) ops — how long the on-device proof took.
 */
@Composable
fun SuccessScreen(
    verb: String,
    amount: String,
    txHash: String,
    proofMs: Long? = null,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val explorer = "https://stellar.expert/explorer/testnet/tx/$txHash"
    val haptics = rememberHaptics()
    LaunchedEffect(Unit) { haptics.confirm() }

    // Check-circle scale-in: 0 -> 1 with a bouncy spring on entry.
    val checkScale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        checkScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().background(Umbra.Bg).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(96.dp).scale(checkScale.value).clip(CircleShape).background(Umbra.Success),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, null, tint = Umbra.Bg, modifier = Modifier.size(48.dp)) }
            Spacer(Modifier.height(28.dp))
            Text(verb, color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text("$amount XLM", color = Umbra.TextSecondary, fontSize = 16.sp)
            if (proofMs != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Zero-knowledge proof built on your phone in %.1fs".format(proofMs / 1000f),
                    color = Umbra.PrimaryLight, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text("Confirmed on Stellar testnet · tap to copy", color = Umbra.TextFaint, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                shortHash(txHash),
                color = Umbra.PrimaryLight, fontFamily = Umbra.Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { copyToClipboard(ctx, "Transaction id", txHash) },
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Umbra.SurfaceElevated)
                        .clickable { openUrl(ctx, explorer) }.padding(horizontal = 16.dp, vertical = 10.dp),
                ) { Text("View on stellar.expert ↗", color = Umbra.PrimaryLight, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Umbra.SurfaceElevated)
                        .clickable { shareReceipt(ctx, verb, amount, txHash, explorer) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Share, null, tint = Umbra.PrimaryLight, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share receipt", color = Umbra.PrimaryLight, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.Primary)
                    .clickable { onClose() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Done", color = Umbra.IconOnPrimary, fontWeight = FontWeight.SemiBold) }
        }
        ConfettiOverlay()
    }
}

private fun shareReceipt(ctx: android.content.Context, verb: String, amount: String, txHash: String, explorer: String) {
    val text = "$verb $amount XLM on Stellar testnet\ntx $txHash\n$explorer"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, "Share receipt"))
}

private data class Confetto(
    val start: Offset,
    val velocity: Offset,
    val color: Color,
    val rotationSpeed: Float,
)

/** A one-shot burst of ~40 falling particles, played once on entry. */
@Composable
private fun ConfettiOverlay() {
    val colors = listOf(Umbra.Primary, Umbra.Success, Umbra.Warning)
    val particles = remember {
        val rnd = Random(42)
        List(40) {
            Confetto(
                start = Offset(0.5f + (rnd.nextFloat() - 0.5f) * 0.3f, 0.12f + rnd.nextFloat() * 0.05f),
                velocity = Offset((rnd.nextFloat() - 0.5f) * 0.9f, 0.35f + rnd.nextFloat() * 0.5f),
                color = colors[rnd.nextInt(colors.size)],
                rotationSpeed = (rnd.nextFloat() - 0.5f) * 720f,
            )
        }
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(1200)) }

    Canvas(Modifier.fillMaxSize()) {
        val t = progress.value
        if (t <= 0f) return@Canvas
        val gravity = 0.9f
        particles.forEach { p ->
            val x = (p.start.x + p.velocity.x * t) * size.width
            val y = (p.start.y + p.velocity.y * t + gravity * t * t) * size.height
            val alpha = (1f - t).coerceIn(0f, 1f)
            if (alpha <= 0f) return@forEach
            rotate(degrees = p.rotationSpeed * t, pivot = Offset(x, y)) {
                drawRect(
                    color = p.color.copy(alpha = alpha),
                    topLeft = Offset(x - 4.dp.toPx() / 2, y - 8.dp.toPx() / 2),
                    size = Size(4.dp.toPx(), 8.dp.toPx()),
                )
            }
        }
    }
}
