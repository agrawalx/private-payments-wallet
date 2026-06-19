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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.wallet.AccountInfo
import com.privatepayments.ui.theme.Umbra

/** Settings: accounts (switch/add), active address, network, recovery, backup. */
@Composable
fun SettingsScreen(
    accounts: List<AccountInfo>,
    activeIndex: Int,
    onSwitch: (Int) -> Unit,
    onAdd: () -> Unit,
    onRecovery: () -> Unit,
    onBackup: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Umbra.Bg).verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Settings", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(20.dp))
        Text("ACCOUNTS", color = Umbra.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        accounts.forEach { acc -> AccountRow(acc, acc.index == activeIndex) { onSwitch(acc.index) } }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Umbra.Surface)
                .clickable(onClick = onAdd).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, null, tint = Umbra.Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text("Add account", color = Umbra.Primary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(24.dp))
        Text("ACTIVE ADDRESS", color = Umbra.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        CopyCard("Stellar · Testnet", accounts.getOrNull(activeIndex)?.address ?: "—", copyLabel = "Address")

        Spacer(Modifier.height(24.dp))
        Text("SECURITY", color = Umbra.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        SettingRow(Icons.Filled.Key, "Recovery phrase", "View or import your 12 words", onRecovery)
        Spacer(Modifier.height(8.dp))
        SettingRow(Icons.Filled.CloudUpload, "Cloud backup", "Encrypted note history to Google Drive", onBackup)

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

@Composable
private fun AccountRow(acc: AccountInfo, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(if (active) Umbra.SurfaceElevated else Umbra.Surface)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Umbra.SurfaceHi),
            contentAlignment = Alignment.Center,
        ) { Text("${acc.index + 1}", color = Umbra.TextSecondary, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Account ${acc.index + 1}", color = Umbra.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("${acc.address.take(8)}…${acc.address.takeLast(6)}", color = Umbra.TextFaint, fontFamily = Umbra.Mono, fontSize = 12.sp)
        }
        if (active) Icon(Icons.Filled.Check, null, tint = Umbra.Success, modifier = Modifier.size(18.dp))
    }
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
