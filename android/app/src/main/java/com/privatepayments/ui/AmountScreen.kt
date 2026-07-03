package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.umbraScreen

/**
 * Amount (+ optional recipient) entry for deposit / send / withdraw. Enforces the
 * **4-note circuit limit** for spends: a single payment can use at most 4 input
 * notes, so the max is the sum of the 4 largest unspent notes. Over that, the
 * screen blocks + explains rather than failing at the contract.
 */
@Composable
fun AmountScreen(
    title: String,
    isPublic: Boolean,
    /** Max spendable in one tx (stroops); null = unbounded (deposit). */
    maxStroops: Long?,
    /** If non-null, show a recipient field with this label + hint. */
    recipientLabel: String?,
    recipientHint: String,
    /** True if a blank recipient is valid (transfer → re-shield to self). */
    recipientOptional: Boolean = false,
    /** Prefills the recipient field — e.g. a tapped contact's address. */
    initialRecipient: String = "",
    /** Your other wallet accounts (label to address) — quick-pick chips so
     *  moving funds between your own accounts never needs a manual copy/paste. */
    myAddresses: List<Pair<String, String>> = emptyList(),
    onConfirm: (amountStroops: Long, recipient: String) -> Unit,
    onCancel: () -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var recipient by remember { mutableStateOf(initialRecipient) }
    val accent = if (isPublic) Umbra.Public else Umbra.Primary

    val stroops: Long? = amount.toDoubleOrNull()?.let { (it * 1e7).toLong() }?.takeIf { it > 0 }
    val overCap = maxStroops != null && stroops != null && stroops > maxStroops
    val recipientOk = recipientLabel == null || recipientOptional || recipient.isNotBlank()
    val canContinue = stroops != null && !overCap && recipientOk

    Column(Modifier.fillMaxSize().umbraScreen().padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isPublic) Icons.Filled.Public else Icons.Filled.Lock, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(28.dp))

        Text("Amount", color = Umbra.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = amount,
                onValueChange = { s -> amount = s.filter { it.isDigit() || it == '.' } },
                textStyle = TextStyle(color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 44.sp, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (amount.isEmpty()) Text("0.00", color = Umbra.TextFaint, fontFamily = Umbra.Display, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
                    inner()
                },
            )
            Text("XLM", color = Umbra.TextMuted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (maxStroops != null) {
            val maxXlm = xlm(maxStroops)
            Text(
                if (overCap) "Over the limit. One payment spends at most 4 notes — max $maxXlm XLM. Send less or deposit more."
                else "Max in one payment: $maxXlm XLM · the pool spends at most 4 notes per transaction",
                color = if (overCap) Umbra.Error else Umbra.TextFaint, fontSize = 12.sp, lineHeight = 17.sp,
            )
        } else {
            Text("Deposited from your public balance.", color = Umbra.TextFaint, fontSize = 12.sp)
        }

        if (recipientLabel != null) {
            Spacer(Modifier.height(22.dp))
            Text(recipientLabel, color = Umbra.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Umbra.Surface).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = recipient,
                        onValueChange = { recipient = it.trim() },
                        textStyle = TextStyle(color = Umbra.TextPrimary, fontFamily = Umbra.Mono, fontSize = 14.sp),
                        cursorBrush = SolidColor(accent), singleLine = true,
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (recipient.isEmpty()) Text(recipientHint, color = Umbra.TextFaint, fontFamily = Umbra.Mono, fontSize = 14.sp)
                            inner()
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    ScanIconButton { scanned -> recipient = scanned.trim() }
                }
            }
            if (myAddresses.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Your other addresses", color = Umbra.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    myAddresses.forEach { (label, addr) ->
                        Row(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(Umbra.Surface)
                                .clickable { recipient = addr }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(18.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) { Text(label, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                shortAddress(addr, 5, 4),
                                color = Umbra.TextSecondary, fontFamily = Umbra.Mono, fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(if (canContinue) accent else Umbra.SurfaceElevated)
                .clickable(enabled = canContinue) { onConfirm(stroops!!, recipient.trim()) }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Continue", color = if (canContinue) Umbra.Bg else Umbra.TextFaint, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.SurfaceElevated)
                .clickable(onClick = onCancel).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Cancel", color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
    }
}
