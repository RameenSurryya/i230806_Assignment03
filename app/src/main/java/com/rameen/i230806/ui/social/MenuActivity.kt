package com.rameen.i230806.ui.social

import android.content.Intent
import android.os.Bundle
import com.rameen.i230806.R
import com.rameen.i230806.data.AuthRepo
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
 *  - Profile card: the logged-in user's name and picture, from SQLite at once and refreshed
 *    from the server every time the screen shows (so Edit profile changes appear). Tapping it
 *    opens Profile.
 *  - The Friends and Marketplace shortcuts open those screens.
 *  - Log out (Assignment 3): the server invalidates the token and marks the user offline; the
 *    phone forgets the token and empties its SQLite cache and offline queue; then Login opens
 *    with the back stack cleared, so Back leaves the app.
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

    override fun onStart() {
        super.onStart()
        AuthRepo.me { user -> if (user != null && !isDestroyed) showProfileCard(user) }
    }

    private fun showProfileCard(user: User) {
        binding.tvMenuName.text = user.name
        Avatars.bind(binding.tvMenuAvatar, binding.ivMenuAvatarPhoto, user)
        binding.llProfileCard.contentDescription = getString(R.string.cd_see_profile_of, user.name)
    }

    /** AuthRepo.logOut clears the phone even offline (after at most 3 seconds). */
    private fun logOut() {
        binding.llLogout.isEnabled = false
        AuthRepo.logOut(this) {
            openScreen(
                LoginActivity::class.java,
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
        }
    }
}
