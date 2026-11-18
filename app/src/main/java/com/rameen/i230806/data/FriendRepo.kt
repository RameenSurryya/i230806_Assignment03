package com.rameen.i230806.data

import android.content.Context
import org.json.JSONObject

/**
 * Everything about friendships, in one place (used by Other profile, Search, Friends and
 * Notifications). Assignment 3: every change is one call to friends/action.php on our server,
 * which updates MySQL (friend_requests, friends both ways, close_friends) in one transaction
 * and adds the Notifications row (friend request, request accepted).
 *
 * Actions go through the Outbox: offline they wait in the queue with their UUID and are sent
 * in order when the internet is back; the server ignores a repeated UUID.
 */
object FriendRepo {

    /** How the logged-in user is related to someone else. */
    enum class Status { NONE, REQUEST_SENT, REQUEST_RECEIVED, FRIENDS }

    // The actions friends/action.php understands.
    const val REQUEST = "request"     // Add friend
    const val CANCEL = "cancel"       // take back my request
    const val ACCEPT = "accept"       // Confirm
    const val DECLINE = "decline"     // Delete their request
    const val UNFRIEND = "unfriend"
    const val CLOSE = "close"         // add to close friends
    const val UNCLOSE = "unclose"     // remove from close friends

    /** The server's word for the relationship -> Status. */
    fun statusOf(word: String): Status = when (word) {
        "friends" -> Status.FRIENDS
        "received" -> Status.REQUEST_RECEIVED
        "sent" -> Status.REQUEST_SENT
        else -> Status.NONE
    }

    /**
     * Does [action] with the person [uid]. [onDone] gets the server's answer {status, close},
     * or null when it was queued offline (the screen keeps its optimistic change).
     */
    fun act(context: Context, action: String, uid: String, onDone: (JSONObject?) -> Unit = {}) {
        val body = JSONObject().put("action", action).put("user_id", uid.toLong())
        Outbox.send(context, "friend_$action", "friends/action.php", body) { result ->
            onDone(if (result != null && result.ok) result.json else null)
        }
    }
}
