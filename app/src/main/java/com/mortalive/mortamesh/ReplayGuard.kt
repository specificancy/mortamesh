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
    fun acceptIfNew(messageId: String): Boolean {
        if (!isCanonicalUuid(messageId)) return false
        val now = System.currentTimeMillis()
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                // Expire only entries that are outside the signed-envelope replay window. Never
                // evict a still-live ID to make room: doing so would make an old valid envelope
                // replayable after an attacker fills the table.
                db.delete(TABLE, "$COL_SEEN_AT < ?", arrayOf((now - RETENTION_MS).toString()))
                val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else MAX_ENTRIES
                }
                if (count >= MAX_ENTRIES) {
                    db.setTransactionSuccessful()
                    return false
                }
                val values = ContentValues().apply {
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
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "$COL_MESSAGE_ID TEXT PRIMARY KEY NOT NULL, " +
                "$COL_SEEN_AT INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX ${TABLE}_time_idx ON $TABLE($COL_SEEN_AT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No destructive migration: replay state should not be dropped silently during upgrades.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "$COL_MESSAGE_ID TEXT PRIMARY KEY NOT NULL, " +
                "$COL_SEEN_AT INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS ${TABLE}_time_idx ON $TABLE($COL_SEEN_AT)")
    }

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val DATABASE_NAME = "morta_mesh_replay_v2.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE = "seen_mesh_packets"
        private const val COL_MESSAGE_ID = "message_id"
        private const val COL_SEEN_AT = "seen_at"
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_ENTRIES = 50_000
    }
}
