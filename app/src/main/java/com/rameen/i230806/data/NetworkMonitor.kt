package com.rameen.i230806.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Knows whether the phone has internet right now, and reacts when it comes back.
 *
 *  - isOnline: checked before sending an action (offline -> it goes into the queue).
 *  - online: LiveData a screen can observe, e.g. to show an "Offline" note.
 *  - The moment the connection returns, the sync worker is started to send the queue.
 *
 * KinnectApp calls init() once when the app starts.
 */
object NetworkMonitor {

    private val _online = MutableLiveData(false)
    val online: LiveData<Boolean> get() = _online

    @Volatile
    var isOnline: Boolean = false
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(ConnectivityManager::class.java)
        isOnline = manager.activeNetwork?.let { hasInternet(manager, it) } == true
        _online.postValue(isOnline)

        // Android calls these whenever the default network (Wi-Fi or mobile data) changes.
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isOnline = true
                _online.postValue(true)
                SyncWorker.schedule(app)          // send what was queued while offline
            }

            override fun onLost(network: Network) {
                isOnline = false
                _online.postValue(false)
            }
        })
    }

    private fun hasInternet(manager: ConnectivityManager, network: Network): Boolean =
        manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}