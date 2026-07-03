package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.GradientButton
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.elevatedCard
import com.privatepayments.ui.theme.umbraScreen

private fun kindLabel(kind: ActivityKind): String = when (kind) {
    ActivityKind.Deposit -> "Deposit"
    ActivityKind.Received -> "Received"
    ActivityKind.Transferred -> "Sent privately"
    ActivityKind.Change -> "Change (to self)"
    ActivityKind.PublicSent -> "Sent"
    ActivityKind.PublicReceived -> "Received"
}

/**
 * Full detail for a single [Activity] row, reached by tapping it on Home or
 * the Activity tab. Shielded notes surface their leaf index + commitment (no
 * explorer link — there's nothing public to look up); public payments surface
 * the tx hash and a stellar.expert link.
 */
@Composable
fun TxDetailScreen(a: Activity, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val accent = if (a.private) Umbra.Primary else Umbra.Public

    Column(Modifier.fillMaxSize().umbraScreen().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Umbra.SurfaceHi),
                contentAlignment = Alignment.Center,
            ) { Icon(a.icon, null, tint = accent, modifier = Modifier.size(32.dp)) }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            a.amount,
            color = if (a.positive) Umbra.Success else Umbra.TextPrimary,
            fontFamily = Umbra.Display, fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(6.dp))
        Text(a.title, color = Umbra.TextMuted, fontSize = 15.sp, modifier = Modifier.align(Alignment.CenterHorizontally))

        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth().elevatedCard(RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 6.dp)) {
            DetailRow("Type", kindLabel(a.kind), Umbra.TextPrimary)
            Divider()
            DetailRow("Status", if (a.pending) "Pending" else "Confirmed", if (a.pending) Umbra.Warning else Umbra.Success)
            Divider()
            DetailRow("Amount", a.amount, if (a.positive) Umbra.Success else Umbra.TextPrimary, mono = true)
            Divider()
            DetailRow(
                "Date",
                a.timestamp?.let { "${dayLabel(dateOf(it))} · ${timeLabel(it)}" } ?: "—",
                Umbra.TextSecondary,
            )
            Divider()
            DetailRow("Privacy", if (a.private) "Shielded" else "Public", accent)
        }

        Spacer(Modifier.height(20.dp))
        if (a.txHash == null) {
            a.leafIndex?.let { CopyCard("Leaf index", it.toString()) }
            if (a.leafIndex != null && a.commitment != null) Spacer(Modifier.height(12.dp))
            a.commitment?.let { CopyCard("Commitment", it) }
        } else {
            CopyCard("Transaction hash", a.txHash)
            Spacer(Modifier.height(12.dp))
            GradientButton("View on stellar.expert ↗", Modifier.fillMaxWidth()) {
                openUrl(ctx, "https://stellar.expert/explorer/testnet/tx/${a.txHash}")
            }
        }

        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.SurfaceElevated)
                .clickable(onClick = onClose).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Close", color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
        Spacer(Modifier.height(20.dp))
    }
}
