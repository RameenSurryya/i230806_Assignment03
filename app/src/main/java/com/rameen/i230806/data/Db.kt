package com.rameen.i230806.data

/**
 * Small shared helpers (Assignment 3: Firebase Auth and the Realtime Database are gone; the
 * data lives in MySQL behind our PHP web services and in SQLite on the phone).
 *
 *  - uid: the logged-in user's id from our server ("12"), kept in SessionStore after login.
 *  - chatId: the id of a conversation, the same on both phones and on the server.
 *  - now(): the time by the SERVER's clock, so "2m ago", the 5-minute edit window and the
 *    24-hour stories don't depend on a phone's clock being right.
 *  - getUser / remember: people's profiles, from memory, SQLite or the server.
 */
object Db {

    /** The logged-in user's id, or null when nobody is logged in. */
    val uid: String? get() = SessionStore.userId.takeIf { it > 0 }?.toString()

    /**
     * The id of the conversation between two people: smaller id + "_" + bigger id, compared
     * as numbers ("9" before "10"), exactly like the server's chat_id().
     */
    fun chatId(uidA: String, uidB: String): String {
        val a = uidA.toLongOrNull() ?: 0
        val b = uidB.toLongOrNull() ?: 0
        return if (a < b) "${a}_$b" else "${b}_$a"
    }

    // ---------------------------------------------------------------------------------------
    // Server time
    // ---------------------------------------------------------------------------------------

    /** Server clock minus phone clock, in milliseconds (updated by every presence heartbeat). */
    @Volatile
    private var serverOffset = 0L

    /** The current time by the server's clock. */
    fun now(): Long = System.currentTimeMillis() + serverOffset

    /** Called with the "time" a server answer carried. */
    fun setServerTime(serverMs: Long) {
        if (serverMs > 0) serverOffset = serverMs - System.currentTimeMillis()
    }

    // ---------------------------------------------------------------------------------------
    // Users
    // ---------------------------------------------------------------------------------------

    /** Users already loaded once, so lists don't load the same person again for every row. */
    private val userCache = HashMap<String, User>()

    /**
     * Loads one user and passes it to [onLoaded] (null if unknown and offline):
     * memory first, then the phone's SQLite copy, then our server (users/profile.php).
     */
    fun getUser(uid: String, onLoaded: (User?) -> Unit) {
        userCache[uid]?.let { onLoaded(it); return }
        Json.cachedUser(uid)?.let {
            userCache[uid] = it
            onLoaded(it)
            return
        }
        if (uid.isEmpty()) {
            onLoaded(null)
            return
        }
        Api.get("users/profile.php", mapOf("id" to uid)) { result ->
            val json = result.json
            onLoaded(if (result.ok && json != null) Json.saveUser(json.getJSONObject("user")) else null)
        }
    }

    /** Puts a fresh copy of [user] in the cache (after a profile edit or a refresh). */
    fun remember(user: User) {
        if (user.uid.isNotEmpty()) userCache[user.uid] = user
    }

    /** Log out: forget everyone. */
    fun clearCache() = userCache.clear()
}
