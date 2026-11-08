package com.rameen.i230806.ui.profile

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.rameen.i230806.R
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.FriendRepo
import com.rameen.i230806.data.Post
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityOtherProfileBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.chat.ChatActivity
import com.rameen.i230806.ui.home.SearchActivity
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Images
import com.rameen.i230806.util.Times

/**
 * 17 Other profile: someone else's profile (their uid comes in Extra.UID).
 *
 * Live data (watched while the screen is visible):
 *  - users/{uid}                    name, pictures, bio, details
 *  - friends/{uid} + friends/{me}   their friend count, and the mutual friends (in both lists)
 *  - friends/{me}/{uid}, friendRequests both ways, closeFriends/{me}/{uid}
 *                                   which friend button to show (FriendRepo.Status)
 *  - posts by this person           the three newest photos
 *
 * Friend button: Add friend -> Requested (tap again to cancel) -> they Confirm -> Friends.
 * If they asked me, it shows Confirm. "..." has Close friends, Unfriend and Delete request.
 * Message opens the chat with this person.
 */
class OtherProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityOtherProfileBinding
    private lateinit var uid: String

    // The facts the friend button depends on, each kept up to date by its own watch.
    private var areFriends = false
    private var iAsked = false
    private var theyAsked = false
    private var isCloseFriend = false
    private val status get() = FriendRepo.statusOf(areFriends, iAsked, theyAsked)

    private var theirFriends: Set<String> = emptySet()
    private var myFriends: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOtherProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        uid = intent.getStringExtra(Extra.UID).orEmpty()
        if (uid.isEmpty()) {
            finish()
            return
        }

        setupBackButton(R.id.iv_back)
        binding.ivSearch.setOnClickListener { openScreen(SearchActivity::class.java) }
        binding.llAddFriend.setOnClickListener { onFriendButton() }
        binding.ivMore.setOnClickListener { showMoreMenu(it) }
        binding.llMessage.setOnClickListener {
            openScreen(ChatActivity::class.java) { putExtra(Extra.UID, uid) }
        }

        // Cuts each photo to its tile's rounded corners (bg_gallery_thumb);
        // XML only has this attribute from Android 12.
        binding.ivPhoto1.clipToOutline = true
        binding.ivPhoto2.clipToOutline = true
        binding.ivPhoto3.clipToOutline = true

        watch(Db.user(uid)) { snapshot ->
            snapshot.getValue(User::class.java)?.let { showUser(it) }
        }

        // Friend lists: count and mutual friends.
        watch(Db.friends(uid)) { snapshot ->
            theirFriends = snapshot.children.mapNotNull { it.key }.toSet()
            showCountsAndMutual()
        }
        watch(Db.friends(myUid)) { snapshot ->
            myFriends = snapshot.children.mapNotNull { it.key }.toSet()
            areFriends = uid in myFriends
            showCountsAndMutual()
            showFriendButton()
        }

        // Requests both ways, and close friends.
        watch(Db.friendRequests(uid).child(myUid)) { snapshot ->
            iAsked = snapshot.exists()
            showFriendButton()
        }
        watch(Db.friendRequests(myUid).child(uid)) { snapshot ->
            theyAsked = snapshot.exists()
            showFriendButton()
        }
        watch(Db.closeFriends(myUid).child(uid)) { snapshot ->
            isCloseFriend = snapshot.exists()
            showFriendButton()
        }

        // Their posts, newest first: the first three pictures fill the Photos tiles.
        watch(Db.posts().orderByChild("authorId").equalTo(uid)) { snapshot ->
            val pictures = snapshot.children
                .mapNotNull { it.getValue(Post::class.java) }
                .sortedByDescending { it.timestamp }
                .flatMap { it.imageIds }
            showPhotos(pictures)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Profile
    // ---------------------------------------------------------------------------------------

    private fun showUser(user: User) {
        Db.remember(user)
        binding.tvTitle.text = user.name
        binding.tvName.text = user.name
        Avatars.bind(binding.tvAvatar, binding.ivAvatarPhoto, user)
        Images.load(binding.ivCover, user.coverId, R.drawable.ph_night)

        binding.tvBio.text = user.bio
        binding.tvBio.visibility = if (user.bio.isEmpty()) View.GONE else View.VISIBLE

        showDetail(binding.llWork, binding.tvWork, user.work)
        showDetail(binding.llStudy, binding.tvStudy, user.study)
        showDetail(binding.llCity, binding.tvCity, user.city)
        binding.tvJoined.text = Times.joined(this, user.createdAt)
    }

    private fun showDetail(row: LinearLayout, valueView: TextView, value: String) {
        valueView.text = value
        row.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }

    /** "1,204 friends · 38 mutual" and the "Friends with ..." row. */
    private fun showCountsAndMutual() {
        val mutual = theirFriends.intersect(myFriends).toList()
        binding.tvCounts.text = html(getString(R.string.other_counts_format, theirFriends.size, mutual.size))

        binding.llMutual.visibility = if (mutual.isEmpty()) View.GONE else View.VISIBLE
        if (mutual.isEmpty()) return

        val circles = listOf(
            binding.flMutual1 to binding.tvMutual1,
            binding.flMutual2 to binding.tvMutual2,
            binding.flMutual3 to binding.tvMutual3,
        )
        circles.forEachIndexed { index, (frame, initials) ->
            frame.visibility = if (index < mutual.size) View.VISIBLE else View.GONE
            if (index < mutual.size) Db.getUser(mutual[index]) { Avatars.bind(initials, null, it) }
        }

        // Names of the first two mutual friends, then the text once both are loaded.
        Db.getUser(mutual[0]) { first ->
            val second = mutual.getOrNull(1)
            if (second == null) {
                binding.tvMutual.text = html(getString(R.string.mutual_one, safe(first)))
            } else {
                Db.getUser(second) { other ->
                    binding.tvMutual.text = html(
                        if (mutual.size == 2) getString(R.string.mutual_two, safe(first), safe(other))
                        else getString(R.string.mutual_many, safe(first), safe(other), mutual.size - 2)
                    )
                }
            }
        }
    }

    /** A name made safe to put inside HTML (a name like "<b>" stays plain text). */
    private fun safe(user: User?): String = TextUtils.htmlEncode(user?.name.orEmpty())

    private fun html(text: String) = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)

    /** Up to three photos; the section is hidden when they haven't posted any. */
    private fun showPhotos(mediaIds: List<String>) {
        val visibility = if (mediaIds.isEmpty()) View.GONE else View.VISIBLE
        binding.llPhotosTitle.visibility = visibility
        binding.llPhotos.visibility = visibility
        listOf(binding.ivPhoto1, binding.ivPhoto2, binding.ivPhoto3).forEachIndexed { index, tile ->
            val mediaId = mediaIds.getOrNull(index)
            tile.visibility = if (mediaId == null) View.INVISIBLE else View.VISIBLE
            Images.load(tile, mediaId)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Friend button and "..." menu
    // ---------------------------------------------------------------------------------------

    /** Teal button for an action (Add friend, Confirm), grey for a state (Requested, Friends). */
    private fun showFriendButton() {
        val (icon, label, teal) = when (status) {
            FriendRepo.Status.NONE -> Triple(R.drawable.ic_add_person, R.string.friend_add, true)
            FriendRepo.Status.REQUEST_SENT -> Triple(R.drawable.ic_clock, R.string.friend_requested, false)
            FriendRepo.Status.REQUEST_RECEIVED -> Triple(R.drawable.ic_add_person, R.string.friend_confirm, true)
            FriendRepo.Status.FRIENDS ->
                if (isCloseFriend) Triple(R.drawable.ic_star, R.string.friend_close, false)
                else Triple(R.drawable.ic_person, R.string.friend_friends, false)
        }
        val textColor = ContextCompat.getColor(this, if (teal) R.color.white else R.color.text_dark)
        binding.llAddFriend.setBackgroundResource(
            if (teal) R.drawable.bg_touch_small_37 else R.drawable.bg_touch_grey_37
        )
        binding.ivFriendIcon.setImageResource(icon)
        binding.ivFriendIcon.imageTintList = ColorStateList.valueOf(textColor)
        binding.tvFriendLabel.setText(label)
        binding.tvFriendLabel.setTextColor(textColor)
    }

    private fun onFriendButton() {
        when (status) {
            FriendRepo.Status.NONE -> {
                FriendRepo.sendRequest(uid)
                toast(R.string.friend_request_sent)
            }
            FriendRepo.Status.REQUEST_SENT -> {
                FriendRepo.cancelRequest(uid)
                toast(R.string.friend_request_cancelled)
            }
            FriendRepo.Status.REQUEST_RECEIVED -> {
                FriendRepo.accept(uid)
                toast(R.string.friend_now_friends)
            }
            // Already friends: the same choices as "...".
            FriendRepo.Status.FRIENDS -> showMoreMenu(binding.llAddFriend)
        }
    }

    /** A small pop-up list under [anchor] with the choices that fit the current status. */
    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        when (status) {
            FriendRepo.Status.FRIENDS -> {
                popup.menu.add(0, MENU_CLOSE, 0,
                    if (isCloseFriend) R.string.menu_remove_close_friend else R.string.menu_add_close_friend)
                popup.menu.add(0, MENU_UNFRIEND, 1, R.string.menu_unfriend)
            }
            FriendRepo.Status.REQUEST_RECEIVED -> popup.menu.add(0, MENU_DELETE_REQUEST, 0, R.string.menu_delete_request)
            FriendRepo.Status.REQUEST_SENT -> popup.menu.add(0, MENU_CANCEL_REQUEST, 0, R.string.menu_cancel_request)
            FriendRepo.Status.NONE -> return   // nothing to offer yet
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_CLOSE -> {
                    FriendRepo.setCloseFriend(uid, !isCloseFriend)
                    toast(if (isCloseFriend) R.string.close_friend_removed else R.string.close_friend_added)
                }
                MENU_UNFRIEND -> {
                    FriendRepo.unfriend(uid)
                    toast(R.string.unfriended)
                }
                MENU_DELETE_REQUEST -> {
                    FriendRepo.decline(uid)
                    toast(R.string.request_deleted)
                }
                MENU_CANCEL_REQUEST -> {
                    FriendRepo.cancelRequest(uid)
                    toast(R.string.friend_request_cancelled)
                }
            }
            true
        }
        popup.show()
    }

    private companion object {
        const val MENU_CLOSE = 1
        const val MENU_UNFRIEND = 2
        const val MENU_DELETE_REQUEST = 3
        const val MENU_CANCEL_REQUEST = 4
    }
}