package com.mortalive.mortamesh

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

internal data class WorldChatPost(
    val postId: String,
    val authorNodeId: String,
    val authorName: String,
    val timestamp: Long,
    val text: String,
    val publicKey: String,
    val signature: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("id", postId)
        .put("node", authorNodeId)
        .put("name", authorName)
        .put("ts", timestamp)
        .put("text", text)
        .put("pub", publicKey)
        .put("sig", signature)

    companion object {
        fun fromJson(value: JSONObject): WorldChatPost? = try {
            val post = WorldChatPost(
                postId = value.getString("id"),
                authorNodeId = value.getString("node"),
                authorName = value.getString("name"),
                timestamp = value.getLong("ts"),
                text = value.getString("text"),
                publicKey = value.getString("pub"),
                signature = value.getString("sig")
            )
            if (!isCanonicalUuid(post.postId) || !isCanonicalUuid(post.authorNodeId) ||
                post.authorName.isBlank() || post.text.isBlank()) null else post
        } catch (_: Exception) { null }

        private fun isCanonicalUuid(value: String): Boolean = try {
            UUID.fromString(value).toString() == value
        } catch (_: Exception) { false }
    }
}

/**
 * Bounded store for intentionally public mesh posts and durable de-duplication IDs.
 * Posts stay in the app-private database for seven days / 1,000 items; up to 275,000
 * distinct signed post IDs are retained for seven days without evicting still-live IDs.
 */
internal class WorldChatStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    enum class InsertResult { INSERTED, DUPLICATE, RATE_LIMITED, STORAGE_ERROR }

    @Synchronized
    fun insertIfNew(post: WorldChatPost): InsertResult {
        val now = System.currentTimeMillis()
        if (!isCanonicalUuid(post.postId) || !isCanonicalUuid(post.authorNodeId)) return InsertResult.STORAGE_ERROR
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete(TABLE_SEEN, COL_SEEN_AT + " < ?", arrayOf((now - RETENTION_MS).toString()))
                db.delete(TABLE_POSTS, COL_RECEIVED_AT + " < ?", arrayOf((now - RETENTION_MS).toString()))

                // New dedupe keys include the signed author's node ID. Treat pre-upgrade
                // raw UUID rows as legacy global entries until their seven-day expiry.
                val postKey = post.authorNodeId + "|" + post.postId
                val already = db.query(
                    TABLE_SEEN, arrayOf(COL_POST_ID),
                    COL_POST_ID + " = ? OR " + COL_POST_ID + " = ?",
                    arrayOf(postKey, post.postId), null, null, null, "1"
                ).use { it.moveToFirst() }
                if (already) {
                    db.setTransactionSuccessful()
                    return InsertResult.DUPLICATE
                }

                val since = now - RATE_WINDOW_MS
                // A persisted device-wide budget bounds seven-day dedupe growth across process
                // restarts and fresh author keys. The in-memory ingress limiter separately bounds
                // how much signature verification a physical neighbor can request.
                val recentSeenCount = db.rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE_SEEN + " WHERE " + COL_SEEN_AT + " >= ?",
                    arrayOf(since.toString())
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else MAX_SEEN_IDS }
                if (recentSeenCount >= MAX_WORLD_POSTS_PER_MINUTE) {
                    db.setTransactionSuccessful()
                    return InsertResult.RATE_LIMITED
                }
                val seenCount = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_SEEN, null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else MAX_SEEN_IDS
                }
                // Never evict a still-live signed post ID: eviction would make an old post
                // replayable during its seven-day validity window.
                if (seenCount >= MAX_SEEN_IDS) {
                    db.setTransactionSuccessful()
                    return InsertResult.RATE_LIMITED
                }

                // Record the immutable signed ID before applying author quotas. A rate-limited
                // post is discarded consistently on retransmission instead of being re-verified.
                val seenValues = ContentValues().apply {
                    put(COL_POST_ID, postKey)
                    put(COL_SEEN_AT, now)
                }
                if (db.insertWithOnConflict(TABLE_SEEN, null, seenValues, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                    db.setTransactionSuccessful()
                    return InsertResult.DUPLICATE
                }

                val count = db.rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE_POSTS + " WHERE " + COL_AUTHOR + " = ? AND " + COL_RECEIVED_AT + " >= ?",
                    arrayOf(post.authorNodeId, since.toString())
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
                if (count >= MAX_POSTS_PER_MINUTE) {
                    db.setTransactionSuccessful()
                    return InsertResult.RATE_LIMITED
                }
                val values = ContentValues().apply {
                    // TABLE_POSTS has a single-column primary key. Store the same scoped key so
                    // two different authors cannot collide on a caller-chosen UUID.
                    put(COL_POST_ID, postKey)
                    put(COL_AUTHOR, post.authorNodeId)
                    put(COL_CREATED_AT, post.timestamp)
                    put(COL_RECEIVED_AT, now)
                    put(COL_PAYLOAD, post.toJson().toString())
                }
                db.insertOrThrow(TABLE_POSTS, null, values)
                db.execSQL(
                    "DELETE FROM " + TABLE_POSTS + " WHERE rowid IN (" +
                        "SELECT rowid FROM " + TABLE_POSTS + " ORDER BY " + COL_CREATED_AT +
                        " DESC LIMIT -1 OFFSET " + MAX_POSTS + ")"
                )
                db.setTransactionSuccessful()
                InsertResult.INSERTED
            } finally {
                db.endTransaction()
            }
        } catch (_: Exception) {
            InsertResult.STORAGE_ERROR
        }
    }

    /** Oldest-to-newest timeline, with the retention cutoff applied on every read. */
    @Synchronized
    fun recent(limit: Int = MAX_POSTS): List<WorldChatPost> {
        val safeLimit = limit.coerceIn(1, MAX_POSTS)
        return try {
            val result = mutableListOf<WorldChatPost>()
            readableDatabase.query(
                TABLE_POSTS,
                arrayOf(COL_PAYLOAD),
                COL_RECEIVED_AT + " >= ?",
                arrayOf((System.currentTimeMillis() - RETENTION_MS).toString()),
                null, null,
                COL_CREATED_AT + " DESC",
                safeLimit.toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val parsed = runCatching { JSONObject(cursor.getString(0)) }.getOrNull() ?: continue
                    WorldChatPost.fromJson(parsed)?.let(result::add)
                }
            }
            result.reverse()
            result
        } catch (_: Exception) { emptyList() }
    }

    /** A small reconnect burst is deliberately bounded to avoid flooding slow BLE links. */
    @Synchronized
    fun recentForSync(limit: Int = SYNC_POSTS): List<WorldChatPost> = recent(limit)

    override fun onCreate(db: SQLiteDatabase) = createTables(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = createTables(db)

    private fun createTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE_POSTS + " (" +
                COL_POST_ID + " TEXT PRIMARY KEY NOT NULL, " +
                COL_AUTHOR + " TEXT NOT NULL, " +
                COL_CREATED_AT + " INTEGER NOT NULL, " +
                COL_RECEIVED_AT + " INTEGER NOT NULL, " +
                COL_PAYLOAD + " TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS world_posts_received_idx ON " + TABLE_POSTS + "(" + COL_RECEIVED_AT + ")")
        db.execSQL("CREATE INDEX IF NOT EXISTS world_posts_author_idx ON " + TABLE_POSTS + "(" + COL_AUTHOR + ", " + COL_RECEIVED_AT + ")")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE_SEEN + " (" +
                COL_POST_ID + " TEXT PRIMARY KEY NOT NULL, " +
                COL_SEEN_AT + " INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS world_seen_time_idx ON " + TABLE_SEEN + "(" + COL_SEEN_AT + ")")
    }

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: Exception) { false }

    companion object {
        private const val DATABASE_NAME = "morta_mesh_world_chat.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_POSTS = "world_posts"
        private const val TABLE_SEEN = "world_seen"
        private const val COL_POST_ID = "post_id"
        private const val COL_AUTHOR = "author_node"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_RECEIVED_AT = "received_at"
        private const val COL_PAYLOAD = "payload"
        private const val COL_SEEN_AT = "seen_at"
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val RATE_WINDOW_MS = 60_000L
        private const val MAX_POSTS_PER_MINUTE = 5
        // Device-wide cap: 20 distinct signed IDs/minute => at most 201,620 in any
        // seven-day replay window (fixed-window boundary headroom included).
        private const val MAX_WORLD_POSTS_PER_MINUTE = 20
        const val MAX_POSTS = 1_000
        const val SYNC_POSTS = 5
        // Allows 20/min for seven days plus 50,000 pre-upgrade legacy IDs.
        private const val MAX_SEEN_IDS = 275_000
    }
}
