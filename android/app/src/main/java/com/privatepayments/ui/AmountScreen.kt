package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
 * **2-note circuit limit** for spends: a single payment can use at most 2 input
 * notes, so the max is the sum of the 2 largest unspent notes. Over that, the
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
    onConfirm: (amountStroops: Long, recipient: String) -> Unit,
    onCancel: () -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var recipient by remember { mutableStateOf("") }
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
            val maxXlm = maxStroops / 1e7
            Text(
                if (overCap) "Over the limit. One payment spends at most 2 notes — max %.4f XLM. Send less or deposit more.".format(maxXlm)
                else "Max in one payment: %.4f XLM · the pool spends at most 2 notes per transaction".format(maxXlm),
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
                BasicTextField(
                    value = recipient,
                    onValueChange = { recipient = it.trim() },
                    textStyle = TextStyle(color = Umbra.TextPrimary, fontFamily = Umbra.Mono, fontSize = 14.sp),
                    cursorBrush = SolidColor(accent), singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (recipient.isEmpty()) Text(recipientHint, color = Umbra.TextFaint, fontFamily = Umbra.Mono, fontSize = 14.sp)
                        inner()
                    },
                )
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
