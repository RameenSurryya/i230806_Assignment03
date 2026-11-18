package com.rameen.i230806.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's own copy of the data (SQLite), so screens open instantly and work offline.
 *
 * Every screen: 1) shows what is in SQLite straight away, 2) asks the server for fresh data
 * when online, 3) saves the answer here and redraws.
 *
 * Cache tables keep the server's JSON for each item in a "json" column, plus the few columns
 * we sort or filter by. An item created offline gets a local id ("local-<uuid>") and
 * pending = 1, so the screen can show it straight away with a "pending" label; when the sync
 * worker has sent it, the server's copy replaces it.
 *
 * pending_actions is the offline queue: every action taken without internet, in order, with
 * the UUID the server uses to ignore repeats.
 *
 * KinnectApp calls init() once when the app starts. Log out calls clearAll().
 */
object LocalDb {

    // Table names, used by the rest of the app.
    const val USERS = "users"
    const val FRIENDS = "friends"
    const val POSTS = "posts"
    const val COMMENTS = "comments"
    const val STORIES = "stories"
    const val CHATS = "chats"
    const val MESSAGES = "messages"
    const val NOTIFICATIONS = "notifications"
    const val PENDING = "pending_actions"
    const val CACHE = "api_cache"       // Assignment 3, step 4: whole server answers by request

    private const val NAME = "kinnect.db"
    private const val VERSION = 2       // 2: api_cache added

    private lateinit var helper: Helper
    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun init(context: Context) {
        if (!::helper.isInitialized) helper = Helper(context.applicationContext)
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            // people we have seen (profiles, search results, chat partners)
            db.execSQL("CREATE TABLE $USERS (id TEXT PRIMARY KEY, name TEXT, username TEXT, json TEXT NOT NULL)")
            // my relationship with each person: friend, requested (I sent), incoming (they sent)
            db.execSQL("CREATE TABLE $FRIENDS (user_id TEXT PRIMARY KEY, status TEXT NOT NULL, json TEXT NOT NULL)")
            db.execSQL(
                "CREATE TABLE $POSTS (id TEXT PRIMARY KEY, author_id TEXT, created_at INTEGER, " +
                    "json TEXT NOT NULL, pending INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL(
                "CREATE TABLE $COMMENTS (id TEXT PRIMARY KEY, post_id TEXT, created_at INTEGER, " +
                    "json TEXT NOT NULL, pending INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL(
                "CREATE TABLE $STORIES (id TEXT PRIMARY KEY, user_id TEXT, created_at INTEGER, " +
                    "expires_at INTEGER, json TEXT NOT NULL)"
            )
            db.execSQL("CREATE TABLE $CHATS (chat_id TEXT PRIMARY KEY, updated_at INTEGER, json TEXT NOT NULL)")
            db.execSQL(
                "CREATE TABLE $MESSAGES (id TEXT PRIMARY KEY, chat_id TEXT, created_at INTEGER, " +
                    "json TEXT NOT NULL, pending INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL("CREATE TABLE $NOTIFICATIONS (id TEXT PRIMARY KEY, created_at INTEGER, json TEXT NOT NULL)")
            // The last answer of each screen's request ("friends/lists.php", "users/profile.php?id=7"),
            // shown at once next time and when offline (see Cached.kt).
            db.execSQL("CREATE TABLE $CACHE (name TEXT PRIMARY KEY, json TEXT NOT NULL)")
            // The offline queue. uuid = the X-Request-Id the server uses to ignore repeats.
            db.execSQL(
                "CREATE TABLE $PENDING (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +   // keeps the order the actions happened in
                    "uuid TEXT NOT NULL UNIQUE, " +
                    "type TEXT NOT NULL, " +                      // e.g. send_message, create_post, react
                    "path TEXT NOT NULL, " +                      // endpoint, e.g. messages/send.php
                    "body TEXT NOT NULL, " +                      // the JSON fields to send
                    "files TEXT NOT NULL DEFAULT '[]', " +        // [{"field":"images[]","path":"/data/.../a.jpg"}]
                    "local_id TEXT, " +                           // the pending row it belongs to (e.g. a message)
                    "created_at INTEGER NOT NULL, " +
                    "attempts INTEGER NOT NULL DEFAULT 0, " +
                    "last_error TEXT)"
            )
            db.execSQL("CREATE INDEX idx_posts_time ON $POSTS (created_at)")
            db.execSQL("CREATE INDEX idx_comments_post ON $COMMENTS (post_id, created_at)")
            db.execSQL("CREATE INDEX idx_messages_chat ON $MESSAGES (chat_id, created_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // It's only a cache: start again (the queue is sent before the app updates).
            for (table in listOf(USERS, FRIENDS, POSTS, COMMENTS, STORIES, CHATS, MESSAGES, NOTIFICATIONS, PENDING, CACHE)) {
                db.execSQL("DROP TABLE IF EXISTS $table")
            }
            onCreate(db)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cache tables
    // ---------------------------------------------------------------------------------------

    /**
     * Saves (or replaces) one item. [key] = the primary key column ("id", "chat_id", "user_id"),
     * [columns] = the extra columns to fill, e.g. mapOf("chat_id" to "3_12", "created_at" to 123L).
     */
    fun put(table: String, key: String, id: String, json: JSONObject, columns: Map<String, Any?> = emptyMap()) {
        val values = ContentValues()
        values.put(key, id)
        values.put("json", json.toString())
        for ((column, value) in columns) {
            when (value) {
                null -> values.putNull(column)
                is Int -> values.put(column, value)
                is Long -> values.put(column, value)
                is Boolean -> values.put(column, if (value) 1 else 0)
                else -> values.put(column, value.toString())
            }
        }
        db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The JSON of every row that matches, e.g. list(MESSAGES, "chat_id = ?", arrayOf(id), "created_at"). */
    fun list(
        table: String,
        where: String? = null,
        args: Array<String>? = null,
        orderBy: String? = null,
        limit: Int? = null,
    ): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        db.query(table, arrayOf("json"), where, args, null, null, orderBy, limit?.toString()).use { cursor ->
            while (cursor.moveToNext()) result.add(JSONObject(cursor.getString(0)))
        }
        return result
    }

    /** One item, or null. */
    fun get(table: String, key: String, id: String): JSONObject? =
        list(table, "$key = ?", arrayOf(id), null, 1).firstOrNull()

    fun delete(table: String, where: String, args: Array<String>): Int = db.delete(table, where, args)

    /** Replaces a list in one go (e.g. the latest feed from the server), all or nothing. */
    fun replaceAll(table: String, where: String?, args: Array<String>?, rows: () -> Unit) {
        db.beginTransaction()
        try {
            db.delete(table, where, args)
            rows()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Stories past their 24 hours are removed from the phone too. */
    fun deleteExpiredStories(now: Long = System.currentTimeMillis()) {
        db.delete(STORIES, "expires_at <= ?", arrayOf(now.toString()))
    }

    /** Log out: nothing of this account stays on the phone. */
    fun clearAll() {
        for (table in listOf(USERS, FRIENDS, POSTS, COMMENTS, STORIES, CHATS, MESSAGES, NOTIFICATIONS, PENDING, CACHE)) {
            db.delete(table, null, null)
        }
    }

    // ---------------------------------------------------------------------------------------
    // The offline queue (pending_actions)
    // ---------------------------------------------------------------------------------------

    /** One queued action, as read back by the sync worker. */
    class PendingAction(
        val seq: Long,
        val uuid: String,
        val type: String,
        val path: String,
        val body: JSONObject,
        val files: JSONArray,
        val localId: String?,
        val attempts: Int,
    )

    fun enqueue(uuid: String, type: String, path: String, body: JSONObject, files: JSONArray, localId: String?) {
        val values = ContentValues()
        values.put("uuid", uuid)
        values.put("type", type)
        values.put("path", path)
        values.put("body", body.toString())
        values.put("files", files.toString())
        values.put("local_id", localId)
        values.put("created_at", System.currentTimeMillis())
        // IGNORE: the same UUID twice is still one action.
        db.insertWithOnConflict(PENDING, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** The oldest queued action (actions are sent strictly in order), or null when the queue is empty. */
    fun nextPending(): PendingAction? {
        db.query(PENDING, null, null, null, null, null, "seq", "1").use { c ->
            if (!c.moveToFirst()) return null
            return PendingAction(
                seq = c.getLong(c.getColumnIndexOrThrow("seq")),
                uuid = c.getString(c.getColumnIndexOrThrow("uuid")),
                type = c.getString(c.getColumnIndexOrThrow("type")),
                path = c.getString(c.getColumnIndexOrThrow("path")),
                body = JSONObject(c.getString(c.getColumnIndexOrThrow("body"))),
                files = JSONArray(c.getString(c.getColumnIndexOrThrow("files"))),
                localId = c.getString(c.getColumnIndexOrThrow("local_id")),
                attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
            )
        }
    }

    fun removePending(uuid: String) {
        db.delete(PENDING, "uuid = ?", arrayOf(uuid))
    }

    fun markPendingFailed(uuid: String, error: String?) {
        db.execSQL(
            "UPDATE $PENDING SET attempts = attempts + 1, last_error = ? WHERE uuid = ?",
            arrayOf(error, uuid),
        )
    }

    fun pendingCount(): Int = db.rawQuery("SELECT COUNT(*) FROM $PENDING", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }
}
