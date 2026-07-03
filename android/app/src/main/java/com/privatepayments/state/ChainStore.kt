package com.privatepayments.state

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One commitment event, durably stored in indexer-`seq` order. */
data class SeqCommitment(val seq: Long, val topic: String, val value: String, val closedAt: String, val eventId: String = "")

/** One nullifier event, durably stored in indexer-`seq` order. `nullifierDec`
 *  is the decoded decimal nullifier (see `decodeNullifierTopic`). */
data class SeqNullifier(val seq: Long, val nullifierDec: String, val eventId: String, val closedAt: String)

/**
 * Durable, **account-independent** mirror of the chain's pool/ASP events.
 *
 * Before this existed the wallet re-fetched the whole indexer feed and
 * re-decrypted every commitment on every 5s poll ("re-scan from leaf 0"). Now
 * we keep a persistent cursor and accumulate events here, so each poll only
 * fetches the **delta** (events with `seq >` cursor) and only scans commitments
 * the active account hasn't seen yet.
 *
 * Tables:
 *  - `commitments` — every `new_commitment_event` (topic + value + event_id),
 *     keyed by the indexer's monotonic `seq`; `ORDER BY seq` is pool-tree order.
 *  - `nullifiers`  — every `new_nullifier_event` (decoded decimal + event_id),
 *     keyed by `seq`. Lets the audit screen check "is this note spent?" and
 *     lets spend-netting pair a commitment with same-tx nullifiers via `event_id`.
 *  - `asp_leaves`  — every ASP `LeafAdded`, decoded to a decimal leaf, in order.
 *  - `meta`        — the global fetch `cursor` plus a per-account `scanned_<idx>`
 *     marker (how far that account has scanned commitments).
 *
 * Commitments/leaves are chain facts shared by all accounts, so this is a single
 * DB (`stella_chain.db`), unlike the per-account [NoteStore].
 */
class ChainStore(context: Context) :
    SQLiteOpenHelper(context, "stella_chain.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE commitments(seq INTEGER PRIMARY KEY, topic TEXT NOT NULL, value TEXT NOT NULL, closed_at TEXT NOT NULL DEFAULT '', event_id TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "CREATE TABLE nullifiers(seq INTEGER PRIMARY KEY, nullifier_dec TEXT NOT NULL, event_id TEXT NOT NULL DEFAULT '', closed_at TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX idx_nullifiers_dec ON nullifiers(nullifier_dec)")
        db.execSQL("CREATE TABLE asp_leaves(seq INTEGER PRIMARY KEY, leaf TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    // Pure chain cache (dev phase, no user data) — drop-recreate on any schema
    // change and let the next poll re-fetch from the indexer, backfilling
    // whatever new columns (e.g. closed_at, event_id) were added.
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS commitments")
        db.execSQL("DROP TABLE IF EXISTS nullifiers")
        db.execSQL("DROP TABLE IF EXISTS asp_leaves")
        db.execSQL("DROP TABLE IF EXISTS meta")
        onCreate(db)
    }

    private fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun putMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "meta", null,
            ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Global fetch cursor — the highest indexer `seq` we've pulled. */
    fun cursor(): Long = meta("cursor")?.toLongOrNull() ?: 0L
    fun setCursor(c: Long) = putMeta("cursor", c.toString())

    /** How far the given account has scanned commitments (a `seq`). */
    fun scannedSeq(accountIndex: Int): Long = meta("scanned_$accountIndex")?.toLongOrNull() ?: 0L
    fun setScannedSeq(accountIndex: Int, seq: Long) = putMeta("scanned_$accountIndex", seq.toString())

    fun appendCommitment(seq: Long, topic: String, value: String, closedAt: String, eventId: String = "") {
        writableDatabase.insertWithOnConflict(
            "commitments", null,
            ContentValues().apply {
                put("seq", seq); put("topic", topic); put("value", value); put("closed_at", closedAt)
                put("event_id", eventId)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** Record a decoded on-chain nullifier (idempotent by `seq`). */
    fun appendNullifier(seq: Long, nullifierDec: String, eventId: String, closedAt: String) {
        writableDatabase.insertWithOnConflict(
            "nullifiers", null,
            ContentValues().apply {
                put("seq", seq); put("nullifier_dec", nullifierDec); put("event_id", eventId); put("closed_at", closedAt)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun appendAspLeaf(seq: Long, leaf: String) {
        writableDatabase.insertWithOnConflict(
            "asp_leaves", null,
            ContentValues().apply { put("seq", seq); put("leaf", leaf) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** All commitment topics, pool-tree order (for pool-root + Merkle paths). */
    fun commitmentTopics(): List<String> =
        readableDatabase.rawQuery("SELECT topic FROM commitments ORDER BY seq ASC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** All ASP leaves (decimal), index order (for rebuilding membership proofs). */
    fun aspLeaves(): List<String> =
        readableDatabase.rawQuery("SELECT leaf FROM asp_leaves ORDER BY seq ASC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** Commitments newer than `seq`, oldest first (the ones still to scan). */
    fun commitmentsSince(seq: Long): List<SeqCommitment> =
        readableDatabase.rawQuery(
            "SELECT seq, topic, value, closed_at, event_id FROM commitments WHERE seq > ? ORDER BY seq ASC",
            arrayOf(seq.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(SeqCommitment(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))) }
        }

    fun commitmentCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM commitments", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** All on-chain nullifiers seen so far, seq order. */
    fun allNullifiers(): List<SeqNullifier> =
        readableDatabase.rawQuery("SELECT seq, nullifier_dec, event_id, closed_at FROM nullifiers ORDER BY seq ASC", null).use { c ->
            buildList { while (c.moveToNext()) add(SeqNullifier(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))) }
        }

    /**
     * `event_id` prefixes (tx identity — everything before the trailing
     * dash-separated operation index) of transactions that published any of
     * [nullifierDecs]. Used to detect change: a commitment created in a tx that
     * also spent one of our own notes is change, not a genuine receive.
     */
    fun eventIdPrefixesForNullifiers(nullifierDecs: Set<String>): Set<String> {
        if (nullifierDecs.isEmpty()) return emptySet()
        return allNullifiers()
            .filter { it.nullifierDec in nullifierDecs && it.eventId.isNotBlank() }
            .map { it.eventId.substringBeforeLast('-') }
            .toSet()
    }
}
