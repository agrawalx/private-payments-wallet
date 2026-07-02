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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.WalletMode
import com.privatepayments.ui.theme.umbraScreen

/**
 * Mode-aware Receive. Shows the address relevant to the current face, with a
 * scannable QR:
 *  - **Daylight (Public):** the `G…` Stellar address (fund / classic payment).
 *  - **Umbra (Shielded):** the "Stella" shielded address (private payments).
 * The QR is always dark-on-white for reliable scanning regardless of theme.
 */
@Composable
fun ReceiveScreen(
    mode: WalletMode,
    stellarAddress: String,
    shieldedAddress: String?,
    onClose: () -> Unit,
) {
    val isPublic = mode == WalletMode.Public
    val value = if (isPublic) stellarAddress else shieldedAddress
    val accent = Umbra.Primary
    val subtitle = if (isPublic) "Public · fund this account or receive XLM"
    else "Private · receive a shielded payment"
    val copyLabel = if (isPublic) "Address" else "Shielded address"
    val cardLabel = if (isPublic) "Stellar address" else "Stella shielded address"
    val hint = if (isPublic)
        "Scan or share to send this account public XLM on Stellar testnet."
    else
        "Share this with a sender so they can pay you privately. It reveals nothing on-chain on its own."

    Column(
        Modifier.fillMaxSize().umbraScreen().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Receive", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isPublic) Icons.Filled.Public else Icons.Filled.Lock, null, tint = accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(subtitle, color = Umbra.TextMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(24.dp))

        if (value.isNullOrEmpty()) {
            Text("Shielded address not ready yet.", color = Umbra.TextFaint, fontSize = 13.sp)
        } else {
            // White QR tile — dark modules on white for reliable scanning.
            Box(
                Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                QrCode(value, size = 224.dp, fg = Color(0xFF14110C), bg = Color.White, modifier = Modifier.size(224.dp))
            }
            Spacer(Modifier.height(22.dp))
            CopyCard(cardLabel, value, copyLabel = copyLabel)
            Spacer(Modifier.height(10.dp))
            Text(hint, color = Umbra.TextFaint, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.SurfaceElevated)
                .clickable(onClick = onClose).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Close", color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
    }
}
