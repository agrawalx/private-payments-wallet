package com.privatepayments.state

/**
 * Coin selection for the 2-in/2-out `policy_tx_2_2` circuit (a native rewrite of
 * the reference `tx-planner`). A single shielded transaction can consume at most
 * **two** input notes and emit two outputs (recipient + change), so a spend of
 * `target` must be covered by one or two unspent notes.
 *
 * Strategy: prefer a single note that covers the target with the least change;
 * otherwise the best pair. Returns null if even the two largest notes can't
 * cover it (the wallet would need to consolidate first — a multi-step plan,
 * deferred). Pure + deterministic, so it unit-tests against known cases.
 */
object CoinSelector {

    data class Selection(val inputs: List<StoredNote>, val total: Long, val change: Long)

    /** Max input notes per transaction (circuit is 2-in/2-out). */
    const val MAX_INPUTS = 2

    fun select(notes: List<StoredNote>, target: Long): Selection? {
        if (target <= 0) return null
        val unspent = notes.filter { !it.spent && it.amount > 0 }.sortedByDescending { it.amount }
        if (unspent.isEmpty()) return null

        // Best single note: smallest note that still covers the target (least change).
        val single = unspent.filter { it.amount >= target }.minByOrNull { it.amount }
        var best: Selection? = single?.let { Selection(listOf(it), it.amount, it.amount - target) }

        // Best pair: minimize change over all 2-combinations that cover the target.
        for (i in unspent.indices) {
            for (j in i + 1 until unspent.size) {
                val total = unspent[i].amount + unspent[j].amount
                if (total < target) continue
                val change = total - target
                val current = best
                if (current == null || change < current.change) {
                    best = Selection(listOf(unspent[i], unspent[j]), total, change)
                }
            }
        }
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
