package com.rameen.i230806.util

import android.content.Context
import com.rameen.i230806.data.User

/**
 * Remembers the last account that logged in on this phone, for the "Recent login" card on the
 * Login screen (02). Stored with SharedPreferences: a small key-value file inside the app that
 * stays after the app is closed, but never leaves the phone.
 *
 * Only the uid, name and email are kept, never the password.
 */
object RecentLogin {

    private const val FILE = "recent_login"
    private const val KEY_UID = "uid"
    private const val KEY_NAME = "name"
    private const val KEY_EMAIL = "email"

    /** What the card shows. */
    data class Account(val uid: String, val name: String, val email: String)

    /** Called after every successful log in or sign up. */
    fun save(context: Context, user: User) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_UID, user.uid)
            .putString(KEY_NAME, user.name)
            .putString(KEY_EMAIL, user.email)
            .apply()
    }

    /** The remembered account, or null if nobody has logged in on this phone yet. */
    fun load(context: Context): Account? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        return Account(
            uid = prefs.getString(KEY_UID, "") ?: "",
            name = prefs.getString(KEY_NAME, "") ?: "",
            email = email,
        )
    }

    /** The "..." on the card: forget this account on this phone. */
    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}