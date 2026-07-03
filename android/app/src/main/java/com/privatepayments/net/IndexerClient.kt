package com.privatepayments.net

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One `new_commitment_event`, tagged with the indexer `seq`. topic is the
 *  commitment (U256), value carries `index` + `encrypted_output`. Both base64-XDR.
 *  `closedAt` is the ledger's close time (ISO instant) — the wall-clock
 *  timestamp for this commitment, used to date deposits/receives in Activity.
 *  `eventId` is the indexer's toid-based `event_id`; events from the same tx
 *  share the prefix before the final dash-separated component, which is how
 *  spend-netting (change detection) pairs a new commitment with the nullifiers
 *  spent in the same transaction. */
data class CommitmentEvent(val seq: Long, val eventId: String, val commitmentTopic: String, val value: String, val closedAt: String)

/** One ASP `LeafAdded`, tagged with `seq`. value is the base64-XDR leaf. */
data class LeafEvent(val seq: Long, val value: String)

/** topic[1] (base64-XDR `ScVal::U256`) of one `new_nullifier_event`, paired
 *  with the ledger close time it was recorded at (used as the spend date).
 *  `eventId` lets this be matched against a same-tx [CommitmentEvent] for
 *  change detection (see [CommitmentEvent.eventId]). */
data class NullifierEvent(val seq: Long, val eventId: String, val topic: String, val closedAt: String)

/**
 * The **delta** of events newer than the caller's cursor. The wallet persists
 * the cursor + accumulated events in [com.privatepayments.state.ChainStore], so
 * each poll only transfers (and later scans) what's actually new.
 */
data class IndexerDelta(
    val reachable: Boolean,
    /** Highest `seq` seen this fetch — persist as the next cursor. */
    val newCursor: Long,
    /** Count of new events this fetch (for the status line). */
    val newCount: Int = 0,
    val commitments: List<CommitmentEvent> = emptyList(),
    val nullifiers: List<NullifierEvent> = emptyList(),
    val leaves: List<LeafEvent> = emptyList(),
    val error: String? = null,
)

/**
 * Minimal client for the private-payments indexer. The device reaches the
 * host-run indexer via `adb reverse tcp:8080 tcp:8080`, so localhost works.
 * A real build would point this at the deployed indexer URL and feed events
 * into the state/scanning layer; here it proves the app ↔ indexer wire.
 */
object IndexerClient {
    private val BASE = Endpoints.INDEXER_BASE

    /**
     * Fetch every event with `seq > sinceCursor`, paging until exhausted. A
     * single fixed `limit` would silently drop the newest events once the chain
     * has more than one page; we page on the returned cursor instead. Starting
     * from a persisted cursor (vs. always 0) is what makes sync incremental.
     */
    fun fetchSince(sinceCursor: Long): IndexerDelta = try {
        val commitments = mutableListOf<CommitmentEvent>()
        val nullifiers = mutableListOf<NullifierEvent>()
        val leaves = mutableListOf<LeafEvent>()
        var total = 0
        var cursor = sinceCursor
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
                val seq = e.optLong("seq")
                val eventId = e.optString("event_id")
                val topics = e.getJSONArray("topic")
                val closedAt = e.optString("ledger_closed_at")
                when (decodeSymbol(topics.optString(0))) {
                    "new_commitment_event" ->
                        commitments.add(CommitmentEvent(seq, eventId, topics.optString(1), e.optString("value"), closedAt))
                    "new_nullifier_event" ->
                        nullifiers.add(NullifierEvent(seq, eventId, topics.optString(1), closedAt))
                    "LeafAdded" ->
                        leaves.add(LeafEvent(seq, e.optString("value")))
                }
            }
            total += n
            val next = json.optLong("cursor", cursor)
            // Last page (short) or no forward progress → done.
            if (n < pageLimit || next == cursor) { cursor = next; break }
            cursor = next
        }
        IndexerDelta(
            reachable = true, newCursor = cursor, newCount = total,
            commitments = commitments, nullifiers = nullifiers, leaves = leaves,
        )
    } catch (e: Exception) {
        IndexerDelta(reachable = false, newCursor = sinceCursor, error = e.message)
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
