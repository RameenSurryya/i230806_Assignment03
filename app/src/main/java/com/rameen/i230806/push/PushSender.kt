package com.rameen.i230806.push

import com.google.firebase.messaging.FirebaseMessaging
import com.rameen.i230806.data.Api
import com.rameen.i230806.data.SessionStore
import org.json.JSONObject

/**
 * Push notifications, Assignment 3: the SERVER sends them now (backend/lib/push.php), with
 * the Firebase service-account key kept in backend/secrets on the server, not in the app.
 *
 * The app only has to tell the server where to send: this phone's FCM token.
 *  - registerThisPhone(): after login and on every app start (AfterLogin).
 *  - saveToken(): when Firebase gives the phone a new token (KinnectMessagingService).
 * Logging out clears the token on the server (auth/logout.php), so this phone gets nothing.
 *
 * The pushes arrive in KinnectMessagingService, which builds the notifications.
 */
object PushSender {

    /** Turns FCM on for this phone and sends its token to the server. */
    fun registerThisPhone() {
        if (!SessionStore.isLoggedIn) return
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        messaging.token.addOnSuccessListener { token -> saveToken(token) }
    }

    /** auth/fcm_token.php: this token now belongs to the logged-in user. */
    fun saveToken(token: String) {
        if (!SessionStore.isLoggedIn || token.isEmpty()) return
        Api.post("auth/fcm_token.php", JSONObject().put("token", token)) { }
    }

    // Kinds of push, set by the server (push.php) and read by KinnectMessagingService.
    const val TYPE_MESSAGE = "message"
    const val TYPE_CALL = "call"
    const val TYPE_ALERT = "alert"      // friend requests, reactions, comments ...
}
