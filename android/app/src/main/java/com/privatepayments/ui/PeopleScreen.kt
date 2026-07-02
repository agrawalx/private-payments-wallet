package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.WalletMode
import com.privatepayments.ui.theme.umbraScreen

/**
 * The People tab — a local address book (save a name against a public G…
 * and/or shielded "stella:" address, then send straight to a contact). Not
 * built yet; this is the placeholder so the tab isn't a dead tap target.
 */
@Composable
fun PeopleScreen(
    address: String,
    accountLabel: String,
    mode: WalletMode,
    onSettings: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
) {
    val isPublic = mode == WalletMode.Public
    Column(Modifier.fillMaxSize().umbraScreen()) {
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            TopBar(address, accountLabel, isPublic, onSettings)
            Spacer(Modifier.height(60.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Umbra.SurfaceElevated),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Contacts, null, tint = Umbra.TextFaint, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.height(18.dp))
                Text("People", color = Umbra.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = Umbra.Display)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Save contacts by name — public G… or shielded stella: address — and send to them directly. Coming soon.",
                    color = Umbra.TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        BottomNav(HomeTab.People, onSelectTab, onSettings)
    }
}
