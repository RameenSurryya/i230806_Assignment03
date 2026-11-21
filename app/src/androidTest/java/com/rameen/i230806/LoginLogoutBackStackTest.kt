package com.rameen.i230806

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.rameen.i230806.data.SessionStore
import com.rameen.i230806.ui.auth.LoginActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test 2 (back stack, with the real PHP login):
 * Login (type email + password) -> Home -> Menu -> Log out -> Login, then Back leaves the app,
 * and the phone no longer has a session token (SessionStore).
 */
@RunWith(AndroidJUnit4::class)
class LoginLogoutBackStackTest {

    @Before
    fun startLoggedOut() {
        TestAccount.ensureExistsAndSignOut()
    }

    @Test
    fun logoutClearsBackStack() {
        // Step 1: start the app on the Login screen.
        ActivityScenario.launch(LoginActivity::class.java).use {

            // Step 2: type the test account and tap "Log in"; Home appears once the server answers.
            onView(withId(R.id.et_email)).perform(replaceText(TestAccount.EMAIL))
            onView(withId(R.id.et_password)).perform(replaceText(TestAccount.PASSWORD))
            closeSoftKeyboard()
            onView(withId(R.id.btn_login)).perform(click())
            TestAccount.waitForView(withId(R.id.tv_wordmark))
            onView(withId(R.id.tv_wordmark)).check(matches(isDisplayed()))

            // Step 3: open the Menu tab, scroll to "Log out" and tap it.
            onView(withId(R.id.fl_tab_menu)).perform(click())
            TestAccount.waitForView(withId(R.id.ll_logout))
            onView(withId(R.id.ll_logout)).perform(scrollTo(), click())

            // Step 4: the Login screen is showing again, and nobody is logged in.
            TestAccount.waitForView(withId(R.id.btn_login))
            onView(withId(R.id.btn_login)).check(matches(isDisplayed()))
            assertFalse("The session token should be gone", SessionStore.isLoggedIn)

            // Step 5: keep a reference to that new Login screen, so we can check it after Back.
            val login = resumedActivity()
            assertTrue("Login should be on screen", login is LoginActivity)

            // Step 6: press Back. "Unconditionally" because leaving the app is what we expect
            // (plain pressBack() would fail the test when the app closes).
            pressBackUnconditionally()

            // Step 7: Login was finished and no earlier screen (Menu, Home) came back,
            // because Log out cleared the back stack.
            assertTrue("Login should be finished", login!!.isFinishing || login.isDestroyed)
            assertTrue("No Kinnect screen should return", resumedActivity() == null)
        }
    }

    /** The app screen currently in the foreground, or null if none (the app has closed). */
    private fun resumedActivity(): Activity? {
        var activity: Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return activity
    }
}
