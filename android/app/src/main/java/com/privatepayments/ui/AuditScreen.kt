package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatepayments.state.SeqCommitment
import com.privatepayments.state.SeqNullifier
import com.privatepayments.ui.theme.GradientButton
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.accentWash
import com.privatepayments.ui.theme.umbraScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One decrypted incoming note found while auditing a viewing key. The
 * v2-only fields ([spent]/[spentAt]/[isChange]) are left at their defaults
 * for a v1 (`stellaview:`) key, which can only see gross inflow.
 */
data class AuditEntry(
    val seq: Long,
    val amountStroops: Long,
    val closedAt: String,
    val spent: Boolean = false,
    val spentAt: String = "",
    val isChange: Boolean = false,
)

/** A parsed `stellaview:`/`stellaview2:` key. v1 only carries the encryption
 *  key (inflow only); v2 adds the nullifier key (spend visibility). */
private sealed class ParsedViewingKey {
    data class V1(val enc: ByteArray) : ParsedViewingKey()
    data class V2(val enc: ByteArray, val nk: ByteArray) : ParsedViewingKey()
}

/**
 * Phase 6/v2 "audit a wallet" screen — the counterpart to [ViewingKeyScreen].
 * Paste (or scan) someone else's `stellaview:` (v1) or `stellaview2:` (v2) key
 * and locally decrypt every commitment in [commitments] to recover the notes
 * it can see. A v2 key additionally lets us recompute each note's nullifier
 * (via its own `nk`, no spend key needed) and check it against [nullifiers] —
 * the on-chain spend set — so we can show spent/unspent + spend dates, and
 * net out change (a note created in the same tx as one of this key's own
 * spends is a self-transfer, not a genuine receive). Purely on-device; no
 * FFI/network calls beyond the existing bindings.
 */
@Composable
fun AuditScreen(commitments: List<SeqCommitment>, nullifiers: List<SeqNullifier> = emptyList(), onClose: () -> Unit) {
    var keyText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var results by remember { mutableStateOf<List<AuditEntry>?>(null) }
    var isV2 by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().umbraScreen().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Policy, null, tint = Umbra.Primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Audit a wallet", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Paste a viewing key to see the incoming payments it can decrypt.",
            color = Umbra.TextMuted, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))

        Text("Viewing key", color = Umbra.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Umbra.Surface).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = keyText,
                onValueChange = { keyText = it; error = null; results = null },
                textStyle = TextStyle(color = Umbra.TextPrimary, fontSize = 15.sp, fontFamily = Umbra.Mono),
                cursorBrush = SolidColor(Umbra.Primary), singleLine = true,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (keyText.isEmpty()) Text("stellaview2:…", color = Umbra.TextFaint, fontSize = 15.sp)
                    inner()
                },
            )
            Spacer(Modifier.width(10.dp))
            ScanIconButton { keyText = it; error = null; results = null }
        }

        if (error != null) {
            Spacer(Modifier.height(10.dp)); Text(error!!, color = Umbra.Error, fontSize = 13.sp)
        }

        Spacer(Modifier.height(18.dp))
        GradientButton(
            if (busy) progress?.let { (n, total) -> "Scanning $n of $total…" } ?: "Scanning…" else "Run audit",
            Modifier.fillMaxWidth(),
            enabled = !busy,
        ) {
            val key = decodeViewingKey(keyText)
            if (key == null) {
                error = "Not a valid viewing key"
            } else {
                error = null; results = null; busy = true; progress = 0 to commitments.size
                isV2 = key is ParsedViewingKey.V2
                scope.launch {
                    val hits = withContext(Dispatchers.Default) {
                        when (key) {
                            is ParsedViewingKey.V1 -> scanV1(commitments, key.enc) { progress = it }
                            is ParsedViewingKey.V2 -> scanV2(commitments, nullifiers, key.enc, key.nk) { progress = it }
                        }
                    }
                    results = hits; busy = false; progress = null
                }
            }
        }

        results?.let { hits ->
            Spacer(Modifier.height(20.dp))
            if (hits.isEmpty()) {
                Text("No incoming payments found for this key.", color = Umbra.TextFaint, fontSize = 13.sp)
            } else if (!isV2) {
                val total = hits.sumOf { it.amountStroops }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.Surface).padding(18.dp)) {
                    // "notes" not "payments": change outputs from the wallet's own
                    // sends are encrypted to the same key and are cryptographically
                    // indistinguishable from genuine incoming payments (telling them
                    // apart needs nullifier visibility = the spend key). Gross, not net.
                    Text("Total notes received (incl. change)", color = Umbra.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text("${xlm(total)} XLM", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(14.dp))
                hits.sortedByDescending { it.seq }.forEach { e -> AuditRow(e, isV2 = false) }
            } else {
                // v2: nullifier visibility lets us net out change and show spend status.
                // Change only affects INFLOW attribution ("was this a payment?") —
                // an unspent change note is still this key's money, so net unspent
                // sums ALL unspent notes regardless of the change flag. (Excluding
                // change here undercounts: a self-send turns the whole balance into
                // change-flagged notes and the hero would read 0.)
                val received = hits.filter { !it.isChange }
                val change = hits.filter { it.isChange }
                val netUnspent = hits.filter { !it.spent }.sumOf { it.amountStroops }
                val receivedTotal = received.sumOf { it.amountStroops }
                val changeTotal = change.sumOf { it.amountStroops }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.Surface).padding(18.dp)) {
                    Text("Net unspent", color = Umbra.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text("${xlm(netUnspent)} XLM", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Payments received: ${xlm(receivedTotal)} XLM · Change returned to self: ${xlm(changeTotal)} XLM",
                        color = Umbra.TextMuted, fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(14.dp))
                hits.sortedByDescending { it.seq }.forEach { e -> AuditRow(e, isV2 = true) }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().accentWash(
                    Umbra.Warning.copy(alpha = 0.14f), Umbra.Warning.copy(alpha = 0.4f), RoundedCornerShape(12.dp),
                ).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (isV2) {
                        "Shows received notes, change, and spend times for this key. Cannot see where withdrawn funds went. Access cannot be revoked."
                    } else {
                        "Gross inflow only — includes change this wallet paid back to itself, which is indistinguishable from real payments without the spend key. Cannot show spends or balance. Access cannot be revoked."
                    },
                    color = Umbra.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.SurfaceElevated)
                .clickable(onClick = onClose).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Close", color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
        Spacer(Modifier.height(20.dp))
    }
}

/** v1 scan: decrypt every commitment with the enc key. Gross inflow only. */
private fun scanV1(
    commitments: List<SeqCommitment>,
    enc: ByteArray,
    onProgress: (Pair<Int, Int>) -> Unit,
): List<AuditEntry> {
    val found = mutableListOf<AuditEntry>()
    commitments.forEachIndexed { i, ev ->
        runCatching { uniffi.prover_ffi.scanCommitment(ev.value, enc) }.getOrNull()?.let { note ->
            note.amount.toLongOrNull()?.let { amt -> found.add(AuditEntry(ev.seq, amt, ev.closedAt)) }
        }
        if (i % 25 == 0) onProgress(i + 1 to commitments.size)
    }
    return found
}

/**
 * v2 scan: decrypt with the enc key, then use `nk` to recompute each hit's
 * own nullifier (no spend key needed) — see `computeNoteNullifier`. Leaf
 * index is the commitment's 0-based position in [commitments] (ordered by
 * indexer `seq`, i.e. pool-tree/leaf order — the same convention
 * `rebuildInputPath`/the poll loop's `scanNote` leaf index rely on).
 */
private fun scanV2(
    commitments: List<SeqCommitment>,
    nullifiers: List<SeqNullifier>,
    enc: ByteArray,
    nk: ByteArray,
    onProgress: (Pair<Int, Int>) -> Unit,
): List<AuditEntry> {
    data class RawHit(val seq: Long, val amount: Long, val closedAt: String, val eventIdPrefix: String, val nullifierDec: String?)

    val nullifiersByDec = nullifiers.associateBy { it.nullifierDec }
    val raw = mutableListOf<RawHit>()
    commitments.forEachIndexed { i, ev ->
        runCatching { uniffi.prover_ffi.scanCommitment(ev.value, enc) }.getOrNull()?.let { note ->
            note.amount.toLongOrNull()?.let { amt ->
                val nullifierDec = runCatching {
                    val commitmentDec = uniffi.prover_ffi.decodeNullifierTopic(ev.topic)
                    uniffi.prover_ffi.computeNoteNullifier(nk, commitmentDec, i.toUInt())
                }.getOrNull()
                raw.add(RawHit(ev.seq, amt, ev.closedAt, ev.eventId.substringBeforeLast('-'), nullifierDec))
            }
        }
        if (i % 25 == 0) onProgress(i + 1 to commitments.size)
    }

    // Change detection: a hit is change iff its own creating tx also spent
    // one of THIS key's own notes (i.e. one of the nullifiers computed above).
    val hitNullifierDecs = raw.mapNotNull { it.nullifierDec }.toSet()
    val selfSpendPrefixes = nullifiers
        .filter { it.nullifierDec in hitNullifierDecs && it.eventId.isNotBlank() }
        .map { it.eventId.substringBeforeLast('-') }
        .toSet()

    return raw.map { h ->
        val spentRow = h.nullifierDec?.let { nullifiersByDec[it] }
        AuditEntry(
            seq = h.seq, amountStroops = h.amount, closedAt = h.closedAt,
            spent = spentRow != null, spentAt = spentRow?.closedAt ?: "",
            isChange = h.eventIdPrefix.isNotBlank() && h.eventIdPrefix in selfSpendPrefixes,
        )
    }
}

/** Validates + decodes a `stellaview:`/`stellaview2:` key into its raw key material. */
private fun decodeViewingKey(text: String): ParsedViewingKey? {
    val trimmed = text.trim()
    return when {
        trimmed.startsWith("stellaview2:") -> {
            val bytes = runCatching {
                android.util.Base64.decode(trimmed.removePrefix("stellaview2:"), android.util.Base64.NO_WRAP)
            }.getOrNull() ?: return null
            if (bytes.size != 64) null else ParsedViewingKey.V2(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64))
        }
        trimmed.startsWith("stellaview:") -> {
            val bytes = runCatching {
                android.util.Base64.decode(trimmed.removePrefix("stellaview:"), android.util.Base64.NO_WRAP)
            }.getOrNull() ?: return null
            if (bytes.size != 32) null else ParsedViewingKey.V1(bytes)
        }
        else -> null
    }
}

@Composable
private fun AuditRow(e: AuditEntry, isV2: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(Umbra.Surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (e.isChange) Icons.Filled.SouthWest else if (e.spent) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
            null, tint = if (e.spent) Umbra.TextFaint else Umbra.Success, modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                if (e.isChange) "Change (self)" else "Received",
                color = Umbra.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
            Text("${xlm(e.amountStroops)} XLM", color = Umbra.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            if (isV2) {
                Text(
                    if (e.spent) "Spent" else "Unspent",
                    color = if (e.spent) Umbra.TextFaint else Umbra.Success, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
            }
            val dateText = if (e.spent && e.spentAt.isNotBlank()) e.spentAt else e.closedAt
            if (dateText.isNotBlank()) {
                Text(dayLabel(dateOf(dateText)), color = Umbra.TextFaint, fontSize = 12.sp)
            }
        }
    }
}
