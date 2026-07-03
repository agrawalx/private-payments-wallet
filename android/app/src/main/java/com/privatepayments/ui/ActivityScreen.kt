package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.WalletMode
import com.privatepayments.ui.theme.umbraScreen

/**
 * The Activity tab — the full history behind Home's short "Recent activity"
 * preview. Mode-aware like Home: the slider here switches between the same
 * two histories (public Horizon payments vs. shielded notes) without leaving
 * the tab.
 */
@Composable
fun ActivityScreen(
    address: String,
    accountLabel: String,
    mode: WalletMode,
    onModeChange: (WalletMode, Offset) -> Unit,
    activity: List<Activity>,
    publicActivity: List<Activity>,
    onSettings: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onActivityTap: (Activity) -> Unit = {},
    syncStatus: SyncStatus = SyncStatus(SyncState.Ok, ""),
    onRetrySync: () -> Unit = {},
    initialSyncing: Boolean = false,
) {
    val isPublic = mode == WalletMode.Public
    val shown = if (isPublic) publicActivity else activity
    var filter by remember(isPublic) { mutableStateOf<ActivityKind?>(null) }
    val filtered = if (filter == null) shown else shown.filter { it.kind == filter }

    Column(Modifier.fillMaxSize().umbraScreen()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            TopBar(address, accountLabel, isPublic, onSettings)
            Spacer(Modifier.height(14.dp))
            SyncBanner(syncStatus, onRetrySync)
            Spacer(Modifier.height(14.dp))
            ModeSlider(mode, onModeChange, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(20.dp))
            Text("Activity", color = Umbra.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = Umbra.Display)
            Spacer(Modifier.height(4.dp))
            Text(
                if (isPublic) "Public XLM payments on Stellar testnet" else "Shielded notes — deposits, receives, sends",
                color = Umbra.TextMuted, fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            // Scrollable: 5 chips overflow narrow screens, and a squeezed chip
            // wraps its label vertically instead of shrinking.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isPublic) {
                    FilterChip("All", filter == null) { filter = null }
                    FilterChip("Sent", filter == ActivityKind.PublicSent) { filter = ActivityKind.PublicSent }
                    FilterChip("Received", filter == ActivityKind.PublicReceived) { filter = ActivityKind.PublicReceived }
                } else {
                    FilterChip("All", filter == null) { filter = null }
                    FilterChip("Deposits", filter == ActivityKind.Deposit) { filter = ActivityKind.Deposit }
                    FilterChip("Received", filter == ActivityKind.Received) { filter = ActivityKind.Received }
                    FilterChip("Change", filter == ActivityKind.Change) { filter = ActivityKind.Change }
                    FilterChip("Sent", filter == ActivityKind.Transferred) { filter = ActivityKind.Transferred }
                }
            }
            Spacer(Modifier.height(18.dp))
            if (shown.isEmpty() && initialSyncing) {
                repeat(3) { ShimmerRow(); Spacer(Modifier.height(6.dp)) }
            } else if (filtered.isEmpty()) {
                Text(
                    if (isPublic) "No public payments yet" else "No shielded notes yet",
                    color = Umbra.TextFaint, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            } else if (isPublic) {
                // Public rows carry a real "YYYY-MM-DD" date — group under
                // day headers.
                filtered.groupBy { it.time }.forEach { (date, rows) ->
                    Text(
                        dayLabel(date), color = Umbra.TextFaint, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                    )
                    rows.forEach { ActivityRow(it, onActivityTap); Spacer(Modifier.height(6.dp)) }
                }
            } else {
                // Shielded notes now carry a real timestamp once scanned back
                // from the chain — group under day headers same as public.
                // Notes without a timestamp yet (e.g. scanned before Phase 1
                // added timestamps) fall into a trailing flat "Earlier" group.
                val (dated, undated) = filtered.partition { it.timestamp != null }
                dated.groupBy { dateOf(it.timestamp!!) }.forEach { (date, rows) ->
                    Text(
                        dayLabel(date), color = Umbra.TextFaint, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                    )
                    rows.forEach { ActivityRow(it, onActivityTap); Spacer(Modifier.height(6.dp)) }
                }
                if (undated.isNotEmpty()) {
                    Text(
                        "Earlier", color = Umbra.TextFaint, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                    )
                    undated.forEach { ActivityRow(it, onActivityTap); Spacer(Modifier.height(6.dp)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomNav(HomeTab.Activity, onSelectTab, onSettings)
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (selected) Umbra.Primary else Umbra.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) Umbra.IconOnPrimary else Umbra.TextMuted,
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
        )
    }
}
