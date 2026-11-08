package com.rameen.i230806.ui.auth

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.rameen.i230806.data.Db
import com.rameen.i230806.databinding.ActivitySplashBinding
import com.rameen.i230806.ui.BaseActivity

/**
 * 01 Splash: the launcher screen. Shows the logo for 5 seconds, then:
 *  - nobody logged in            -> Login
 *  - already logged in           -> Home (or Edit profile if the profile was never set up)
 *
 * Firebase remembers the logged-in user on the phone, so Db.auth.currentUser is still set after
 * the app is closed and opened again: that is what "stay logged in" means here.
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
        val uid = Db.uid
        if (uid == null) {
            openLogin()
            return
        }
        // Logged in: read the profile to know if the first-time setup was finished.
        Db.getUser(uid) { user ->
            if (isFinishing) return@getUser
            if (user == null) {
                // The account exists but its profile is missing: start over at Login.
                Db.auth.signOut()
                openLogin()
            } else {
                openAfterLogin(user)
            }
        }
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