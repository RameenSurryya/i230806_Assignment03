package com.rameen.i230806.data

import android.content.Context
import android.net.Uri
import androidx.work.WorkManager
import org.json.JSONObject

/**
 * Accounts through our own web services (Assignment 3): sign up, log in, log out, my profile.
 *
 *  - Sign up / log in: the server answers {token, user}. The token is saved encrypted
 *    (SessionStore) and sent with every later request; the user is cached in SQLite.
 *  - Log out: the server invalidates the token and marks me offline; then the phone forgets
 *    the token, empties the SQLite cache and the offline queue.
 *  - My profile is read from SQLite first, so Splash and Home work offline.
 *
 * Callbacks run on the main thread. [error] is the server's message ("Wrong email or password").
 */
object AuthRepo {

    fun signUp(
        name: String,
        email: String,
        password: String,
        gender: String,
        birthday: String,
        onDone: (user: User?, error: String?) -> Unit,
    ) {
        val body = JSONObject()
            .put("name", name)
            .put("email", email)
            .put("password", password)
            .put("gender", gender)
            .put("birthday", birthday)
        Api.post("auth/signup.php", body) { result -> loggedIn(result, onDone) }
    }

    fun logIn(email: String, password: String, onDone: (user: User?, error: String?) -> Unit) {
        val body = JSONObject().put("email", email).put("password", password)
        Api.post("auth/login.php", body) { result -> loggedIn(result, onDone) }
    }

    /** Both sign up and log in answer {token, user}: save the session and the user. */
    private fun loggedIn(result: Api.Result, onDone: (User?, String?) -> Unit) {
        val json = result.json
        if (!result.ok || json == null) {
            onDone(null, result.error)
            return
        }
        val userJson = json.getJSONObject("user")
        LocalDb.clearAll()                       // start clean: nothing from a previous account
        val user = Json.saveUser(userJson)
        SessionStore.save(json.getString("token"), user.uid.toLong(), user.setupDone)
        onDone(user, null)
    }

    /**
     * Log out (Menu, 19): tell the server (token invalidated, marked offline), then clear the
     * phone in every case, even offline. [onDone] runs when the phone is cleared.
     */
    fun logOut(context: Context, onDone: () -> Unit) {
        val app = context.applicationContext
        var finished = false
        val clear = {
            if (!finished) {
                finished = true
                WorkManager.getInstance(app).cancelAllWork()
                LocalDb.clearAll()
                SessionStore.clear()
                onDone()
            }
        }
        if (!NetworkMonitor.isOnline) {
            clear()
            return
        }
        Api.post("auth/logout.php", JSONObject()) { clear() }
    }

    /** My own profile: the SQLite copy straight away (may be null), fresh from the server after. */
    fun me(onLoaded: (User?) -> Unit) {
        val id = SessionStore.userId
        if (id == 0L) {
            onLoaded(null)
            return
        }
        Json.cachedUser(id.toString())?.let(onLoaded)
        Api.get("auth/me.php") { result ->
            val json = result.json
            if (result.ok && json != null) {
                val user = Json.saveUser(json.getJSONObject("user"))
                SessionStore.setupDone = user.setupDone
                onLoaded(user)
            }
        }
    }

    /** The cached copy of my profile, or null. */
    fun cachedMe(): User? = SessionStore.userId.takeIf { it > 0 }?.let { Json.cachedUser(it.toString()) }

    /**
     * Edit profile (16): [fields] = name, username, bio, website, work, study, city, phone.
     * [photo] / [cover] = newly picked pictures (or null). Needs the internet (the server checks
     * that the username is free), so it is not queued.
     */
    fun updateProfile(
        context: Context,
        fields: Map<String, String>,
        photo: Uri?,
        cover: Uri?,
        onDone: (user: User?, error: String?) -> Unit,
    ) {
        val files = listOfNotNull(
            photo?.let { Api.filePart(context, "photo", it) },
            cover?.let { Api.filePart(context, "cover", it) },
        )
        Api.upload("profile/update.php", fields, files) { result ->
            val json = result.json
            if (result.ok && json != null) {
                val user = Json.saveUser(json.getJSONObject("user"))
                SessionStore.setupDone = true
                onDone(user, null)
            } else {
                onDone(null, result.error)
            }
        }
    }
}
