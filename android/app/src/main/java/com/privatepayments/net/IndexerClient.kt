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
        val conn = URL("$BASE/events?cursor=0&limit=100").openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val events = json.getJSONArray("events")
        val n = events.length()
        var type: String? = null
        var ledger: Long? = null
        val commitments = mutableListOf<CommitmentEvent>()
        val nullifierTopics = mutableListOf<String>()
        val leafAddedValues = mutableListOf<String>()
        for (i in 0 until n) {
            val e = events.getJSONObject(i)
            val topics = e.getJSONArray("topic")
            val t = decodeSymbol(topics.optString(0))
            if (i == n - 1) {
                type = t
                ledger = e.optLong("ledger")
            }
            when (t) {
                "new_commitment_event" ->
                    commitments.add(CommitmentEvent(topics.optString(1), e.optString("value")))
                "new_nullifier_event" ->
                    nullifierTopics.add(topics.optString(1))
                "LeafAdded" ->
                    leafAddedValues.add(e.optString("value"))
            }
        }
        SyncStatus(
            reachable = true, count = n, latestType = type, latestLedger = ledger,
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
