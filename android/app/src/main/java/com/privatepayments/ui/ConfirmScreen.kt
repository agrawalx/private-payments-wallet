package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.*

/**
 * Final review/confirm step shown after Amount and before the proof runs. Shows
 * the amount, recipient, network, type, and the fee — including the nice detail
 * that for private sends/withdrawals the **relayer covers the gas** (the user
 * pays nothing), while deposits pay a small fee from their own account.
 */
@Composable
fun ConfirmScreen(
    title: String,
    isPublic: Boolean,
    amountXlm: String,
    recipient: String,
    typeLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    warning: String? = null,
) {
    val accent = if (isPublic) Umbra.Public else Umbra.Primary
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxSize().umbraScreen().padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isPublic) Icons.Filled.Public else Icons.Filled.Lock, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Review", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(28.dp))
        // Amount hero.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            Text(amountXlm, color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("XLM", color = Umbra.TextMuted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
        }

        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth().elevatedCard(RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 6.dp)) {
            DetailRow("Action", title, Umbra.TextPrimary)
            Divider()
            DetailRow("To", recipient, Umbra.TextPrimary, mono = recipient.startsWith("stella") || recipient.startsWith("G"))
            Divider()
            DetailRow("Network", "Stellar · Testnet", Umbra.TextSecondary)
            Divider()
            DetailRow("Type", typeLabel, accent)
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().accentWash(
                if (isPublic) Umbra.PublicWash else Umbra.PrimaryWash,
                if (isPublic) Umbra.PublicBorder else Umbra.PrimaryBorder,
                RoundedCornerShape(14.dp),
            ).padding(13.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(if (isPublic) Icons.Filled.Public else Icons.Filled.Lock, null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (isPublic) "This deposit is public — your address and the amount are visible on-chain."
                else "Private — the proof reveals only that the math checks out, never who or how much.",
                color = Umbra.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }

        warning?.let { msg ->
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().accentWash(
                    Umbra.Warning.copy(alpha = 0.14f), Umbra.Warning.copy(alpha = 0.4f), RoundedCornerShape(12.dp),
                ).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, null, tint = Umbra.Warning, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(msg, color = Umbra.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }

        Spacer(Modifier.weight(1f))
        GradientButton("Confirm & sign", Modifier.fillMaxWidth(), onClick = { haptics.tick(); onConfirm() })
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.SurfaceElevated)
                .clickable(onClick = onCancel).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Back", color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
    }
}
