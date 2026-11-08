package com.rameen.i230806.ui.auth

import android.app.Activity
import android.content.Intent
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.User
import com.rameen.i230806.ui.home.HomeActivity
import com.rameen.i230806.ui.profile.EditProfileActivity

/**
 * Where to go once we know who is logged in. Shared by Splash, Login and Sign up.
 *
 *  - First time (profile not set up yet: no username, bio or photo chosen) -> Edit profile,
 *    with Extra.FIRST_TIME so it shows "Save" -> Home instead of going back.
 *  - Everyone else -> Home.
 *
 * NEW_TASK | CLEAR_TASK removes Splash / Login / Sign up from the back stack, so pressing Back
 * on Home leaves the app instead of returning to the login screens.
 */
fun Activity.openAfterLogin(user: User) {
    val next = if (user.setupDone) {
        Intent(this, HomeActivity::class.java)
    } else {
        Intent(this, EditProfileActivity::class.java).putExtra(Extra.FIRST_TIME, true)
    }
    startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    finish()
}