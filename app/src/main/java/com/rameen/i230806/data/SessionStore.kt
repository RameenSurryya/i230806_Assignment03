package com.rameen.i230806.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The login session saved on the phone (Assignment 3).
 *
 * After login the server sends a random session token. It is kept here, ENCRYPTED, with
 * EncryptedSharedPreferences: the file on disk is unreadable without a key that lives in the
 * phone's Android Keystore. Every request to the PHP API sends this token
 * ("Authorization: Bearer <token>"), and Splash checks it to go straight to Home, even offline.
 *
 * KinnectApp calls init() once when the app starts.
 */
@Suppress("DEPRECATION") // EncryptedSharedPreferences is the class the assignment asks for
object SessionStore {

    private const val FILE = "kinnect_session"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_SETUP_DONE = "setup_done"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        val app = context.applicationContext
        prefs = try {
            open(app)
        } catch (e: Exception) {
            // The saved key can become unreadable (e.g. after restoring a backup on a new phone):
            // throw the old file away and start logged out.
            app.deleteSharedPreferences(FILE)
            open(app)
        }
    }

    /** The encrypted preferences file: keys and values are both encrypted with an AES-256 key. */
    private fun open(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** The session token from the server, or null when logged out. */
    val token: String? get() = prefs.getString(KEY_TOKEN, null)

    /** The logged-in user's id on the server (0 when logged out). */
    val userId: Long get() = prefs.getLong(KEY_USER_ID, 0L)

    val isLoggedIn: Boolean get() = token != null && userId > 0L

    /** False until the first-time Edit profile (16) is saved. */
    var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_SETUP_DONE, value) }

    /** After a successful login or sign up. */
    fun save(token: String, userId: Long, setupDone: Boolean) {
        prefs.edit {
            putString(KEY_TOKEN, token)
            putLong(KEY_USER_ID, userId)
            putBoolean(KEY_SETUP_DONE, setupDone)
        }
    }

    /** Log out: forget the token (the server has already invalidated it). */
    fun clear() {
        prefs.edit { clear() }
    }
}