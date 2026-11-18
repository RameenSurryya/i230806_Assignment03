package com.rameen.i230806

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.rameen.i230806.data.Api
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.LocalDb
import com.rameen.i230806.data.NetworkMonitor
import com.rameen.i230806.data.SyncWorker
import com.rameen.i230806.data.SessionStore

/**
 * Runs once when the app process starts, before any screen (registered in AndroidManifest.xml
 * with android:name=".KinnectApp"). It does three app-wide jobs:
 *
 *  1. Creates the notification channels (Android 8+ needs one per kind of notification).
 *  2. Keeps Db.now() in sync with Firebase's clock.
 *  3. Online / offline status ("Active now", green dots):
 *     - online while at least one Kinnect screen is visible, offline when the app goes to the
 *       background (counted with ActivityLifecycleCallbacks);
 *     - if the app is killed or loses internet, Firebase's server itself sets online = false
 *       (onDisconnect), because the phone can no longer do it.
 */
class KinnectApp : Application() {

    /** How many Kinnect screens are currently on screen (started but not stopped). */
    private var visibleScreens = 0

    /** Firebase's special ".info/connected" path: true while this phone is connected. */
    private var connectedRef: DatabaseReference? = null
    private var connectedListener: ValueEventListener? = null

    override fun onCreate() {
        super.onCreate()
        SessionStore.init(this)       // Assignment 3: the encrypted session token
        Api.init(this)                // Assignment 3: Volley requests to the PHP web services
        LocalDb.init(this)            // Assignment 3: SQLite cache + offline queue
        NetworkMonitor.init(this)     // Assignment 3: online / offline, restarts the sync on reconnect
        SyncWorker.schedule(this)     // Assignment 3: send anything still queued from last time
        createNotificationChannels()
        Db.watchServerTime()

        // Runs now, and again every time someone logs in or out.
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            stopPresence()
            val uid = auth.currentUser?.uid
            if (uid != null) startPresence(uid)
        }

        registerActivityLifecycleCallbacks(ScreenCounter())
    }

    /** Called whenever this phone (re)connects to Firebase while [uid] is logged in. */
    private fun startPresence(uid: String) {
        val ref = FirebaseDatabase.getInstance().getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.getValue(Boolean::class.java) != true) return
                // Tell the server what to write if this connection drops (app killed, no network).
                val me = Db.user(uid)
                me.child("online").onDisconnect().setValue(false)
                me.child("lastSeen").onDisconnect().setValue(ServerValue.TIMESTAMP)
                // Connected again: we are online if a screen is showing.
                if (visibleScreens > 0) Db.setOnline(true)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        connectedRef = ref
        connectedListener = listener
    }

    private fun stopPresence() {
        connectedListener?.let { connectedRef?.removeEventListener(it) }
        connectedRef = null
        connectedListener = null
    }

    /** Online when the first screen appears, offline when the last one disappears. */
    private inner class ScreenCounter : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            visibleScreens++
            if (visibleScreens == 1) Db.setOnline(true)
        }

        override fun onActivityStopped(activity: Activity) {
            visibleScreens--
            if (visibleScreens == 0) Db.setOnline(false)
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