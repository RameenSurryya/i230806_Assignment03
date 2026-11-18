package com.rameen.i230806

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import com.rameen.i230806.data.Api
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.LocalDb
import com.rameen.i230806.data.NetworkMonitor
import com.rameen.i230806.data.Presence
import com.rameen.i230806.data.SyncWorker
import com.rameen.i230806.data.SessionStore

/**
 * Runs once when the app process starts, before any screen (registered in AndroidManifest.xml
 * with android:name=".KinnectApp"). It does three app-wide jobs:
 *
 *  1. Creates the notification channels (Android 8+ needs one per kind of notification).
 *  2. Keeps Db.now() in sync with Firebase's clock.
 *  3. Online / offline status ("Active now", green dots), Assignment 3:
 *     - online while at least one Kinnect screen is visible, offline when the app goes to the
 *       background (counted with ActivityLifecycleCallbacks, sent by Presence);
 *     - if the app is killed or loses internet, the heartbeats stop and our server shows the
 *       user offline after 2 minutes on its own.
 *  4. Assignment 3 set-up: session, web services, SQLite, network monitor, offline queue.
 */
class KinnectApp : Application() {

    /** How many Kinnect screens are currently on screen (started but not stopped). */
    private var visibleScreens = 0

    override fun onCreate() {
        super.onCreate()
        SessionStore.init(this)       // Assignment 3: the encrypted session token
        Api.init(this)                // Assignment 3: Volley requests to the PHP web services
        LocalDb.init(this)            // Assignment 3: SQLite cache + offline queue
        NetworkMonitor.init(this)     // Assignment 3: online / offline, restarts the sync on reconnect
        SyncWorker.schedule(this)     // Assignment 3: send anything still queued from last time
        createNotificationChannels()
        Db.watchServerTime()

        registerActivityLifecycleCallbacks(ScreenCounter())
    }

    /** Online when the first screen appears, offline when the last one disappears. */
    private inner class ScreenCounter : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            visibleScreens++
            // Every time (not only for the first screen): Home opening right after login must
            // start the heartbeat too. Presence ignores repeated calls.
            Presence.appVisible()
        }

        override fun onActivityStopped(activity: Activity) {
            visibleScreens--
            if (visibleScreens == 0) Presence.appHidden()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** Three channels: chat messages, calls (both pop up on screen) and everything else. */
    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES, getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALLS, getString(R.string.channel_calls),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GENERAL, getString(R.string.channel_general),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"   // also the default in AndroidManifest.xml
        const val CHANNEL_CALLS = "calls"
        const val CHANNEL_GENERAL = "general"
    }
}
