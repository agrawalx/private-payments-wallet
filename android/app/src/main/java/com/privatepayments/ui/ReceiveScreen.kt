package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.umbraScreen

/**
 * Receive screen. Two addresses:
 *  - **Public Stellar address** (`G…`) — to fund this account or receive a
 *    public deposit (amber/globe).
 *  - **Private "Stella address"** (note + encryption pubkeys) — share this to
 *    receive private shielded payments (purple/lock).
 */
@Composable
fun ReceiveScreen(
    stellarAddress: String,
    shieldedAddress: String?,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().umbraScreen().padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Receive", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Public, null, tint = Umbra.Public, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Public · fund this account / public deposit", color = Umbra.TextMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        CopyCard("Stellar address", stellarAddress, copyLabel = "Address")

        if (shieldedAddress != null) {
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = Umbra.Primary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Private · receive a shielded payment", color = Umbra.TextMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            CopyCard("Stella shielded address", shieldedAddress, copyLabel = "Shielded address")
            Spacer(Modifier.height(8.dp))
            Text(
                "Share this with a sender so they can pay you privately. It reveals nothing on-chain on its own.",
                color = Umbra.TextFaint, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }

        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.SurfaceElevated)
                .clickable(onClick = onClose).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Close", color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
    }
}
