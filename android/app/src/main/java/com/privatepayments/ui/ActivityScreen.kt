package com.privatepayments.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.WalletMode
import com.privatepayments.ui.theme.umbraScreen
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
) {
    val isPublic = mode == WalletMode.Public
    val shown = if (isPublic) publicActivity else activity

    Column(Modifier.fillMaxSize().umbraScreen()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            TopBar(address, accountLabel, isPublic, onSettings)
            Spacer(Modifier.height(14.dp))
            ModeSlider(mode, onModeChange, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(20.dp))
            Text("Activity", color = Umbra.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = Umbra.Display)
            Spacer(Modifier.height(4.dp))
            Text(
                if (isPublic) "Public XLM payments on Stellar testnet" else "Shielded notes — deposits, receives, sends",
                color = Umbra.TextMuted, fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))
            if (shown.isEmpty()) {
                Text(
                    if (isPublic) "No public payments yet" else "No shielded notes yet",
                    color = Umbra.TextFaint, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            } else if (isPublic) {
                // Public rows carry a real "YYYY-MM-DD" date — group under
                // day headers. Shielded notes only have a leaf index (no
                // wall-clock time is ever derived from chain data), so they
                // stay a flat, leaf-ordered list below.
                shown.groupBy { it.time }.forEach { (date, rows) ->
                    Text(
                        dayLabel(date), color = Umbra.TextFaint, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                    )
                    rows.forEach { ActivityRow(it); Spacer(Modifier.height(6.dp)) }
                }
            } else {
                shown.forEach { ActivityRow(it); Spacer(Modifier.height(6.dp)) }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomNav(HomeTab.Activity, onSelectTab, onSettings)
    }
}

/** "YYYY-MM-DD" → "Today" / "Yesterday" / "Mon, Jan 5". Falls back to the raw string. */
private fun dayLabel(iso: String): String {
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
}
