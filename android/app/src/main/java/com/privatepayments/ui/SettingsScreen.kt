package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.wallet.AccountInfo
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.umbraScreen

/** Settings: accounts (switch/add), active address, network, recovery, backup. */
@Composable
fun SettingsScreen(
    accounts: List<AccountInfo>,
    activeIndex: Int,
    onSwitch: (Int) -> Unit,
    onAdd: suspend () -> Unit,
    onRecovery: () -> Unit,
    onBackup: () -> Unit,
    onViewingKey: () -> Unit,
    onAudit: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().umbraScreen().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Settings", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(20.dp))
        Text("ACCOUNT", color = Umbra.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        AccountDropdown(accounts, activeIndex, onSwitch)
        Spacer(Modifier.height(8.dp))
        // Disable + show progress while adding (friendbot funding takes a few seconds)
        // so rapid taps don't spawn duplicate accounts.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Umbra.Surface)
                .clickable(enabled = !adding) {
                    scope.launch { adding = true; runCatching { onAdd() }; adding = false }
                }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (adding) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Umbra.Primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Adding account…", color = Umbra.TextMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            } else {
                Icon(Icons.Filled.Add, null, tint = Umbra.Primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text("Add account", color = Umbra.Primary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("SECURITY", color = Umbra.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        SettingRow(Icons.Filled.Key, "Recovery phrase", "View or import your 12 words", onRecovery)
        Spacer(Modifier.height(8.dp))
        SettingRow(Icons.Filled.CloudUpload, "Cloud backup", "Encrypted note history to Google Drive", onBackup)

        Spacer(Modifier.height(24.dp))
        Text("AUDITING", color = Umbra.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        SettingRow(Icons.Filled.Visibility, "Viewing key", "Give an auditor read access to incoming funds", onViewingKey)
        Spacer(Modifier.height(8.dp))
        SettingRow(Icons.Filled.Policy, "Audit a wallet", "Inspect incoming funds with a viewing key", onAudit)

        Spacer(Modifier.height(24.dp))
        Text("Stella · Stellar testnet · v0.1", color = Umbra.TextFaint, fontSize = 12.sp)

        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.SurfaceElevated)
                .clickable(onClick = onClose).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Close", color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
    }
}

/** Collapsed = active account + copy; tap to expand and select another. */
@Composable
private fun AccountDropdown(accounts: List<AccountInfo>, activeIndex: Int, onSwitch: (Int) -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val active = accounts.getOrNull(activeIndex)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Umbra.Surface)) {
        // Header (active account).
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountAvatar(activeIndex)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Account ${activeIndex + 1}", color = Umbra.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(shortAddr(active?.address), color = Umbra.TextFaint, fontFamily = Umbra.Mono, fontSize = 12.sp)
            }
            if (active != null) {
                Icon(
                    Icons.Filled.ContentCopy, "Copy address", tint = Umbra.TextMuted,
                    modifier = Modifier.size(18.dp).clickable { copyToClipboard(ctx, "Address", active.address) },
                )
                Spacer(Modifier.width(12.dp))
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                null, tint = Umbra.TextMuted, modifier = Modifier.size(22.dp),
            )
        }
        if (expanded) {
            accounts.forEach { acc ->
                Box(Modifier.fillMaxWidth().height(1.dp).background(Umbra.Border))
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onSwitch(acc.index); expanded = false }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccountAvatar(acc.index)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Account ${acc.index + 1}", color = Umbra.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(shortAddr(acc.address), color = Umbra.TextFaint, fontFamily = Umbra.Mono, fontSize = 12.sp)
                    }
                    if (acc.index == activeIndex) Icon(Icons.Filled.Check, null, tint = Umbra.Success, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun shortAddr(a: String?): String = a?.let { shortAddress(it, 8, 6) } ?: "—"

@Composable
private fun AccountAvatar(index: Int) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(Umbra.SurfaceHi),
        contentAlignment = Alignment.Center,
    ) { Text(('A' + index.coerceIn(0, 25)).toString(), color = Umbra.TextSecondary, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Umbra.Surface)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Umbra.Primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Umbra.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Umbra.TextFaint, fontSize = 12.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Umbra.TextFaint, modifier = Modifier.size(20.dp))
    }
}
