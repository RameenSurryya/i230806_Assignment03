package com.rameen.i230806

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.platform.app.InstrumentationRegistry
import com.rameen.i230806.data.Api
import com.rameen.i230806.data.AuthRepo
import com.rameen.i230806.data.SessionStore
import org.hamcrest.Matcher
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared helpers for the Espresso tests (Assignment 3: our PHP server, not Firebase).
 *
 * The tests use a real account on the server. The first run signs it up (and finishes its
 * profile, so login goes straight to Home); later runs just log in. The phone or emulator
 * must reach the server (res/values/server.xml) with XAMPP running.
 *
 * AuthRepo answers on the main thread; the test thread waits for it with a CountDownLatch.
 * The server answers over the network and Espresso doesn't know to wait for it, so
 * [waitForView] checks again every quarter second until the view appears (or gives up).
 */
object TestAccount {

    const val EMAIL = "espresso.tester@kinnect.app"
    const val PASSWORD = "Espresso123!"
    private const val NAME = "Espresso Tester"
    private const val TIMEOUT_SECONDS = 20L

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Runs [work] on the main thread and waits until it calls done(). */
    private fun await(work: (done: () -> Unit) -> Unit) {
        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { work { latch.countDown() } }
        check(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "The server did not answer in time" }
    }

    /** Logs the test account in (signing it up the first time) and returns its id. */
    fun signIn(): String {
        var error: String? = null
        await { done -> AuthRepo.logIn(EMAIL, PASSWORD) { user, e -> if (user == null) error = e; done() } }
        if (!SessionStore.isLoggedIn) {
            // First run: the account doesn't exist yet.
            await { done -> AuthRepo.signUp(NAME, EMAIL, PASSWORD, "Female", "2000-01-01") { user, e -> if (user == null) error = e; done() } }
        }
        check(SessionStore.isLoggedIn) { "Could not log in the test account: $error" }
        // A finished profile, so AfterLogin opens Home instead of the first-time Edit profile.
        if (!SessionStore.setupDone) {
            await { done -> AuthRepo.updateProfile(context, mapOf("name" to NAME), null, null) { _, _ -> done() } }
        }
        return SessionStore.userId.toString()
    }

    /** Makes sure the account exists, then logs out, so a test can start on the Login screen. */
    fun ensureExistsAndSignOut() {
        signIn()
        await { done -> AuthRepo.logOut(context) { done() } }
    }

    /** GET [path] and wait for the answer (null when it failed). */
    fun getNow(path: String, params: Map<String, Any?>): JSONObject? {
        var answer: JSONObject? = null
        await { done -> Api.get(path, params) { result -> if (result.ok) answer = result.json; done() } }
        return answer
    }

    /** POST [body] to [path] and wait for the answer. */
    fun postNow(path: String, body: JSONObject) {
        await { done -> Api.post(path, body) { done() } }
    }

    /** Waits until a view matching [matcher] is on screen (data may still be loading). */
    fun waitForView(matcher: Matcher<View>) {
        val giveUpAt = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000
        while (true) {
            try {
                onView(matcher).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
                return
            } catch (e: Throwable) {
                if (System.currentTimeMillis() > giveUpAt) throw e
                Thread.sleep(250)
            }
        }
    }
}
