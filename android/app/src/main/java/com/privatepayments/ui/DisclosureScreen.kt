package com.privatepayments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.platform.LocalContext
import com.privatepayments.ui.theme.Umbra
import com.privatepayments.ui.theme.umbraScreen
import kotlinx.coroutines.launch

/** The disclosure result surfaced to the UI (FFI types stay out of Compose). */
data class DisclosureResult(
    val amount: String,
    val noteCommitment: String,
    val root: String,
    val authority: String,
    val purpose: String,
    /** Four-part verification (proof ∧ context ∧ known-root ∧ amount). */
    val proofVerified: Boolean,
    val contextVerified: Boolean,
    val knownRoot: Boolean,
    val amountVerified: Boolean,
    val fullyVerified: Boolean,
    /** Canonical `DisclosureReceipt` JSON the holder shares with a verifier. */
    val receiptJson: String,
)

/** The context (authority + purpose) the disclosure is bound to. */
data class DisclosureRequest(val authority: String, val purpose: String)

/** One selectable unspent note offered in the disclosure picker. */
data class NoteOption(val leafIndex: Long, val amount: Long) {
    val label: String get() = "${xlm(amount)} XLM"
}

/**
 * Phase 6 "share a payment proof" screen. The user names who/what the proof is
 * for (the context), the app proves ownership of a note bound to that context,
 * and shows a verifiable receipt — revealing only the amount + commitment, never
 * the rest of the wallet.
 */
@Composable
fun DisclosureScreen(
    notes: List<NoteOption>,
    runDisclosure: suspend (req: DisclosureRequest, leafIndex: Long) -> DisclosureResult?,
    onClose: () -> Unit,
) {
    var authority by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DisclosureResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Default to the largest unspent note; user can pick any other.
    var selectedLeaf by remember(notes) { mutableStateOf(notes.maxByOrNull { it.amount }?.leafIndex) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().umbraScreen().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, null, tint = Umbra.Primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Proof of funds", color = Umbra.TextPrimary, fontFamily = Umbra.Display, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Prove to a specific auditor or recipient that you hold a note of a given amount in the pool — without revealing your other notes, total balance, or history. This is NOT a proof you paid someone; it attests ownership of one note. Bound to who it's for + a fresh anti-replay nonce.",
            color = Umbra.TextMuted, fontSize = 13.sp, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(16.dp))

        // Note picker — disclose ANY unspent note, not just the largest.
        Text("Note to disclose", color = Umbra.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        if (notes.isEmpty()) {
            Text("No unspent notes to disclose.", color = Umbra.TextFaint, fontSize = 13.sp)
        } else {
            notes.forEach { n ->
                NoteRow(n, selected = n.leafIndex == selectedLeaf) { selectedLeaf = n.leafIndex; error = null; result = null }
            }
        }
        Spacer(Modifier.height(18.dp))

        LabeledField("Who is this for? (authority)", "e.g. Acme Auditors", authority) { authority = it; error = null }
        Spacer(Modifier.height(12.dp))
        LabeledField("Purpose", "e.g. kyc-review or invoice-42", purpose) { purpose = it; error = null }

        if (error != null) {
            Spacer(Modifier.height(10.dp)); Text(error!!, color = Umbra.Error, fontSize = 13.sp)
        }

        Spacer(Modifier.height(18.dp))
        val canGen = authority.isNotBlank() && purpose.isNotBlank() && selectedLeaf != null && !busy
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(if (canGen) Umbra.Primary else Umbra.SurfaceElevated)
                .clickable(enabled = canGen) {
                    busy = true; error = null; result = null
                    scope.launch {
                        try {
                            result = runDisclosure(DisclosureRequest(authority.trim(), purpose.trim()), selectedLeaf!!)
                                ?: run { error = "Couldn't generate proof"; null }
                        } catch (e: Exception) {
                            error = e.message ?: "Disclosure failed"
                        } finally { busy = false }
                    }
                }.padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Umbra.Bg, strokeWidth = 2.dp)
            else Text(if (result == null) "Generate proof" else "Regenerate", color = if (canGen) Umbra.Bg else Umbra.TextFaint, fontWeight = FontWeight.SemiBold)
        }

        result?.let { r -> Spacer(Modifier.height(20.dp)); ReceiptCard(r) }

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.SurfaceElevated)
                .clickable(onClick = onClose).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Close", color = Umbra.TextSecondary, fontWeight = FontWeight.Medium) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun NoteRow(note: NoteOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Umbra.Primary.copy(alpha = 0.16f) else Umbra.Surface)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
            tint = if (selected) Umbra.Primary else Umbra.TextFaint, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(note.label, color = Umbra.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text("#${note.leafIndex}", color = Umbra.TextFaint, fontSize = 12.sp, fontFamily = Umbra.Mono)
    }
}

@Composable
private fun LabeledField(label: String, hint: String, value: String, onChange: (String) -> Unit) {
    Text(label, color = Umbra.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Umbra.Surface).padding(14.dp)) {
        BasicTextField(
            value = value, onValueChange = onChange,
            textStyle = TextStyle(color = Umbra.TextPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(Umbra.Primary), singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(hint, color = Umbra.TextFaint, fontSize = 16.sp)
                inner()
            },
        )
    }
}

@Composable
private fun ReceiptCard(r: DisclosureResult) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Umbra.Surface).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (r.fullyVerified) {
                Icon(Icons.Filled.Check, null, tint = Umbra.Success, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Receipt fully verified", color = Umbra.Success, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Text("Receipt incomplete", color = Umbra.Error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        // The privacy-pool's verification checks.
        CheckRow("Groth16 proof valid", r.proofVerified)
        CheckRow("Bound to this authority + nonce", r.contextVerified)
        CheckRow("Proven under a known pool root", r.knownRoot)
        CheckRow("Amount matches the proven note", r.amountVerified)
        Spacer(Modifier.height(12.dp))
        ReceiptRow("Note amount", "${r.amount.toLongOrNull()?.let { xlm(it) } ?: "0.0000"} XLM")
        ReceiptRow("For", r.authority)
        ReceiptRow("Purpose", r.purpose)
        ReceiptRow("Commitment", "${r.noteCommitment.take(10)}…")
        ReceiptRow("Pool root", "${r.root.take(10)}…")
        Spacer(Modifier.height(10.dp))
        Text("Canonical receipt (${r.receiptJson.length} chars) — share with the verifier", color = Umbra.TextFaint, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "${r.receiptJson.replace(Regex("\\s+"), " ").take(64)}…",
            color = Umbra.PrimaryLight, fontSize = 12.sp, fontFamily = com.privatepayments.ui.theme.Umbra.Mono,
        )
        Spacer(Modifier.height(14.dp))
        Row {
            ReceiptAction(Icons.Filled.ContentCopy, "Copy receipt", Modifier.weight(1f)) {
                val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("Stella proof receipt", r.receiptJson))
                Toast.makeText(ctx, "Receipt copied", Toast.LENGTH_SHORT).show()
            }
            Spacer(Modifier.width(10.dp))
            ReceiptAction(Icons.Filled.Share, "Share", Modifier.weight(1f)) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Umbra payment proof — ${r.purpose}")
                    putExtra(Intent.EXTRA_TEXT, r.receiptJson)
                }
                ctx.startActivity(Intent.createChooser(send, "Share payment proof"))
            }
        }
    }
}

@Composable
private fun ReceiptAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(Umbra.SurfaceElevated)
            .clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Umbra.PrimaryLight, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Umbra.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ReceiptRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Umbra.TextMuted, fontSize = 13.sp, modifier = Modifier.width(108.dp))
        Text(value, color = Umbra.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.Check else Icons.Filled.Close, null,
            tint = if (ok) Umbra.Success else Umbra.Error, modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = Umbra.TextSecondary, fontSize = 13.sp)
    }
}
