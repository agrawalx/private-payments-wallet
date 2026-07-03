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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.WalletMode
import com.privatepayments.ui.theme.cardDepth
import com.privatepayments.ui.theme.elevatedCard
import com.privatepayments.ui.theme.umbraScreen
import kotlinx.coroutines.launch

/** What kind of event an [Activity] row represents — drives grouping/labels
 *  beyond the free-text `title`. */
enum class ActivityKind { Deposit, Received, Transferred, Change, PublicSent, PublicReceived }

data class Activity(
    val icon: ImageVector,
    val title: String,
    val private: Boolean,
    val amount: String,
    val positive: Boolean,
    val time: String,
    val subtitle: String? = null,
    /** stellar.expert link for this tx, if known (public activity only). */
    val url: String? = null,
    val kind: ActivityKind = ActivityKind.Received,
    /** Full ISO timestamp, when known (shielded notes may not have one yet). */
    val timestamp: String? = null,
    val txHash: String? = null,
    val leafIndex: Long? = null,
    val commitment: String? = null,
    val pending: Boolean = false,
)

/** The three bottom-nav destinations that live "inside" Home (Settings is a
 *  separate pushed screen, not a tab — see [Screen] in MainActivity). */
enum class HomeTab { Home, Activity, People }

@androidx.compose.runtime.Composable
fun HomeScreen(
    address: String,
    accountLabel: String,
    balanceText: String,
    publicText: String,
    activity: List<Activity>,
    syncStatus: SyncStatus,
    onSend: () -> Unit,
    onDeposit: () -> Unit,
    onWithdraw: () -> Unit,
    onReceive: () -> Unit,
    onSettings: () -> Unit,
    onShareProof: () -> Unit,
    registered: Boolean = true,
    onRegister: () -> Unit = {},
    onFund: suspend () -> Pair<Boolean, String> = { false to "Fund unavailable" },
    mode: WalletMode = WalletMode.Shielded,
    onModeChange: (WalletMode, Offset) -> Unit = { _, _ -> },
    onPublicSend: () -> Unit = {},
    publicActivity: List<Activity> = emptyList(),
    onSelectTab: (HomeTab) -> Unit = {},
    onActivityTap: (Activity) -> Unit = {},
    onRetrySync: () -> Unit = {},
    initialSyncing: Boolean = false,
) {
    var revealed by remember { mutableStateOf(false) }
    val isPublic = mode == WalletMode.Public

    Column(
        Modifier
            .fillMaxSize()
            .umbraScreen()
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            TopBar(address, accountLabel, isPublic, onSettings)
            Spacer(Modifier.height(14.dp))
            ModeSlider(mode, onModeChange, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(14.dp))
            SyncBanner(syncStatus, onRetrySync)
            // The mode switch's visual transition is a circular reveal wrapping
            // this whole tab family (see MainActivity's ModeReveal) — this branch
            // just snaps to whichever face is active, no local animation needed.
            if (isPublic) {
                // ☀ Daylight: your normal Stellar wallet — public XLM, classic send.
                Spacer(Modifier.height(14.dp))
                PublicBalanceCard(publicText)
                Spacer(Modifier.height(10.dp))
                FundButton(onFund)
                Spacer(Modifier.height(20.dp))
                PublicActionRow(onSend = onPublicSend, onReceive = onReceive)
            } else {
                // 🌙 Umbra: the shielded pool — deposit in, send/withdraw privately.
                if (!registered) {
                    Spacer(Modifier.height(14.dp))
                    RegisterBanner(onRegister)
                }
                Spacer(Modifier.height(14.dp))
                BalanceCard(revealed, balanceText, initialSyncing) { revealed = !revealed }
                Spacer(Modifier.height(20.dp))
                ActionRow(onSend = onSend, onDeposit = onDeposit, onWithdraw = onWithdraw, onReceive = onReceive)
                Spacer(Modifier.height(14.dp))
                ShareProofButton(onShareProof)
            }
            Spacer(Modifier.height(28.dp))
            ActivityHeader(onSeeAll = { onSelectTab(HomeTab.Activity) })
            Spacer(Modifier.height(8.dp))
            val shown = if (isPublic) publicActivity else activity
            if (shown.isEmpty() && initialSyncing) {
                repeat(3) { ShimmerRow(); Spacer(Modifier.height(6.dp)) }
            } else if (shown.isEmpty()) {
                Text(
                    if (isPublic) "No public payments yet" else "No shielded notes yet",
                    color = Umbra.TextFaint, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            } else {
                // Preview only — "See all" / the Activity tab has the full list.
                shown.take(5).forEach { ActivityRow(it, onActivityTap); Spacer(Modifier.height(6.dp)) }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomNav(HomeTab.Home, onSelectTab, onSettings)
    }
}

@androidx.compose.runtime.Composable
private fun FundButton(onFund: suspend () -> Pair<Boolean, String>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Umbra.Surface)
            .border(1.dp, Umbra.Border, RoundedCornerShape(14.dp))
            .clickable(enabled = !busy) {
                scope.launch {
                    busy = true
                    val (_, message) = runCatching { onFund() }
                        .getOrDefault(false to "Fund failed unexpectedly")
                    busy = false
                    android.widget.Toast.makeText(
                        ctx, message, android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Umbra.Public, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Funding…", color = Umbra.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        } else {
            Icon(Icons.Filled.WaterDrop, null, tint = Umbra.Public, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Fund from testnet", color = Umbra.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@androidx.compose.runtime.Composable
private fun RegisterBanner(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Umbra.PrimaryWash)
            .border(1.dp, Umbra.Primary, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Shield, null, tint = Umbra.Primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Register to start sending", color = Umbra.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("One-time enrollment — needed before you deposit or send", color = Umbra.TextFaint, fontSize = 12.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Umbra.Primary, modifier = Modifier.size(20.dp))
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
internal fun TopBar(address: String, accountLabel: String, isPublic: Boolean, onOpenWallet: () -> Unit) {
    val ctx = LocalContext.current
    val short = shortAddress(address)
    Column {
        // Avatar (active account) left · network centered · settings right.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.align(Alignment.CenterStart).size(40.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Umbra.Primary, Umbra.PrimaryDeep)))
                    .clickable { onOpenWallet() },
                contentAlignment = Alignment.Center,
            ) { Text(accountLabel, color = Umbra.IconOnPrimary, fontWeight = FontWeight.Bold) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isPublic) Icons.Filled.Public else Icons.Filled.Lock,
                    null, tint = Umbra.Primary, modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (isPublic) "Stellar · Public" else "Stellar · Shielded",
                    color = Umbra.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }

            Box(
                Modifier.align(Alignment.CenterEnd).size(40.dp).clip(CircleShape)
                    .background(Umbra.Surface).clickable { onOpenWallet() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Settings, "Settings", tint = Umbra.TextMuted, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.height(10.dp))
        // Copyable address chip (centered).
        Row(
            Modifier.fillMaxWidth().wrapContentWidth()
                .clip(RoundedCornerShape(999.dp)).background(Umbra.Surface)
                .clickable { copyToClipboard(ctx, "Address", address) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(short, color = Umbra.TextMuted, fontFamily = Umbra.Mono, fontSize = 12.sp)
            Spacer(Modifier.width(7.dp))
            Icon(Icons.Filled.ContentCopy, "Copy address", tint = Umbra.TextFaint, modifier = Modifier.size(13.dp))
        }
    }
}

@androidx.compose.runtime.Composable
private fun BalanceCard(revealed: Boolean, balanceText: String, syncing: Boolean = false, onToggle: () -> Unit) {
    // Design's bordered balance card: dark purple gradient box + hairline, a small
    // "Shielded balance" label with an eye, a left-aligned number, and a glow that
    // pools in the top-right corner (by the eye) and fades inward.
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .cardDepth(shape)
            .clip(shape) // clips the corner glow (design: overflow hidden)
            .background(Brush.linearGradient(listOf(Color(0xFF15131F), Color(0xFF101015))))
            .border(1.dp, Color(0xFF2A2350), shape)
            .clickable { onToggle() },
    ) {
        // Corner glow anchored at the eye (top-right), fading to transparent — subtle.
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 30.dp, y = (-38).dp).size(130.dp)
                .background(Brush.radialGradient(listOf(Umbra.Primary.copy(alpha = 0.16f), Color.Transparent)), CircleShape),
        )
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = Umbra.PrimaryLight, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Shielded balance", color = Umbra.PrimaryLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (revealed) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    null, tint = Umbra.TextFaint, modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                if (revealed && syncing && balanceText == "•.••••") {
                    ShimmerBox(Modifier.width(140.dp).height(38.dp).clip(RoundedCornerShape(8.dp)))
                } else {
                    Text(
                        if (revealed) balanceText else "••••••",
                        color = Umbra.TextPrimary, fontFamily = Umbra.Display,
                        fontSize = 42.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("XLM", color = Umbra.TextFaint, fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (revealed) "Tap to hide" else "Tap to reveal · hidden for privacy",
                color = Umbra.TextFaint, fontSize = 12.5.sp,
            )
        }
    }
}

/** ☀ Daylight balance card — light, joyful, the public XLM balance as hero. */
@androidx.compose.runtime.Composable
private fun PublicBalanceCard(publicText: String) {
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.fillMaxWidth().elevatedCard(shape)) {
        // Warm amber corner glow (the Daylight counterpart of the purple pool glow).
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 30.dp, y = (-38).dp).size(130.dp)
                .background(Brush.radialGradient(listOf(Umbra.Public.copy(alpha = 0.18f), Color.Transparent)), CircleShape),
        )
        Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Public, null, tint = Umbra.Public, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Public balance", color = Umbra.Public, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.WbSunny, null, tint = Umbra.Public, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    publicText, color = Umbra.TextPrimary, fontFamily = Umbra.Display,
                    fontSize = 42.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp,
                )
                Spacer(Modifier.width(8.dp))
                Text("XLM", color = Umbra.TextMuted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text("Spendable · on Stellar testnet", color = Umbra.TextMuted, fontSize = 12.5.sp)
        }
    }
}

/** ☀ Daylight actions — classic send + receive (no proofs, no pool). */
@androidx.compose.runtime.Composable
private fun PublicActionRow(onSend: () -> Unit, onReceive: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(56.dp, Alignment.CenterHorizontally),
    ) {
        ActionButton(Icons.Filled.ArrowUpward, "Send", ActionStyle.Primary, onClick = onSend)
        ActionButton(Icons.Filled.QrCode2, "Receive", ActionStyle.Neutral, onClick = onReceive)
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
private fun ActivityHeader(onSeeAll: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Recent activity", color = Umbra.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(
            "See all", color = Umbra.Primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onSeeAll),
        )
    }
}

@androidx.compose.runtime.Composable
internal fun ActivityRow(a: Activity, onClick: ((Activity) -> Unit)? = null) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.Surface)
            .then(
                when {
                    onClick != null -> Modifier.clickable { onClick(a) }
                    a.url != null -> Modifier.clickable { openUrl(ctx, a.url) }
                    else -> Modifier
                },
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Umbra.SurfaceHi),
            contentAlignment = Alignment.Center,
        ) {
            if (a.pending) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Umbra.Warning, strokeWidth = 2.dp)
            } else {
                Icon(a.icon, null, tint = Umbra.TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
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
            if (a.pending) {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(Umbra.Warning.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) { Text("Pending", color = Umbra.Warning, fontSize = 10.sp, fontWeight = FontWeight.Medium) }
            } else {
                Text(a.time, color = Umbra.TextFaint, fontSize = 11.sp)
            }
        }
        if (a.url != null) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.OpenInNew, "View on stellar.expert", tint = Umbra.TextFaint, modifier = Modifier.size(13.dp))
        }
    }
}

@androidx.compose.runtime.Composable
internal fun BottomNav(activeTab: HomeTab, onSelectTab: (HomeTab) -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Umbra.Surface).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavItem(Icons.Filled.Home, "Home", activeTab == HomeTab.Home) { onSelectTab(HomeTab.Home) }
        NavItem(Icons.Filled.ReceiptLong, "Activity", activeTab == HomeTab.Activity) { onSelectTab(HomeTab.Activity) }
        NavItem(Icons.Filled.Contacts, "People", activeTab == HomeTab.People) { onSelectTab(HomeTab.People) }
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
