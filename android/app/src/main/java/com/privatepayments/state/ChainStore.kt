package com.privatepayments.state

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One commitment event, durably stored in indexer-`seq` order. */
data class SeqCommitment(val seq: Long, val topic: String, val value: String)

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
 *  - `commitments` — every `new_commitment_event` (topic + value), keyed by the
 *     indexer's monotonic `seq`; `ORDER BY seq` is pool-tree order.
 *  - `asp_leaves`  — every ASP `LeafAdded`, decoded to a decimal leaf, in order.
 *  - `meta`        — the global fetch `cursor` plus a per-account `scanned_<idx>`
 *     marker (how far that account has scanned commitments).
 *
 * Commitments/leaves are chain facts shared by all accounts, so this is a single
 * DB (`stella_chain.db`), unlike the per-account [NoteStore].
 */
class ChainStore(context: Context) :
    SQLiteOpenHelper(context, "stella_chain.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE commitments(seq INTEGER PRIMARY KEY, topic TEXT NOT NULL, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE asp_leaves(seq INTEGER PRIMARY KEY, leaf TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS commitments")
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

    fun appendCommitment(seq: Long, topic: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "commitments", null,
            ContentValues().apply { put("seq", seq); put("topic", topic); put("value", value) },
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
            "SELECT seq, topic, value FROM commitments WHERE seq > ? ORDER BY seq ASC",
            arrayOf(seq.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(SeqCommitment(c.getLong(0), c.getString(1), c.getString(2))) }
        }

    fun commitmentCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM commitments", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
}
