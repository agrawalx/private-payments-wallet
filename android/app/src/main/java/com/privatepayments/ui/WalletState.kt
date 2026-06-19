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
class WalletState {
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

    fun publicText(): String = publicStroops?.let { "%,.4f".format(it / 1e7) } ?: "—"

    /** Apply the reconciled note set from the state layer. */
    fun applyNotes(balanceStroops: Long, notes: List<StoredNote>) {
        shieldedStroops = balanceStroops
        notesScanned = notes.count { !it.spent }
        activity.clear()
        notes.forEach { note ->
            // Three plain labels (no contacts / no recipient identity in a
            // privacy-pool transfer): Deposit (public in), Received (private in),
            // Transferred (private out).
            val isDeposit = !note.spent && note.kind == "deposit"
            val title = when {
                note.spent -> "Transferred"
                isDeposit -> "Deposit"
                else -> "Received"
            }
            activity.add(
                Activity(
                    icon = if (note.spent) Icons.Filled.NorthEast else Icons.Filled.SouthWest,
                    title = title,
                    // Deposit is the one public action (amber badge); the rest are shielded.
                    private = !isDeposit,
                    amount = "%s%.4f".format(if (note.spent) "−" else "+", note.amount / 1e7),
                    positive = !note.spent,
                    time = "leaf #${note.leafIndex}",
                    subtitle = when {
                        note.spent -> "Spent · reconciled on-device"
                        isDeposit -> "Moved into the pool"
                        else -> "Decrypted on-device"
                    },
                ),
            )
        }
    }

    fun balanceText(): String =
        shieldedStroops?.let { "%,.4f".format(it / 1e7) } ?: "•.••••"
}
