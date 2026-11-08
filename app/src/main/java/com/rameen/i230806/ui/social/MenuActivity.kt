package com.rameen.i230806.ui.social

import android.content.Intent
import android.os.Bundle
import com.google.firebase.database.ServerValue
import com.rameen.i230806.R
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityMenuBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.auth.LoginActivity
import com.rameen.i230806.ui.market.MarketplaceActivity
import com.rameen.i230806.ui.profile.ProfileActivity
import com.rameen.i230806.util.Avatars

/**
 * 19 Menu.
 *  - Top bar (title, search, messenger), the tabs and their badges are wired by BaseActivity.
 *  - Profile card: the logged-in user's name and picture, live from users/{uid} (changes made
 *    in Edit profile show up here straight away). Tapping it opens Profile.
 *  - The Friends and Marketplace shortcuts open those screens.
 *  - Log out: marks the user offline, signs out of Firebase and opens Login with the back
 *    stack cleared, so Back leaves the app.
 *  - Memories, Saved, Groups, Events, See more and the three rows do nothing.
 */
class MenuActivity : BaseActivity() {

    private lateinit var binding: ActivityMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTopBar(R.string.title_menu)
        setupTopTabs(Tab.MENU)

        // Live profile card: watch() listens while the screen is visible (BaseActivity).
        watch(Db.user(myUid)) { snapshot ->
            snapshot.getValue(User::class.java)?.let { showProfileCard(it) }
        }

        binding.llProfileCard.setOnClickListener {
            openScreen(ProfileActivity::class.java)
        }
        // Friends and Marketplace are tab screens too: bring them to the front, don't stack copies.
        binding.llShortcutFriends.setOnClickListener {
            openScreen(FriendsActivity::class.java, Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        binding.llShortcutMarketplace.setOnClickListener {
            openScreen(MarketplaceActivity::class.java, Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        binding.llLogout.setOnClickListener { logOut() }
    }

    private fun showProfileCard(user: User) {
        Db.remember(user)
        binding.tvMenuName.text = user.name
        Avatars.bind(binding.tvMenuAvatar, binding.ivMenuAvatarPhoto, user)
        binding.llProfileCard.contentDescription = getString(R.string.cd_see_profile_of, user.name)
    }

    /**
     * 1. Write online = false while still logged in (the database rules only let a user change
     *    their own profile, so this must happen before signing out).
     * 2. Sign out of Firebase.
     * 3. Login, with every earlier screen removed (NEW_TASK | CLEAR_TASK).
     * Without internet the write never confirms, so after 2 seconds we log out anyway.
     */
    private fun logOut() {
        binding.llLogout.isEnabled = false
        var done = false
        val finishLogOut = {
            if (!done) {
                done = true
                Db.auth.signOut()
                openScreen(
                    LoginActivity::class.java,
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                )
            }
        }
        Db.user(myUid)
            .updateChildren(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP))
            .addOnCompleteListener { finishLogOut() }
        binding.root.postDelayed({ finishLogOut() }, LOGOUT_TIMEOUT_MS)
    }

    private companion object {
        const val LOGOUT_TIMEOUT_MS = 2000L
    }
}