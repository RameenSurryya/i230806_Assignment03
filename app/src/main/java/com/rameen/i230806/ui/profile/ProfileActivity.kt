package com.rameen.i230806.ui.profile

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rameen.i230806.R
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityProfileBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.home.CameraActivity
import com.rameen.i230806.ui.home.SearchActivity
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Images
import com.rameen.i230806.util.Times

/**
 * 15 Profile: your own profile, live from the database.
 *  - users/{my uid}: name, pictures, bio and the Details rows (each row hidden when empty).
 *  - friends/{my uid}: the friend count and the first three friends as tiles (tap: their profile).
 * Both are watched while the screen is visible, so returning from Edit profile shows the
 * changes straight away.
 *
 * Buttons: Edit profile and both camera badges open Edit profile; Add to story opens the camera;
 * the search icon and "Find friends" open Search. The pills and "..." are visual only.
 */
class ProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBackButton(R.id.iv_back)
        val openEdit = View.OnClickListener { openScreen(EditProfileActivity::class.java) }
        binding.llEditProfile.setOnClickListener(openEdit)
        binding.ivCoverCamera.setOnClickListener(openEdit)
        binding.ivAvatarCamera.setOnClickListener(openEdit)
        binding.llAddToStory.setOnClickListener { openScreen(CameraActivity::class.java) }
        binding.ivSearch.setOnClickListener { openScreen(SearchActivity::class.java) }
        binding.tvFindFriends.setOnClickListener { openScreen(SearchActivity::class.java) }

        watch(Db.user(myUid)) { snapshot ->
            snapshot.getValue(User::class.java)?.let { showUser(it) }
        }
        watch(Db.friends(myUid)) { snapshot ->
            showFriends(snapshot.children.mapNotNull { it.key })
        }
    }

    private fun showUser(user: User) {
        Db.remember(user)
        binding.tvTitle.text = user.name
        binding.tvName.text = user.name
        Avatars.bind(binding.tvAvatar, binding.ivAvatarPhoto, user)
        Images.load(binding.ivCover, user.coverId, R.drawable.ph_teal)

        binding.tvBio.text = user.bio
        binding.tvBio.visibility = if (user.bio.isEmpty()) View.GONE else View.VISIBLE

        showDetail(binding.llWork, binding.tvWork, user.work)
        showDetail(binding.llStudy, binding.tvStudy, user.study)
        showDetail(binding.llCity, binding.tvCity, user.city)
        binding.tvJoined.text = Times.joined(this, user.createdAt)
    }

    /** Shows a Details row with [value] in bold, or hides the row when [value] is empty. */
    private fun showDetail(row: LinearLayout, valueView: TextView, value: String) {
        valueView.text = value
        row.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The count, and tiles for the first three friends. */
    private fun showFriends(friendUids: List<String>) {
        binding.tvFriendCount.text = friendUids.size.toString()
        binding.tvFriendsTotal.text =
            if (friendUids.isEmpty()) getString(R.string.profile_no_friends)
            else getString(R.string.friends_count_format, friendUids.size)
        binding.llFriendTiles.visibility = if (friendUids.isEmpty()) View.GONE else View.VISIBLE

        val tiles = listOf(
            Triple(binding.flFriend1, binding.tvFriend1, binding.ivFriend1),
            Triple(binding.flFriend2, binding.tvFriend2, binding.ivFriend2),
            Triple(binding.flFriend3, binding.tvFriend3, binding.ivFriend3),
        )
        tiles.forEachIndexed { index, (frame, initials, photo) ->
            val uid = friendUids.getOrNull(index)
            if (uid == null) {
                frame.visibility = View.INVISIBLE
            } else {
                Db.getUser(uid) { friend -> showTile(frame, initials, photo, friend) }
            }
        }
    }

    private fun showTile(frame: FrameLayout, initials: TextView, photo: ImageView, friend: User?) {
        if (friend == null) {
            frame.visibility = View.INVISIBLE
            return
        }
        frame.visibility = View.VISIBLE
        Avatars.bind(initials, photo, friend)
        frame.contentDescription = getString(R.string.cd_open_friend_profile, friend.name)
        frame.setOnClickListener { openProfile(friend.uid) }
    }
}