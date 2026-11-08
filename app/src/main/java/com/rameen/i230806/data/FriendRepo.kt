package com.rameen.i230806.data

/**
 * Everything about friendships, in one place (used by Other profile, Search, Friends and
 * Notifications). How it is stored (see Models.kt):
 *
 *   friendRequests/{toUid}/{fromUid} = time sent     "fromUid asked toUid"
 *   friends/{uid}/{friendUid}        = time accepted  stored on BOTH people
 *   closeFriends/{uid}/{friendUid}   = true           only on the side that chose it
 *
 * Accepting writes several paths at once with updateChildren on the root: either all of them
 * are saved or none are, so two people can never end up "half friends".
 */
object FriendRepo {

    /** How the logged-in user is related to someone else. */
    enum class Status { NONE, REQUEST_SENT, REQUEST_RECEIVED, FRIENDS }

    private val me: String get() = Db.uid ?: ""

    /** "Add friend": asks [toUid] to be friends and adds a row to their Notifications. */
    fun sendRequest(toUid: String) {
        Db.friendRequests(toUid).child(me).setValue(Db.now())
        Db.notify(toUid, NotifType.FRIEND_REQUEST)
    }

    /** Takes back a request I sent. */
    fun cancelRequest(toUid: String) {
        Db.friendRequests(toUid).child(me).removeValue()
    }

    /** "Confirm": [fromUid] asked me; we become friends on both sides and the request goes away. */
    fun accept(fromUid: String) {
        val now = Db.now()
        Db.root.updateChildren(
            mapOf(
                "friends/$me/$fromUid" to now,
                "friends/$fromUid/$me" to now,
                "friendRequests/$me/$fromUid" to null,
                "friendRequests/$fromUid/$me" to null,   // in case both asked each other
            )
        )
        Db.notify(fromUid, NotifType.FRIEND_ACCEPTED)
    }

    /** "Delete": refuses a request [fromUid] sent me. They are not told. */
    fun decline(fromUid: String) {
        Db.friendRequests(me).child(fromUid).removeValue()
    }

    /** Ends the friendship on both sides, and removes them from my close friends. */
    fun unfriend(uid: String) {
        Db.root.updateChildren(
            mapOf(
                "friends/$me/$uid" to null,
                "friends/$uid/$me" to null,
                "closeFriends/$me/$uid" to null,
                "closeFriends/$uid/$me" to null,
            )
        )
    }

    /** Close friends see the stories shared to "Close friends" (Story editor, step 6). */
    fun setCloseFriend(uid: String, close: Boolean) {
        val ref = Db.closeFriends(me).child(uid)
        if (close) ref.setValue(true) else ref.removeValue()
    }

    /**
     * Works out the status from the three facts the screen is watching:
     * are we friends, did I ask them, did they ask me.
     */
    fun statusOf(areFriends: Boolean, iAsked: Boolean, theyAsked: Boolean): Status = when {
        areFriends -> Status.FRIENDS
        theyAsked -> Status.REQUEST_RECEIVED
        iAsked -> Status.REQUEST_SENT
        else -> Status.NONE
    }
}