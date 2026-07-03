package com.privatepayments.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.privatepayments.state.StoredNote

/**
 * Wallet model. Balance + activity are derived from the durable Phase 3 state
 * layer (`NoteStore`): the app scans on-chain commitments into notes, records
 * on-chain nullifiers, and reconciles. The balance is the sum of **unspent**
 * notes only — spent notes are shown struck-through, no longer double-counted.
 */
/**
 * An in-flight tx not yet reflected in the reconciled note set (or, for public
 * sends, not yet visible in the Horizon payment history). Cleared by the poll
 * loop once the underlying data catches up, or after a 60s timeout.
 *
 * [noteCountAtCreation]/[spentCountAtCreation] snapshot the shielded note
 * counts at creation time (unused for public entries) — the poll loop clears
 * an entry once either count has moved, meaning the note store has reconciled
 * the change this pending entry represents.
 */
data class PendingTx(
    val id: Long,
    val title: String,
    val amountStroops: Long,
    val isPublic: Boolean,
    val createdAtMs: Long,
    val noteCountAtCreation: Int = 0,
    val spentCountAtCreation: Int = 0,
)

class WalletState {
    /** In-flight sends/deposits not yet confirmed by a poll — see [PendingTx]. */
    val pending = mutableStateListOf<PendingTx>()

    /** Reconciled unspent shielded balance, in stroops (1 XLM = 10^7 stroops). */
    var shieldedStroops by mutableStateOf<Long?>(null)
        private set

    /** Public (Stellar account) XLM balance, in stroops. */
    var publicStroops by mutableStateOf<Long?>(null)
        private set

    /** Count of unspent notes backing the balance. */
    var notesScanned by mutableStateOf(0)
        private set

    val activity = mutableStateListOf<Activity>()

    fun applyPublic(stroops: Long?) { publicStroops = stroops }

    fun publicText(): String = publicStroops?.let { xlm(it) } ?: "—"

    /** Apply the reconciled note set from the state layer. */
    fun applyNotes(balanceStroops: Long, notes: List<StoredNote>) {
        shieldedStroops = balanceStroops
        notesScanned = notes.count { !it.spent }
        activity.clear()
        notes.forEach { note ->
            // Plain labels (no contacts / no recipient identity in a privacy-pool
            // transfer): Deposit (public in), Received (private in), Change (to
            // self — a transfer's own change output), Transferred (private out).
            val isDeposit = !note.spent && note.kind == "deposit"
            val isChange = !note.spent && !isDeposit && note.isChange
            val title = when {
                note.spent -> "Transferred"
                isDeposit -> "Deposit"
                isChange -> "Change (to self)"
                else -> "Received"
            }
            val kind = when {
                note.spent -> ActivityKind.Transferred
                isDeposit -> ActivityKind.Deposit
                isChange -> ActivityKind.Change
                else -> ActivityKind.Received
            }
            val timestamp = note.spentAt.ifBlank { note.createdAt }.ifBlank { null }
            activity.add(
                Activity(
                    icon = if (note.spent) Icons.Filled.NorthEast else Icons.Filled.SouthWest,
                    title = title,
                    // Deposit is the one public action (amber badge); the rest are shielded.
                    private = !isDeposit,
                    amount = signedXlm(note.amount, negative = note.spent),
                    positive = !note.spent,
                    time = timestamp?.let { dateOf(it) } ?: "leaf #${note.leafIndex}",
                    subtitle = when {
                        note.spent -> "Spent · reconciled on-device"
                        isDeposit -> "Moved into the pool"
                        isChange -> "Change from a transfer you sent"
                        else -> "Decrypted on-device"
                    },
                    kind = kind,
                    timestamp = timestamp,
                    leafIndex = note.leafIndex,
                    commitment = note.commitment,
                ),
            )
        }
    }

    fun balanceText(): String =
        shieldedStroops?.let { xlm(it) } ?: "•.••••"
}
