package com.privatepayments.state

/**
 * Coin selection for the 4-in/2-out `policy_tx_4_2` circuit (a native rewrite of
 * the reference `tx-planner`). A single shielded transaction can consume at most
 * **four** input notes and emit two outputs (recipient + change), so a spend of
 * `target` must be covered by one to four unspent notes.
 *
 * Strategy: find the subset of up to [MAX_INPUTS] notes that covers the target
 * with the least change, by brute force over all combinations (n is small — a
 * wallet holds few unspent notes at once). Returns null if even the four
 * largest notes can't cover it (the wallet would need to consolidate first — a
 * multi-step plan, deferred). Pure + deterministic, so it unit-tests against
 * known cases.
 */
object CoinSelector {

    data class Selection(val inputs: List<StoredNote>, val total: Long, val change: Long)

    /** Max input notes per transaction (circuit is 4-in/2-out). */
    const val MAX_INPUTS = 4

    fun select(notes: List<StoredNote>, target: Long): Selection? {
        if (target <= 0) return null
        val unspent = notes.filter { !it.spent && it.amount > 0 }.sortedByDescending { it.amount }
        if (unspent.isEmpty()) return null

        // Best subset of 1..MAX_INPUTS notes: minimize change over all
        // combinations that cover the target.
        var best: Selection? = null
        fun consider(combo: List<StoredNote>) {
            val total = combo.sumOf { it.amount }
            if (total < target) return
            val change = total - target
            val current = best
            if (current == null || change < current.change) {
                best = Selection(combo, total, change)
            }
        }
        fun combine(start: Int, chosen: List<StoredNote>) {
            if (chosen.isNotEmpty()) consider(chosen)
            if (chosen.size == MAX_INPUTS) return
            for (i in start until unspent.size) {
                combine(i + 1, chosen + unspent[i])
            }
        }
        combine(0, emptyList())
        return best
    }

    /** Total spendable across at most [MAX_INPUTS] notes (max amount sendable now). */
    fun maxSpendable(notes: List<StoredNote>): Long =
        notes.filter { !it.spent && it.amount > 0 }
            .map { it.amount }
            .sortedDescending()
            .take(MAX_INPUTS)
            .sum()
}
