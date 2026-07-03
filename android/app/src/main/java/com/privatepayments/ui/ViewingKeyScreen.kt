package com.privatepayments.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.accentWash
import com.privatepayments.ui.theme.umbraScreen

/**
 * Phase 6/v2 "viewing key" export screen — the wallet's X25519 decryption key
 * plus its BN254 nullifier key, wire-formatted as `stellaview2:<base64>`.
 * Handing this out lets an auditor decrypt every incoming note this wallet
 * has ever received (amounts + dates) *and* see when those notes were later
 * spent, but it cannot spend funds and cannot be revoked — hence the
 * persistent warning.
 */
@Composable
fun ViewingKeyScreen(viewKey: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().umbraScreen().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Visibility, null, tint = Umbra.Primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Viewing key", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Share this with an auditor to give them read-only access to the payments you receive.",
            color = Umbra.TextMuted, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "This is a v2 key — it additionally reveals when received funds were later spent.",
            color = Umbra.TextMuted, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth().accentWash(
                Umbra.Warning.copy(alpha = 0.14f), Umbra.Warning.copy(alpha = 0.4f), RoundedCornerShape(12.dp),
            ).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, null, tint = Umbra.Warning, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Anyone with this key can see every payment you receive and when you spend — forever. It cannot spend your funds, and it cannot be revoked.",
                color = Umbra.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }

        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                QrCode(viewKey, size = 224.dp, fg = Color(0xFF14110C), bg = Color.White, modifier = Modifier.size(224.dp))
            }
        }

        Spacer(Modifier.height(22.dp))
        CopyCard("Viewing key", viewKey, copyLabel = "Viewing key")
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Umbra.SurfaceElevated)
                .clickable {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Stella viewing key")
                        putExtra(Intent.EXTRA_TEXT, viewKey)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Share viewing key"))
                }.padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Share, null, tint = Umbra.PrimaryLight, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Share", color = Umbra.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.SurfaceElevated)
                .clickable(onClick = onClose).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Close", color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
        Spacer(Modifier.height(20.dp))
    }
}
