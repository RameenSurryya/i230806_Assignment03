package com.rameen.i230806

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withParent
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rameen.i230806.data.Json
import org.json.JSONObject
import com.rameen.i230806.ui.home.HomeActivity
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test 1 (multi-step flow, with the real PHP backend):
 * Home -> Create post -> the new post appears in the feed -> Comment -> write a comment ->
 * it appears on the Comments screen -> Back -> Home.
 *
 * The post and comment text carry a unique number, so the test finds exactly its own post
 * even when the feed has others. The post is deleted again afterwards.
 */
@RunWith(AndroidJUnit4::class)
class HomeToCommentsTest {

    private lateinit var uid: String
    private val stamp = System.currentTimeMillis()
    private val postText = "$POST_PREFIX $stamp"
    private val commentText = "Espresso comment $stamp"

    @Before
    fun logIn() {
        uid = TestAccount.signIn()
    }

    @Test
    fun createPost_comment_andBackReturnsHome() {
        // Step 1: start on the Home feed (already logged in by logIn()).
        ActivityScenario.launch(HomeActivity::class.java).use {
            TestAccount.waitForView(withId(R.id.tv_wordmark))

            // Step 2: tap "What's on your mind?", type the post and tap Post.
            onView(withId(R.id.tv_composer)).perform(click())
            TestAccount.waitForView(withId(R.id.et_post_text))
            onView(withId(R.id.et_post_text)).perform(replaceText(postText))
            closeSoftKeyboard()
            onView(withId(R.id.btn_post)).perform(click())

            // Step 3: back on Home, the new post shows up in the feed (from our server).
            TestAccount.waitForView(withText(postText))

            // Step 4: tap Comment on THAT post: the ll_comment inside the feed row
            // (a direct child of rv_feed) that also contains our text.
            onView(
                allOf(
                    withId(R.id.ll_comment),
                    isDescendantOfA(allOf(withParent(withId(R.id.rv_feed)), hasDescendant(withText(postText)))),
                )
            ).perform(scrollTo(), click())

            // Step 5: the Comments screen opened.
            TestAccount.waitForView(withId(R.id.tv_comments_title))
            onView(withId(R.id.tv_comments_title)).check(matches(withText(R.string.comments_title)))

            // Step 6: write a comment and send it; it appears in the list.
            onView(withId(R.id.et_reply)).perform(replaceText(commentText))
            onView(withId(R.id.iv_send)).perform(click())
            TestAccount.waitForView(withText(commentText))

            // Step 7: Back returns to Home, where our post is still in the feed.
            closeSoftKeyboard()
            pressBack()
            TestAccount.waitForView(withId(R.id.tv_wordmark))
            onView(withId(R.id.tv_wordmark)).check(matches(isDisplayed()))
            TestAccount.waitForView(withText(postText))
        }
    }

    /** Deletes the test's posts (and their comments) so the feed doesn't fill up with them. */
    @After
    fun cleanUp() {
        val answer = TestAccount.getNow("posts/feed.php", mapOf("author" to uid, "limit" to 100)) ?: return
        for (post in Json.objects(answer.optJSONArray("posts"))) {
            if (Json.str(post, "text").startsWith(POST_PREFIX)) {
                // Deleting a post on the server deletes its comments too.
                TestAccount.postNow("posts/delete.php", JSONObject().put("id", post.optLong("id")))
            }
        }
    }

    private companion object {
        const val POST_PREFIX = "Espresso test post"
    }
}
