package com.privatepayments.state

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A note recovered + tracked by the local state layer. */
data class StoredNote(
    val leafIndex: Long,
    val commitment: String,
    val amount: Long,
    val nullifier: String,
    val blinding: String,
    val spent: Boolean,
    /**
     * How this note entered the wallet: `"deposit"` (we moved public funds in)
     * or `"received"` (it arrived via a shielded transfer). Outgoing/spent notes
     * are surfaced as "Transferred" regardless. Used only for the activity label.
     */
    val kind: String = "received",
)

/**
 * Phase 3 native state layer. Durable on-device SQLite store of the wallet's
 * shielded notes + the nullifiers seen on-chain. This is what fixes the
 * **balance over-count**: before, the app summed every decrypted note forever;
 * now each note carries its nullifier, and once that nullifier appears on-chain
 * the note is marked spent and drops out of the balance.
 *
 * Tables (a focused subset of the reference `state` schema):
 *  - `user_notes`     — decrypted notes addressed to us, keyed by tree leaf index
 *  - `seen_nullifiers`— every nullifier observed on-chain (spend markers)
 *
 * Reconciliation = "mark a note spent iff its nullifier is on-chain". Balance is
 * then `SUM(amount) WHERE NOT spent`.
 */
class NoteStore(context: Context, accountIndex: Int = 0) :
    SQLiteOpenHelper(context, "stella_state_$accountIndex.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE user_notes(
                 leaf_index INTEGER PRIMARY KEY,
                 commitment TEXT NOT NULL,
                 amount     INTEGER NOT NULL,
                 nullifier  TEXT NOT NULL,
                 blinding   TEXT NOT NULL,
                 spent      INTEGER NOT NULL DEFAULT 0,
                 kind       TEXT NOT NULL DEFAULT 'received'
               )"""
        )
        db.execSQL("CREATE TABLE seen_nullifiers(nullifier TEXT PRIMARY KEY)")
        // Blindings of notes WE created by depositing — lets us label them
        // "Deposit" (vs "Received") once they're scanned back in.
        db.execSQL("CREATE TABLE my_deposits(blinding TEXT PRIMARY KEY)")
        db.execSQL("CREATE INDEX idx_notes_nullifier ON user_notes(nullifier)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS user_notes")
        db.execSQL("DROP TABLE IF EXISTS seen_nullifiers")
        db.execSQL("DROP TABLE IF EXISTS my_deposits")
        onCreate(db)
    }

    /** Insert a scanned note (idempotent by leaf index; never clobbers `spent`). */
    fun upsertNote(leafIndex: Long, commitment: String, amount: Long, nullifier: String, blinding: String) {
        writableDatabase.insertWithOnConflict(
            "user_notes",
            null,
            ContentValues().apply {
                put("leaf_index", leafIndex)
                put("commitment", commitment)
                put("amount", amount)
                put("nullifier", nullifier)
                put("blinding", blinding)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** Record nullifiers observed on-chain (idempotent). */
    fun addNullifiers(nullifiers: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (n in nullifiers) {
                db.insertWithOnConflict(
                    "seen_nullifiers",
                    null,
                    ContentValues().apply { put("nullifier", n) },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Record the blinding of a note we just created by depositing. */
    fun recordDepositBlinding(blinding: String) {
        writableDatabase.insertWithOnConflict(
            "my_deposits", null,
            ContentValues().apply { put("blinding", blinding) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /**
     * Mark every note spent whose nullifier is now on-chain, and tag any note
     * whose blinding we recorded at deposit time as a deposit (vs a received
     * transfer). Returns the number of notes newly marked spent.
     */
    fun reconcile(): Int {
        val db = writableDatabase
        db.execSQL(
            """UPDATE user_notes SET kind = 'deposit'
               WHERE kind <> 'deposit'
                 AND blinding IN (SELECT blinding FROM my_deposits)"""
        )
        return db.compileStatement(
            """UPDATE user_notes SET spent = 1
               WHERE spent = 0
                 AND nullifier IN (SELECT nullifier FROM seen_nullifiers)"""
        ).executeUpdateDelete()
    }

    /** Unspent shielded balance, in stroops. */
    fun unspentBalanceStroops(): Long =
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(amount), 0) FROM user_notes WHERE spent = 0", null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /** All notes, newest leaf first (for activity + coin selection). */
    fun notes(): List<StoredNote> =
        readableDatabase.rawQuery(
            "SELECT leaf_index, commitment, amount, nullifier, blinding, spent, kind FROM user_notes ORDER BY leaf_index DESC",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(StoredNote(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4), c.getInt(5) != 0, c.getString(6)))
                }
            }
        }

    fun unspentNotes(): List<StoredNote> = notes().filter { !it.spent }

    data class Counts(val total: Int, val unspent: Int, val spent: Int)

    fun counts(): Counts {
        val all = notes()
        val spent = all.count { it.spent }
        return Counts(total = all.size, unspent = all.size - spent, spent = spent)
    }

    /**
     * Export the full note set as a JSON document for encrypted backup (Phase
     * 7). This preserves note history (incl. spent markers) so a restored wallet
     * shows it without a full chain rescan.
     */
    fun exportJson(): String {
        val arr = org.json.JSONArray()
        for (n in notes()) {
            arr.put(
                org.json.JSONObject()
                    .put("leaf_index", n.leafIndex)
                    .put("commitment", n.commitment)
                    .put("amount", n.amount)
                    .put("nullifier", n.nullifier)
                    .put("blinding", n.blinding)
                    .put("spent", n.spent)
                    .put("kind", n.kind),
            )
        }
        return org.json.JSONObject().put("version", 1).put("notes", arr).toString()
    }

    /** Restore notes from an [exportJson] document. Returns the count imported. */
    fun importJson(json: String): Int {
        val arr = org.json.JSONObject(json).optJSONArray("notes") ?: return 0
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.insertWithOnConflict(
                    "user_notes", null,
                    ContentValues().apply {
                        put("leaf_index", o.getLong("leaf_index"))
                        put("commitment", o.getString("commitment"))
                        put("amount", o.getLong("amount"))
                        put("nullifier", o.getString("nullifier"))
                        put("blinding", o.getString("blinding"))
                        put("spent", if (o.optBoolean("spent")) 1 else 0)
                        put("kind", o.optString("kind", "received"))
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }
}
