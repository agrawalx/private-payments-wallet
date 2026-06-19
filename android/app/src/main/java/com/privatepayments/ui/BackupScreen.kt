package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra
import kotlinx.coroutines.launch

/**
 * Phase 7 backup screen. Backs up the (encrypted) note history to the user's own
 * Google Drive `appDataFolder`, and restores it. The encryption key is derived
 * from the recovery phrase, so the backup is useless to anyone — including
 * Google — without the seed words.
 */
@Composable
fun BackupScreen(
    encryptionOk: Boolean,
    onBackup: suspend () -> String,
    onRestore: suspend () -> String,
    onClose: () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun run(op: suspend () -> String) {
        if (busy) return
        busy = true; status = null; isError = false
        scope.launch {
            try { status = op() } catch (e: Exception) { status = e.message ?: "Failed"; isError = true } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(Umbra.Bg).padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Cloud backup", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your note history is encrypted with a key derived from your recovery phrase, then stored in your own Google Drive. We never see it, and it can't be decrypted without your seed words.",
            color = Umbra.TextMuted, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (encryptionOk) "✓ Encryption self-test passed (AES-256-GCM, key from your phrase)"
            else "⚠ Encryption self-test failed",
            color = if (encryptionOk) Umbra.Success else Umbra.Error, fontSize = 12.sp,
        )
        Spacer(Modifier.height(24.dp))

        BackupAction(Icons.Filled.CloudUpload, "Back up to Google Drive", "Encrypt + upload your note history", !busy) { run(onBackup) }
        Spacer(Modifier.height(12.dp))
        BackupAction(Icons.Filled.CloudDownload, "Restore from Google Drive", "Download + decrypt onto this device", !busy) { run(onRestore) }

        if (busy) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Umbra.Primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp)); Text("Working…", color = Umbra.TextMuted, fontSize = 13.sp)
            }
        }
        status?.let {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Umbra.Surface).padding(14.dp)) {
                Text(it, color = if (isError) Umbra.Error else Umbra.Success, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }

        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.SurfaceElevated)
                .clickable(onClick = onClose).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Close", color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun BackupAction(icon: ImageVector, title: String, sub: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.Surface)
            .clickable(enabled = enabled, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Umbra.Primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Umbra.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Umbra.TextFaint, fontSize = 12.sp)
        }
    }
}
