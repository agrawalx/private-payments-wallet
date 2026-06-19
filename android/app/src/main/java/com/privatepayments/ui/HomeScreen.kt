package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra

data class Activity(
    val icon: ImageVector,
    val title: String,
    val private: Boolean,
    val amount: String,
    val positive: Boolean,
    val time: String,
    val subtitle: String? = null,
)

@androidx.compose.runtime.Composable
fun HomeScreen(
    handle: String,
    balanceText: String,
    publicText: String,
    activity: List<Activity>,
    syncStatus: String,
    onSend: () -> Unit,
    onDeposit: () -> Unit,
    onWithdraw: () -> Unit,
    onReceive: () -> Unit,
    onSettings: () -> Unit,
    onShareProof: () -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Umbra.Bg)
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            TopBar(handle, onSettings)
            Spacer(Modifier.height(14.dp))
            SyncBanner(syncStatus)
            Spacer(Modifier.height(14.dp))
            BalanceCard(revealed, balanceText, publicText) { revealed = !revealed }
            Spacer(Modifier.height(24.dp))
            ActionRow(onSend = onSend, onDeposit = onDeposit, onWithdraw = onWithdraw, onReceive = onReceive)
            Spacer(Modifier.height(14.dp))
            ShareProofButton(onShareProof)
            Spacer(Modifier.height(28.dp))
            ActivityHeader()
            Spacer(Modifier.height(8.dp))
            activity.forEach { ActivityRow(it); Spacer(Modifier.height(6.dp)) }
            Spacer(Modifier.height(24.dp))
        }
        BottomNav(onSettings)
    }
}

@androidx.compose.runtime.Composable
private fun SyncBanner(status: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Umbra.SurfaceElevated).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Umbra.Success))
        Spacer(Modifier.width(10.dp))
        Text(status, color = Umbra.TextMuted, fontSize = 12.sp)
    }
}

@androidx.compose.runtime.Composable
private fun ShareProofButton(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Umbra.Surface)
            .clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Shield, null, tint = Umbra.Primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Prove funds", color = Umbra.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("Prove you hold a note — reveals nothing else", color = Umbra.TextFaint, fontSize = 12.sp)
        }
    }
}

@androidx.compose.runtime.Composable
private fun TopBar(handle: String, onOpenWallet: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Umbra.Primary, Umbra.PrimaryDeep))),
            contentAlignment = Alignment.Center,
        ) { Text("A", color = Umbra.TextPrimary, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = Umbra.Primary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("Stellar · Testnet", color = Umbra.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Text(handle, color = Umbra.TextFaint, fontFamily = Umbra.Mono, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Outlined.Settings, "Recovery phrase", tint = Umbra.TextMuted,
            modifier = Modifier.clickable { onOpenWallet() },
        )
    }
}

@androidx.compose.runtime.Composable
private fun BalanceCard(revealed: Boolean, balanceText: String, publicText: String, onToggle: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Umbra.SurfaceElevated, Umbra.Surface)))
            .clickable { onToggle() }
    ) {
        // Purple privacy glow behind the shielded balance (design: radial wash).
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = (-20).dp, y = (-40).dp)
                .size(240.dp)
                .background(
                    Brush.radialGradient(listOf(Umbra.Primary.copy(alpha = 0.20f), Color.Transparent)),
                    CircleShape,
                )
        )
        Column(Modifier.padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shielded balance", color = Umbra.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Icon(
                if (revealed) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                null, tint = Umbra.TextFaint, modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (revealed) balanceText else "••••••",
                color = Umbra.TextPrimary,
                fontFamily = Umbra.Display,
                fontSize = 44.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1).sp,
            )
            Spacer(Modifier.width(10.dp))
            Text("XLM", color = Umbra.TextMuted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (revealed) "Tap to hide" else "Tap to reveal · hidden for privacy",
            color = Umbra.TextFaint, fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Umbra.Border))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Public, null, tint = Umbra.Public, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Public balance", color = Umbra.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "$publicText XLM", color = Umbra.TextSecondary, fontFamily = Umbra.Display,
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
        }
        }
    }
}

// Action-button visual language (from the design system):
//   Public  = amber wash + amber-dark hairline (deposit reveals on-chain)
//   Primary = filled purple gradient + glow (Send — the headline action)
//   Neutral = flat surface + hairline (Receive / Withdraw)
private enum class ActionStyle { Public, Primary, Neutral }

@androidx.compose.runtime.Composable
private fun ActionRow(onSend: () -> Unit, onDeposit: () -> Unit, onWithdraw: () -> Unit, onReceive: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ActionButton(Icons.Filled.SouthWest, "Deposit", ActionStyle.Public, onClick = onDeposit)
        ActionButton(Icons.Filled.ArrowUpward, "Send", ActionStyle.Primary, onClick = onSend)
        ActionButton(Icons.Filled.QrCode2, "Receive", ActionStyle.Neutral, onClick = onReceive)
        ActionButton(Icons.Filled.NorthEast, "Withdraw", ActionStyle.Neutral, onClick = onWithdraw)
    }
}

@androidx.compose.runtime.Composable
private fun ActionButton(icon: ImageVector, label: String, style: ActionStyle, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        var box = Modifier.size(56.dp)
        // Glow only on the filled primary action.
        if (style == ActionStyle.Primary) {
            box = box.shadow(12.dp, shape, clip = false, ambientColor = Umbra.Primary, spotColor = Umbra.Primary)
        }
        box = box.clip(shape)
        box = when (style) {
            ActionStyle.Public -> box.background(Umbra.PublicWash).border(1.dp, Umbra.PublicBorder, shape)
            ActionStyle.Primary -> box.background(Brush.linearGradient(listOf(Umbra.Primary, Umbra.PrimaryDeep)))
            ActionStyle.Neutral -> box.background(Umbra.Surface).border(1.dp, Umbra.SurfaceHi, shape)
        }
        val iconTint = when (style) {
            ActionStyle.Public -> Umbra.Public
            ActionStyle.Primary -> Umbra.IconOnPrimary
            ActionStyle.Neutral -> Umbra.TextSecondary
        }
        Box(box.clickable { onClick() }, contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = iconTint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            color = if (style == ActionStyle.Primary) Umbra.TextPrimary else Umbra.TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (style == ActionStyle.Primary) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@androidx.compose.runtime.Composable
private fun ActivityHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Recent activity", color = Umbra.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text("See all", color = Umbra.Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@androidx.compose.runtime.Composable
private fun ActivityRow(a: Activity) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.Surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Umbra.SurfaceHi),
            contentAlignment = Alignment.Center,
        ) { Icon(a.icon, null, tint = Umbra.TextSecondary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, color = Umbra.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val badge = if (a.private) "Private" else "Public"
                val badgeColor = if (a.private) Umbra.Primary else Umbra.Public
                Icon(
                    if (a.private) Icons.Filled.Lock else Icons.Filled.Public,
                    null, tint = badgeColor, modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(badge, color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                a.subtitle?.let {
                    Text(" · $it", color = Umbra.TextFaint, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                a.amount,
                color = if (a.positive) Umbra.Success else Umbra.TextPrimary,
                fontFamily = Umbra.Display,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(a.time, color = Umbra.TextFaint, fontSize = 11.sp)
        }
    }
}

@androidx.compose.runtime.Composable
private fun BottomNav(onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Umbra.Surface).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavItem(Icons.Filled.Home, "Home", true) {}
        NavItem(Icons.Filled.ReceiptLong, "Activity", false) {}
        NavItem(Icons.Filled.Contacts, "People", false) {}
        NavItem(Icons.Filled.Settings, "Settings", false, onSettings)
    }
}

@androidx.compose.runtime.Composable
private fun NavItem(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val c = if (active) Umbra.Primary else Umbra.TextFaint
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Icon(icon, label, tint = c, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = c, fontSize = 11.sp)
    }
}
