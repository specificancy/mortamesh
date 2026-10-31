package com.mortalive.mortamesh

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * Durable anti-replay ledger for signed envelope IDs. It stores only message UUIDs and receive times,
 * never message content. The signed timestamp in MeshSecurity is also enforced before acceptance.
 */
internal class ReplayGuard(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    @Synchronized
    fun acceptIfNew(senderNodeId: String, messageId: String): Boolean {
        if (!isCanonicalUuid(senderNodeId) || !isCanonicalUuid(messageId)) return false
        val now = System.currentTimeMillis()
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                // Expire only entries outside the signed-envelope replay window. Never evict a
                // still-live ID to make room. Legacy IDs migrated without a sender remain a
                // conservative global deny for that UUID until their original retention expires.
                db.delete(TABLE, "$COL_SEEN_AT < ?", arrayOf((now - RETENTION_MS).toString()))
                val already = db.query(
                    TABLE, arrayOf(COL_MESSAGE_ID),
                    "($COL_SENDER_NODE = ? OR $COL_SENDER_NODE = ?) AND $COL_MESSAGE_ID = ?",
                    arrayOf(senderNodeId, LEGACY_SENDER, messageId), null, null, null, "1"
                ).use { it.moveToFirst() }
                if (already) {
                    db.setTransactionSuccessful()
                    return false
                }
                val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else MAX_ENTRIES
                }
                if (count >= MAX_ENTRIES) {
                    db.setTransactionSuccessful()
                    return false
                }
                val values = ContentValues().apply {
                    put(COL_SENDER_NODE, senderNodeId)
                    put(COL_MESSAGE_ID, messageId)
                    put(COL_SEEN_AT, now)
                }
                val inserted = db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
                db.setTransactionSuccessful()
                inserted
            } finally {
                db.endTransaction()
            }
        } catch (_: Exception) {
            // Fail closed if persistence fails.
            false
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        createTable(db, TABLE)
        createTimeIndex(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val legacyTable = TABLE + "_legacy_v1"
            db.execSQL("DROP INDEX IF EXISTS ${TABLE}_time_idx")
            db.execSQL("ALTER TABLE $TABLE RENAME TO $legacyTable")
            createTable(db, TABLE)
            db.execSQL(
                "INSERT OR IGNORE INTO $TABLE ($COL_SENDER_NODE, $COL_MESSAGE_ID, $COL_SEEN_AT) " +
                    "SELECT '$LEGACY_SENDER', $COL_MESSAGE_ID, $COL_SEEN_AT FROM $legacyTable"
            )
            db.execSQL("DROP TABLE $legacyTable")
            createTimeIndex(db)
        }
    }

    private fun createTable(db: SQLiteDatabase, tableName: String) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $tableName (" +
                "$COL_SENDER_NODE TEXT NOT NULL, " +
                "$COL_MESSAGE_ID TEXT NOT NULL, " +
                "$COL_SEEN_AT INTEGER NOT NULL, " +
                "PRIMARY KEY ($COL_SENDER_NODE, $COL_MESSAGE_ID))"
        )
    }

    private fun createTimeIndex(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS ${TABLE}_time_idx ON $TABLE($COL_SEEN_AT)")
    }

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val DATABASE_NAME = "morta_mesh_replay_v2.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE = "seen_mesh_packets"
        private const val COL_SENDER_NODE = "sender_node_id"
        private const val COL_MESSAGE_ID = "message_id"
        private const val COL_SEEN_AT = "seen_at"
        private const val LEGACY_SENDER = "__legacy_global__"
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        // A 60/min global accepted-chat cap yields at most 604,800 IDs in seven days.
        // Keep headroom without evicting any still-live replay ID.
        private const val MAX_ENTRIES = 650_000
    }
}
