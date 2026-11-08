package com.rameen.i230806.ui.profile

import android.app.Activity
import android.content.Intent
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra

/**
 * Opens someone's profile from anywhere (Search, Home, Comments, Friends, Notifications ...):
 * your own uid opens Profile (15), anyone else opens Other profile (17).
 */
fun Activity.openProfile(uid: String) {
    if (uid.isEmpty()) return
    val screen = if (uid == Db.uid) ProfileActivity::class.java else OtherProfileActivity::class.java
    startActivity(Intent(this, screen).putExtra(Extra.UID, uid))
}