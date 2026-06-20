package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.umbraScreen
import kotlinx.coroutines.launch

/**
 * One-time "Register" step = ASP enrollment. To **spend** from the pool
 * (deposit / send / withdraw) an account's key must be in the on-chain
 * association set; receiving never needs it. Registration is open to everyone
 * for now (a later build can gate it behind a proof of personhood such as
 * anon-aadhaar). [onRegister] submits the enrollment and resolves true once the
 * new membership leaf is confirmed on-chain.
 */
@Composable
fun RegisterScreen(
    onRegister: suspend () -> Boolean,
    onRegistered: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().umbraScreen().padding(24.dp)) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Umbra.PrimaryWash)
                .border(1.dp, Umbra.Primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Shield, null, tint = Umbra.Primary, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.height(16.dp))
        Text("Register to start", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Before you can deposit or send, this account joins the pool's association set — a one-time, on-chain enrollment that lets you spend privately. Receiving never needs it.",
            color = Umbra.TextMuted, fontSize = 14.sp, lineHeight = 20.sp,
        )

        Spacer(Modifier.height(20.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.Surface).padding(16.dp),
        ) {
            InfoRow("Open to everyone — no approval needed right now.")
            Spacer(Modifier.height(10.dp))
            InfoRow("Submitted from this account; it pays a small network fee.")
            Spacer(Modifier.height(10.dp))
            InfoRow("Later this can require a proof of personhood (e.g. anon-aadhaar).")
        }

        Spacer(Modifier.weight(1f))
        error?.let {
            Text(it, color = Umbra.Error, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.Primary)
                .clickable(enabled = !working) {
                    scope.launch {
                        working = true; error = null
                        runCatching { onRegister() }
                            .onSuccess { ok ->
                                working = false
                                if (ok) onRegistered()
                                else error = "Enrollment didn't confirm yet — give it a moment and try again."
                            }
                            .onFailure { working = false; error = it.message ?: "Registration failed" }
                    }
                }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (working) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Umbra.IconOnPrimary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Registering…", color = Umbra.IconOnPrimary, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text("Register", color = Umbra.IconOnPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .clickable(enabled = !working, onClick = onCancel).padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Not now", color = Umbra.TextMuted, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun InfoRow(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(5.dp).clip(CircleShape).background(Umbra.Primary))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Umbra.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
