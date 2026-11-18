package com.rameen.i230806.ui.profile

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.rameen.i230806.R
import com.rameen.i230806.data.Cached
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.FriendRepo
import com.rameen.i230806.data.Json
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityOtherProfileBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.chat.ChatActivity
import com.rameen.i230806.ui.home.SearchActivity
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Images
import com.rameen.i230806.util.Times
import org.json.JSONObject

/**
 * 17 Other profile: someone else's profile (their id comes in Extra.UID).
 *
 * One request, users/profile.php?id=..., answers everything on the screen (Assignment 3):
 *  - user                         name, pictures, bio, details
 *  - friend_count, mutual_count   "1,204 friends · 38 mutual", and the first mutual friends
 *  - status, close                which friend button to show (FriendRepo.Status)
 *  - photos                       their three newest post pictures
 * It is loaded every time the screen shows; the saved copy (Cached) shows first and offline.
 *
 * Friend button: Add friend -> Requested (tap again to cancel) -> they Confirm -> Friends.
 * If they asked me, it shows Confirm. "..." has Close friends, Unfriend and Delete request.
 * A tap changes the button at once (also offline: the action waits in the queue), then the
 * server's answer confirms it. Message opens the chat with this person.
 */
class OtherProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityOtherProfileBinding
    private lateinit var uid: String

    // What the friend button depends on (from the server, or changed by a tap).
    private var status = FriendRepo.Status.NONE
    private var isCloseFriend = false

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

        // Until the profile arrives: whatever we already know about this person.
        Json.cachedUser(uid)?.let { showUser(it) }
        showFriendButton()
    }

    override fun onStart() {
        super.onStart()
        load()
    }

    /** Fills the whole screen from users/profile.php. */
    private fun load() {
        Cached.get("users/profile.php", mapOf("id" to uid)) { json ->
            if (isDestroyed) return@get
            showUser(Json.saveUser(json.getJSONObject("user")))
            showCountsAndMutual(
                json.optInt("friend_count"),
                json.optInt("mutual_count"),
                Json.objects(json.optJSONArray("mutual")).map { Json.user(it) },
            )
            showPhotos(Json.strings(json.optJSONArray("photos")))
            showRelation(json)
        }
    }

    /** status + close from the server's answer. */
    private fun showRelation(json: JSONObject) {
        status = FriendRepo.statusOf(json.optString("status"))
        isCloseFriend = json.optBoolean("close")
        showFriendButton()
    }

    // ---------------------------------------------------------------------------------------
    // Profile
    // ---------------------------------------------------------------------------------------

    private fun showUser(user: User) {
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
    private fun showCountsAndMutual(friendCount: Int, mutualCount: Int, firstMutual: List<User>) {
        binding.tvCounts.text = html(getString(R.string.other_counts_format, friendCount, mutualCount))

        binding.llMutual.visibility = if (mutualCount == 0) View.GONE else View.VISIBLE
        if (mutualCount == 0 || firstMutual.isEmpty()) return

        val circles = listOf(
            binding.flMutual1 to binding.tvMutual1,
            binding.flMutual2 to binding.tvMutual2,
            binding.flMutual3 to binding.tvMutual3,
        )
        circles.forEachIndexed { index, (frame, initials) ->
            val friend = firstMutual.getOrNull(index)
            frame.visibility = if (friend != null) View.VISIBLE else View.GONE
            if (friend != null) Avatars.bind(initials, null, friend)
        }

        // Names of the first two mutual friends.
        val first = safe(firstMutual[0])
        val second = firstMutual.getOrNull(1)
        binding.tvMutual.text = html(
            when {
                second == null -> getString(R.string.mutual_one, first)
                mutualCount == 2 -> getString(R.string.mutual_two, first, safe(second))
                else -> getString(R.string.mutual_many, first, safe(second), mutualCount - 2)
            }
        )
    }

    /** A name made safe to put inside HTML (a name like "<b>" stays plain text). */
    private fun safe(user: User?): String = TextUtils.htmlEncode(user?.name.orEmpty())

    private fun html(text: String) = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)

    /** Up to three photos; the section is hidden when they haven't posted any. */
    private fun showPhotos(urls: List<String>) {
        val visibility = if (urls.isEmpty()) View.GONE else View.VISIBLE
        binding.llPhotosTitle.visibility = visibility
        binding.llPhotos.visibility = visibility
        listOf(binding.ivPhoto1, binding.ivPhoto2, binding.ivPhoto3).forEachIndexed { index, tile ->
            val url = urls.getOrNull(index)
            tile.visibility = if (url == null) View.INVISIBLE else View.VISIBLE
            Images.load(tile, url)
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

    /**
     * Shows the expected result at once ([newStatus], [newClose]) and sends [action]. The
     * server's answer then sets the real state, and the counts are reloaded.
     */
    private fun change(action: String, newStatus: FriendRepo.Status, newClose: Boolean, @StringRes message: Int) {
        status = newStatus
        isCloseFriend = newClose
        showFriendButton()
        toast(message)
        FriendRepo.act(this, action, uid) { answer ->
            if (answer == null || isDestroyed) return@act
            showRelation(answer)
            load()
        }
    }

    private fun onFriendButton() {
        when (status) {
            FriendRepo.Status.NONE ->
                change(FriendRepo.REQUEST, FriendRepo.Status.REQUEST_SENT, false, R.string.friend_request_sent)
            FriendRepo.Status.REQUEST_SENT ->
                change(FriendRepo.CANCEL, FriendRepo.Status.NONE, false, R.string.friend_request_cancelled)
            FriendRepo.Status.REQUEST_RECEIVED ->
                change(FriendRepo.ACCEPT, FriendRepo.Status.FRIENDS, false, R.string.friend_now_friends)
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
                MENU_CLOSE ->
                    if (isCloseFriend) change(FriendRepo.UNCLOSE, status, false, R.string.close_friend_removed)
                    else change(FriendRepo.CLOSE, status, true, R.string.close_friend_added)
                MENU_UNFRIEND ->
                    change(FriendRepo.UNFRIEND, FriendRepo.Status.NONE, false, R.string.unfriended)
                MENU_DELETE_REQUEST ->
                    change(FriendRepo.DECLINE, FriendRepo.Status.NONE, false, R.string.request_deleted)
                MENU_CANCEL_REQUEST ->
                    change(FriendRepo.CANCEL, FriendRepo.Status.NONE, false, R.string.friend_request_cancelled)
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
