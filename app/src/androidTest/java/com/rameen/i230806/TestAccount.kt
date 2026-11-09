package com.rameen.i230806

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.rameen.i230806.data.Db
import org.hamcrest.Matcher
import java.util.concurrent.TimeUnit

/**
 * Shared helpers for the Espresso tests.
 *
 * The app now talks to Firebase, so the tests use a real account. The first run creates it
 * (with a finished profile, so login goes straight to Home); later runs just log in.
 *
 * Firebase answers over the network, and Espresso doesn't know to wait for it, so
 * [waitForView] checks again every quarter second until the view appears (or gives up).
 */
object TestAccount {

    const val EMAIL = "espresso.tester@kinnect.app"
    const val PASSWORD = "Espresso123!"
    private const val NAME = "Espresso Tester"
    private const val TIMEOUT_SECONDS = 20L

    /** Logs the test account in (creating it the first time) and returns its uid. */
    fun signIn(): String {
        val auth = FirebaseAuth.getInstance()
        val result = try {
            Tasks.await(auth.signInWithEmailAndPassword(EMAIL, PASSWORD), TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // First run: the account doesn't exist yet.
            Tasks.await(auth.createUserWithEmailAndPassword(EMAIL, PASSWORD), TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        val uid = result.user!!.uid
        // A finished profile, so AfterLogin opens Home instead of the first-time Edit profile.
        Tasks.await(
            Db.user(uid).updateChildren(
                mapOf(
                    "uid" to uid,
                    "name" to NAME,
                    "nameLower" to NAME.lowercase(),
                    "email" to EMAIL,
                    "setupDone" to true,
                )
            ),
            TIMEOUT_SECONDS, TimeUnit.SECONDS,
        )
        return uid
    }

    /** Makes sure the account exists, then logs out, so a test can start on the Login screen. */
    fun ensureExistsAndSignOut() {
        signIn()
        FirebaseAuth.getInstance().signOut()
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
