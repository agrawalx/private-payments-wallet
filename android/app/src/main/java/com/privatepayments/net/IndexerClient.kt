package com.privatepayments.net

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One `new_commitment_event`: topic[1] is the commitment (U256), value carries
 *  `index` + `encrypted_output`. Both base64-XDR, fed to the `scanNote` FFI. */
data class CommitmentEvent(val commitmentTopic: String, val value: String)

/** Live sync state pulled from the indexer's /events feed. */
data class SyncStatus(
    val reachable: Boolean,
    val count: Int = 0,
    val latestType: String? = null,
    val latestLedger: Long? = null,
    /** Every `new_commitment_event` (topic + value) for the wallet to scan. */
    val commitments: List<CommitmentEvent> = emptyList(),
    /** topic[1] (base64-XDR `ScVal::U256`) of every `new_nullifier_event`. */
    val nullifierTopics: List<String> = emptyList(),
    /** base64-XDR `value` of every ASP `LeafAdded` event (index order) — the
     *  live ASP membership leaves, for rebuilding membership proofs. */
    val leafAddedValues: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * Minimal client for the private-payments indexer. The device reaches the
 * host-run indexer via `adb reverse tcp:8080 tcp:8080`, so localhost works.
 * A real build would point this at the deployed indexer URL and feed events
 * into the state/scanning layer; here it proves the app ↔ indexer wire.
 */
object IndexerClient {
    private const val BASE = "http://127.0.0.1:8080"

    fun fetchStatus(): SyncStatus = try {
        // Page through the cursor-paginated feed until exhausted — a single
        // fixed `limit` silently drops the newest events once the chain has
        // more than one page (which is exactly when fresh deposits go missing).
        val commitments = mutableListOf<CommitmentEvent>()
        val nullifierTopics = mutableListOf<String>()
        val leafAddedValues = mutableListOf<String>()
        var type: String? = null
        var ledger: Long? = null
        var total = 0
        var cursor = 0L
        val pageLimit = 1000 // indexer clamps to [1, 1000]
        while (true) {
            val conn = URL("$BASE/events?cursor=$cursor&limit=$pageLimit").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val events = json.getJSONArray("events")
            val n = events.length()
            for (i in 0 until n) {
                val e = events.getJSONObject(i)
                val topics = e.getJSONArray("topic")
                val t = decodeSymbol(topics.optString(0))
                type = t // events are seq-ascending, so the final one is newest
                ledger = e.optLong("ledger")
                when (t) {
                    "new_commitment_event" ->
                        commitments.add(CommitmentEvent(topics.optString(1), e.optString("value")))
                    "new_nullifier_event" ->
                        nullifierTopics.add(topics.optString(1))
                    "LeafAdded" ->
                        leafAddedValues.add(e.optString("value"))
                }
            }
            total += n
            val next = json.optLong("cursor", cursor)
            // Last page (short) or no forward progress → done.
            if (n < pageLimit || next == cursor) break
            cursor = next
        }
        SyncStatus(
            reachable = true, count = total, latestType = type, latestLedger = ledger,
            commitments = commitments, nullifierTopics = nullifierTopics,
            leafAddedValues = leafAddedValues,
        )
    } catch (e: Exception) {
        SyncStatus(reachable = false, error = e.message)
    }

    /**
     * Best-effort decode of an event topic (base64 XDR `ScVal::Symbol`):
     * [4-byte SCV type][4-byte big-endian length][UTF-8 symbol].
     */
    private fun decodeSymbol(b64: String): String? = try {
        val b = Base64.decode(b64, Base64.DEFAULT)
        if (b.size >= 8) {
            val len = ((b[4].toInt() and 0xff) shl 24) or
                ((b[5].toInt() and 0xff) shl 16) or
                ((b[6].toInt() and 0xff) shl 8) or
                (b[7].toInt() and 0xff)
            String(b, 8, minOf(len, b.size - 8), Charsets.UTF_8)
        } else null
    } catch (e: Exception) {
        null
    }
}
