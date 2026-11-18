package com.rameen.i230806.ui.auth

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.rameen.i230806.data.AuthRepo
import com.rameen.i230806.data.SessionStore
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivitySplashBinding
import com.rameen.i230806.ui.BaseActivity

/**
 * 01 Splash: the launcher screen. Shows the logo for 5 seconds, then:
 *  - nobody logged in            -> Login
 *  - already logged in           -> Home (or Edit profile if the profile was never set up)
 *
 * Assignment 3: "logged in" means the encrypted SessionStore holds a token from our server.
 * The decision uses only what is on the phone (token + profile cached in SQLite), so it works
 * offline too: Home then shows the cached posts and messages.
 */
class SplashActivity : BaseActivity() {

    // Teal background: white status bar icons.
    override val darkSystemBars = true

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivitySplashBinding.inflate(layoutInflater).root)

        // Wait 5 seconds, then decide where to go.
        handler.postDelayed({ moveOn() }, SPLASH_DELAY_MS)
    }

    private fun moveOn() {
        if (!SessionStore.isLoggedIn) {
            openLogin()
            return
        }
        // My profile from SQLite; if it isn't cached, a stand-in with just the id is enough to
        // choose between Home and Edit profile.
        val me = AuthRepo.cachedMe()
            ?: User(uid = SessionStore.userId.toString(), setupDone = SessionStore.setupDone)
        openAfterLogin(me)
    }

    /** Login replaces Splash, so Back on Login leaves the app instead of returning here. */
    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        // If the user leaves during the 5 seconds, cancel the pending jump.
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val SPLASH_DELAY_MS = 5000L
    }
}
