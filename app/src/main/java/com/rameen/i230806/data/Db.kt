package com.rameen.i230806.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

/**
 * One place for every Firebase path, so the rest of the app never types "users" or "posts"
 * by hand. Example: Db.user(uid).child("bio").setValue("Hello") writes users/{uid}/bio.
 * The full database layout is described at the top of Models.kt.
 */
object Db {

    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val database: FirebaseDatabase get() = FirebaseDatabase.getInstance()
    val root: DatabaseReference get() = database.reference

    /**
     * The logged-in user's id, or null when nobody is logged in.
     * Assignment 3: the id from our server ("12"), kept in SessionStore after login.
     */
    val uid: String? get() = SessionStore.userId.takeIf { it > 0 }?.toString()

    // ---------------------------------------------------------------------------------------
    // Paths
    // ---------------------------------------------------------------------------------------

    fun users(): DatabaseReference = root.child("users")
    fun user(uid: String): DatabaseReference = users().child(uid)
    fun usernames(): DatabaseReference = root.child("usernames")

    fun friends(uid: String): DatabaseReference = root.child("friends").child(uid)
    fun friendRequests(toUid: String): DatabaseReference = root.child("friendRequests").child(toUid)
    fun closeFriends(uid: String): DatabaseReference = root.child("closeFriends").child(uid)

    fun posts(): DatabaseReference = root.child("posts")
    fun post(postId: String): DatabaseReference = posts().child(postId)
    fun comments(postId: String): DatabaseReference = root.child("comments").child(postId)

    fun allStories(): DatabaseReference = root.child("stories")
    fun stories(uid: String): DatabaseReference = allStories().child(uid)
    fun media(mediaId: String): DatabaseReference = root.child("media").child(mediaId)

    fun notifications(uid: String): DatabaseReference = root.child("notifications").child(uid)

    fun userChats(uid: String): DatabaseReference = root.child("userChats").child(uid)
    fun messages(chatId: String): DatabaseReference = root.child("messages").child(chatId)
    fun typing(chatId: String): DatabaseReference = root.child("typing").child(chatId)
    fun chatReads(chatId: String): DatabaseReference = root.child("chatReads").child(chatId)

    fun calls(uid: String): DatabaseReference = root.child("calls").child(uid)
    fun callLogs(uid: String): DatabaseReference = root.child("callLogs").child(uid)

    fun listings(): DatabaseReference = root.child("listings")

    /**
     * The id of the conversation between two people. Both phones compute the same id because
     * the two uids are always put in alphabetical order: chatId("b", "a") == chatId("a", "b") == "a_b".
     */
    fun chatId(uidA: String, uidB: String): String =
        if (uidA < uidB) "${uidA}_$uidB" else "${uidB}_$uidA"

    // ---------------------------------------------------------------------------------------
    // Server time
    // ---------------------------------------------------------------------------------------

    /** How far this phone's clock is from Firebase's clock, in milliseconds. */
    @Volatile
    private var serverOffset = 0L

    /**
     * The current time by Firebase's clock. Used for anything that is compared between phones
     * (the 5-minute message edit window, 24-hour stories), so a phone with a wrong clock
     * can't cheat or break them.
     */
    fun now(): Long = System.currentTimeMillis() + serverOffset

    /** Keeps [serverOffset] up to date. Called once from KinnectApp. */
    fun watchServerTime() {
        database.getReference(".info/serverTimeOffset")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    serverOffset = (snapshot.value as? Number)?.toLong() ?: 0L
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    // ---------------------------------------------------------------------------------------
    // Users
    // ---------------------------------------------------------------------------------------

    /** Users already loaded once, so lists don't download the same person again for every row. */
    private val userCache = HashMap<String, User>()

    /**
     * Loads one user and passes it to [onLoaded] (null if unknown and offline).
     * Assignment 3: memory first, then the phone's SQLite copy, then our server
     * (users/profile.php), which is saved to SQLite for next time.
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

    /** Puts a fresh copy of [user] in the cache (after a profile edit or a live update). */
    fun remember(user: User) {
        if (user.uid.isNotEmpty()) userCache[user.uid] = user
    }

    /** Marks the logged-in user online or offline and records when they were last seen. */
    fun setOnline(online: Boolean) {
        val me = uid ?: return
        user(me).updateChildren(
            mapOf("online" to online, "lastSeen" to ServerValue.TIMESTAMP)
        )
    }

    // ---------------------------------------------------------------------------------------
    // Notifications screen (18)
    // ---------------------------------------------------------------------------------------

    /**
     * Adds a row to [toUid]'s Notifications screen, e.g.
     * Db.notify(postAuthorId, NotifType.COMMENT, postId = post.id). Nothing is sent to yourself.
     */
    fun notify(toUid: String, type: String, postId: String = "", text: String = "") {
        val me = uid ?: return
        if (toUid == me || toUid.isEmpty()) return
        val ref = notifications(toUid).push()
        ref.setValue(
            AppNotification(
                id = ref.key ?: "",
                type = type,
                fromUid = me,
                postId = postId,
                text = text,
                timestamp = now(),
                read = false,
            )
        )
    }
}
