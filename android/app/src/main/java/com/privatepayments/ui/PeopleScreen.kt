package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.state.Contact
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.WalletMode
import com.privatepayments.ui.theme.elevatedCard
import com.privatepayments.ui.theme.umbraScreen

/**
 * The People tab — a local address book (save a name against a public G…
 * and/or shielded "stella:" address, then send straight to a contact). All
 * storage is on-device (see `ContactStore`) — nothing here ever leaves the phone.
 */
@Composable
fun PeopleScreen(
    address: String,
    accountLabel: String,
    mode: WalletMode,
    contacts: List<Contact>,
    onAddContact: (name: String, publicAddress: String?, shieldedAddress: String?) -> Unit,
    onRemoveContact: (String) -> Unit,
    onUpdateContact: (Contact) -> Unit = {},
    onSendToPublic: (String) -> Unit,
    onSendToShielded: (String) -> Unit,
    onSettings: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    syncStatus: SyncStatus = SyncStatus(SyncState.Ok, ""),
    onRetrySync: () -> Unit = {},
) {
    val isPublic = mode == WalletMode.Public
    var showAdd by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().umbraScreen()) {
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            TopBar(address, accountLabel, isPublic, onSettings)
            Spacer(Modifier.height(14.dp))
            SyncBanner(syncStatus, onRetrySync)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("People", color = Umbra.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = Umbra.Display)
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(999.dp)).background(Umbra.Primary)
                        .clickable { showAdd = !showAdd }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (showAdd) Icons.Filled.Close else Icons.Filled.Add, null, tint = Umbra.IconOnPrimary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (showAdd) "Cancel" else "Add", color = Umbra.IconOnPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Saved locally on this device only", color = Umbra.TextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))

            if (showAdd) {
                ContactForm(
                    onSave = { name, pub, shielded -> onAddContact(name, pub, shielded); showAdd = false },
                    onCancel = { showAdd = false },
                )
                Spacer(Modifier.height(20.dp))
            }

            if (contacts.isEmpty() && !showAdd) {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(Umbra.SurfaceElevated),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Contacts, null, tint = Umbra.TextFaint, modifier = Modifier.size(28.dp)) }
                    Spacer(Modifier.height(18.dp))
                    Text("No contacts yet", color = Umbra.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Save a name against a public G… or shielded stella: address, then send to them directly.",
                        color = Umbra.TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else {
                contacts.forEach { c ->
                    ContactRow(
                        c,
                        onSendToPublic = { onSendToPublic(c.publicAddress!!) },
                        onSendToShielded = { onSendToShielded(c.shieldedAddress!!) },
                        onRemove = { onRemoveContact(c.id) },
                        onUpdate = onUpdateContact,
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        BottomNav(HomeTab.People, onSelectTab, onSettings)
    }
}

@Composable
private fun ContactRow(
    c: Contact,
    onSendToPublic: () -> Unit,
    onSendToShielded: () -> Unit,
    onRemove: () -> Unit,
    onUpdate: (Contact) -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().elevatedCard(RoundedCornerShape(16.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Umbra.Primary, Umbra.PrimaryDeep))),
                contentAlignment = Alignment.Center,
            ) { Text(c.name.take(1).uppercase(), color = Umbra.IconOnPrimary, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, color = Umbra.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Row {
                    if (c.publicAddress != null) RailBadge("Public", Umbra.Public)
                    if (c.publicAddress != null && c.shieldedAddress != null) Spacer(Modifier.width(6.dp))
                    if (c.shieldedAddress != null) RailBadge("Shielded", Umbra.Primary)
                }
            }
            Icon(
                Icons.Filled.Edit, "Edit contact", tint = Umbra.TextFaint,
                modifier = Modifier.size(16.dp).clickable { editing = !editing; confirmRemove = false },
            )
            Spacer(Modifier.width(14.dp))
            Icon(
                Icons.Filled.Close, "Remove contact", tint = Umbra.TextFaint,
                modifier = Modifier.size(16.dp).clickable { confirmRemove = !confirmRemove; editing = false },
            )
        }
        if (editing) {
            Spacer(Modifier.height(12.dp))
            ContactForm(
                initial = c,
                onSave = { name, pub, shielded -> onUpdate(c.copy(name = name, publicAddress = pub, shieldedAddress = shielded)); editing = false },
                onCancel = { editing = false },
            )
        } else if (confirmRemove) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "Remove ${c.name}?", color = Umbra.TextMuted, fontSize = 12.sp,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                )
                Text(
                    "Remove", color = Umbra.Error, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onRemove() }.padding(horizontal = 8.dp),
                )
            }
        } else {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (c.publicAddress != null) {
                    SendChip("Send public", Umbra.Public, Modifier.weight(1f), onSendToPublic)
                }
                if (c.shieldedAddress != null) {
                    SendChip("Send shielded", Umbra.Primary, Modifier.weight(1f), onSendToShielded)
                }
            }
        }
    }
}

@Composable
private fun RailBadge(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (label == "Public") Icons.Filled.Public else Icons.Filled.Lock,
            null, tint = color, modifier = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SendChip(label: String, accent: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(Umbra.Surface)
            .clickable(onClick = onClick).padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}

/** A shielded "stella:" address is base64 — public G-addresses are 56 chars, G + [A-Z2-7]{55}. */
private val publicAddressRegex = Regex("G[A-Z2-7]{55}")

@Composable
private fun ContactForm(
    initial: Contact? = null,
    onSave: (String, String?, String?) -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var pub by remember { mutableStateOf(initial?.publicAddress ?: "") }
    var shielded by remember { mutableStateOf(initial?.shieldedAddress ?: "") }
    val canSave = name.isNotBlank() && (pub.isNotBlank() || shielded.isNotBlank())
    val haptics = rememberHaptics()
    val editing = initial != null

    Column(Modifier.fillMaxWidth().elevatedCard(RoundedCornerShape(18.dp)).padding(16.dp)) {
        Text(if (editing) "Edit contact" else "New contact", color = Umbra.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        val scan = rememberQrScanner { scanned ->
            val text = scanned.trim()
            when {
                text.startsWith("stella:") -> shielded = text
                publicAddressRegex.matches(text) -> pub = text
                else -> Toast.makeText(ctx, "Not a Stella address", Toast.LENGTH_SHORT).show()
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Umbra.Surface)
                .clickable { scan() }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.QrCodeScanner, null, tint = Umbra.TextSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text("Scan QR", color = Umbra.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(12.dp))
        FormField("Name", name, "e.g. Alex") { name = it }
        Spacer(Modifier.height(10.dp))
        FormField("Public address (G…)", pub, "optional — G… Stellar address", mono = true) { pub = it.trim() }
        Spacer(Modifier.height(10.dp))
        FormField("Shielded address", shielded, "optional — stella: shielded address", mono = true) { shielded = it.trim() }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Umbra.Surface)
                    .clickable { onCancel() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Cancel", color = Umbra.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (canSave) Umbra.Primary else Umbra.SurfaceElevated)
                    .clickable(enabled = canSave) { haptics.confirm(); onSave(name.trim(), pub.ifBlank { null }, shielded.ifBlank { null }) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (editing) "Save changes" else "Save",
                    color = if (canSave) Umbra.IconOnPrimary else Umbra.TextFaint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun FormField(label: String, value: String, hint: String, mono: Boolean = false, onChange: (String) -> Unit) {
    Column {
        Text(label, color = Umbra.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Umbra.Surface).padding(12.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(
                    color = Umbra.TextPrimary,
                    fontFamily = if (mono) Umbra.Mono else Umbra.Body,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(Umbra.Primary), singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(hint, color = Umbra.TextFaint, fontFamily = if (mono) Umbra.Mono else Umbra.Body, fontSize = 13.sp)
                    inner()
                },
            )
        }
    }
}
