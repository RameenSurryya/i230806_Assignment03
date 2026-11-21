package com.rameen.i230806.data

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Online / offline status through our server (Assignment 3), replacing Firebase's onDisconnect.
 *
 *  - While a Kinnect screen is visible: auth/presence.php {online: true} now and every minute
 *    (a "heartbeat"). Every other request also counts as activity on the server.
 *  - When the app goes to the background: one {online: false}, so friends see "Active 1m ago".
 *  - App killed or no internet: the heartbeats stop and the server shows the user offline
 *    after 2 minutes on its own (users.php, ONLINE_WINDOW_MS).
 *
 * KinnectApp calls appVisible() / appHidden() as screens appear and disappear.
 */
object Presence {

    private const val BEAT_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val beat = object : Runnable {
        override fun run() {
            send(online = true)
            handler.postDelayed(this, BEAT_MS)
        }
    }

    /** A screen is visible. Safe to call often: only one heartbeat ever runs. */
    fun appVisible() {
        if (running) return
        running = true
        handler.post(beat)
    }

    /** No screen is visible any more. */
    fun appHidden() {
        if (!running) return
        running = false
        handler.removeCallbacks(beat)
        send(online = false)
    }

    /** Skipped when logged out (Login screen) or offline; the next beat tries again. */
    private fun send(online: Boolean) {
        if (!SessionStore.isLoggedIn || !NetworkMonitor.isOnline) return
        Api.post("auth/presence.php", JSONObject().put("online", online)) { result ->
            result.json?.optLong("time")?.let { Db.setServerTime(it) }   // keeps Db.now() right
        }
    }
}
