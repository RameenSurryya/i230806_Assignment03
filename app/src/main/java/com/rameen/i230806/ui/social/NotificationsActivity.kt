package com.rameen.i230806.ui.social

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rameen.i230806.R
import com.rameen.i230806.data.AppNotification
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.FriendRepo
import com.rameen.i230806.data.NotifType
import com.rameen.i230806.data.Reaction
import com.rameen.i230806.databinding.ActivityNotificationsBinding
import com.rameen.i230806.databinding.ItemNotificationBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.home.CommentsActivity
import com.rameen.i230806.ui.home.YourStoryActivity
import com.rameen.i230806.ui.profile.openProfile
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Times

/**
 * 18 Notifications, live from notifications/{my uid}.
 *
 * Other screens add rows with Db.notify(...) (friend requests now; reactions, comments and
 * story reactions in steps 5 and 6). This screen shows the newest 30:
 *  - New: not read yet, on the light teal band, time in bold teal.
 *  - Earlier: already seen.
 * When you leave the screen they are all marked read = true, which also clears the red
 * number on the bell tab (BaseActivity counts read == false).
 *
 * Tapping a row: friend rows open that person's profile, post rows open the comments of that
 * post, story rows open your story. A friend request that is still waiting has Confirm /
 * Delete. "..." removes the row. "See previous notifications" loads 30 more.
 */
class NotificationsActivity : BaseActivity() {

    private lateinit var binding: ActivityNotificationsBinding

    private val newRows = mutableListOf<AppNotification>()
    private val earlierRows = mutableListOf<AppNotification>()
    private val newAdapter = NotifAdapter(newRows, isNew = true)
    private val earlierAdapter = NotifAdapter(earlierRows, isNew = false)

    /** People whose friend request to me is still waiting (for Confirm / Delete). */
    private var waitingRequests: Set<String> = emptySet()

    private var limit = PAGE_SIZE
    private var listWatch: Watch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTopBar(R.string.title_notifications)
        setupTopTabs(Tab.NOTIFICATIONS)

        for ((list, adapter) in listOf(binding.rvNew to newAdapter, binding.rvEarlier to earlierAdapter)) {
            list.layoutManager = LinearLayoutManager(this)
            list.isNestedScrollingEnabled = false   // the page's ScrollView does the scrolling
            list.adapter = adapter
        }

        watch(Db.friendRequests(myUid)) { snapshot ->
            waitingRequests = snapshot.children.mapNotNull { it.key }.toSet()
            newAdapter.notifyDataSetChanged()
            earlierAdapter.notifyDataSetChanged()
        }
        watchList()

        binding.btnSeePrevious.setOnClickListener {
            limit += PAGE_SIZE
            watchList()
        }
    }

    /** (Re)starts listening to the newest [limit] notifications. */
    private fun watchList() {
        listWatch?.let { stopWatching(it) }
        listWatch = watch(Db.notifications(myUid).orderByChild("timestamp").limitToLast(limit)) { snapshot ->
            val all = snapshot.children
                .mapNotNull { it.getValue(AppNotification::class.java)?.apply { id = it.key ?: id } }
                .sortedByDescending { it.timestamp }
            newRows.clear()
            newRows.addAll(all.filter { !it.read })
            earlierRows.clear()
            earlierRows.addAll(all.filter { it.read })
            newAdapter.notifyDataSetChanged()
            earlierAdapter.notifyDataSetChanged()

            setVisible(binding.tvNewTitle, newRows.isNotEmpty())
            setVisible(binding.rvNew, newRows.isNotEmpty())
            setVisible(binding.tvEarlierTitle, earlierRows.isNotEmpty())
            setVisible(binding.rvEarlier, earlierRows.isNotEmpty())
            setVisible(binding.tvEmpty, all.isEmpty())
            // A full page means there may be older ones.
            setVisible(binding.btnSeePrevious, all.size >= limit)
        }
    }

    private fun setVisible(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Leaving the screen: everything shown has now been seen. One write for all of them. */
    override fun onStop() {
        if (newRows.isNotEmpty()) {
            Db.notifications(myUid).updateChildren(newRows.associate { "${it.id}/read" to true })
        }
        super.onStop()
    }

    // ---------------------------------------------------------------------------------------
    // What each type looks like and does
    // ---------------------------------------------------------------------------------------

    /** The sentence, with the name in bold. User text is HTML-escaped so it stays plain text. */
    private fun textOf(item: AppNotification, name: String): CharSequence {
        val who = TextUtils.htmlEncode(name)
        val quote = TextUtils.htmlEncode(item.text.take(QUOTE_LENGTH))
        val html = when (item.type) {
            NotifType.FRIEND_REQUEST -> getString(R.string.notif_text_friend_request, who)
            NotifType.FRIEND_ACCEPTED -> getString(R.string.notif_text_friend_accepted, who)
            NotifType.POST_REACTION -> getString(R.string.notif_text_post_reaction, who)
            NotifType.COMMENT -> getString(R.string.notif_text_comment, who, quote)
            NotifType.REPLY -> getString(R.string.notif_text_reply, who, quote)
            NotifType.COMMENT_LIKE -> getString(R.string.notif_text_comment_like, who, quote)
            NotifType.STORY_REACTION -> getString(R.string.notif_text_story_reaction, who)
            else -> getString(R.string.notif_text_other, who)
        }
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    /** The badge on the avatar: a reaction for reactions, a type disc for the rest. */
    private fun showBadge(row: ItemNotificationBinding, item: AppNotification) {
        val reaction = when (item.type) {
            NotifType.POST_REACTION, NotifType.STORY_REACTION -> Reaction.of(item.text) ?: Reaction.LIKE
            NotifType.COMMENT_LIKE -> Reaction.LIKE
            else -> null
        }
        val badge = when (item.type) {
            NotifType.FRIEND_REQUEST, NotifType.FRIEND_ACCEPTED -> R.drawable.badge_friend_request
            NotifType.COMMENT, NotifType.REPLY -> R.drawable.badge_comment
            else -> 0
        }
        setVisible(row.flReaction, reaction != null)
        reaction?.let { row.ivReaction.setImageResource(it.icon) }
        setVisible(row.flBadge, badge != 0)
        if (badge != 0) row.ivBadge.setImageResource(badge)
    }

    private fun onRowTapped(item: AppNotification) {
        when (item.type) {
            NotifType.POST_REACTION, NotifType.COMMENT, NotifType.REPLY, NotifType.COMMENT_LIKE ->
                openScreen(CommentsActivity::class.java) { putExtra(Extra.POST_ID, item.postId) }
            NotifType.STORY_REACTION -> openScreen(YourStoryActivity::class.java)
            else -> openProfile(item.fromUid)
        }
    }

    private fun showRowMenu(anchor: View, item: AppNotification) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(R.string.notif_remove)
        popup.setOnMenuItemClickListener {
            Db.notifications(myUid).child(item.id).removeValue()
            toast(R.string.notif_removed)
            true
        }
        popup.show()
    }

    // ---------------------------------------------------------------------------------------
    // Rows (item_notification.xml)
    // ---------------------------------------------------------------------------------------

    private inner class NotifHolder(val row: ItemNotificationBinding) : RecyclerView.ViewHolder(row.root)

    private inner class NotifAdapter(
        private val rows: List<AppNotification>,
        private val isNew: Boolean,
    ) : RecyclerView.Adapter<NotifHolder>() {

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            NotifHolder(ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: NotifHolder, position: Int) {
            val item = rows[position]
            val row = holder.row
            row.root.tag = item.id

            // Name and avatar of the person who did it.
            row.tvText.text = textOf(item, getString(R.string.notif_someone))
            Avatars.bind(row.tvAvatar, row.ivAvatarPhoto, null)
            if (item.fromUid.isNotEmpty()) Db.getUser(item.fromUid) { user ->
                if (row.root.tag != item.id) return@getUser
                Avatars.bind(row.tvAvatar, row.ivAvatarPhoto, user)
                if (user != null) row.tvText.text = textOf(item, user.name)
            }
            showBadge(row, item)

            // Time: bold teal while new (Kinnect.Text.Caption.New), grey once seen.
            row.tvTime.text = Times.ago(this@NotificationsActivity, item.timestamp)
            row.tvTime.setTextColor(
                ContextCompat.getColor(this@NotificationsActivity, if (isNew) R.color.teal else R.color.text_grey)
            )
            row.tvTime.typeface = ResourcesCompat.getFont(
                this@NotificationsActivity, if (isNew) R.font.figtree_bold else R.font.figtree_regular
            )

            // Confirm / Delete only while their request is still waiting.
            val waiting = item.type == NotifType.FRIEND_REQUEST && item.fromUid in waitingRequests
            setVisible(row.llRequestButtons, waiting)
            row.btnConfirm.setOnClickListener {
                FriendRepo.accept(item.fromUid)
                toast(R.string.friends_request_confirmed)
            }
            row.btnDelete.setOnClickListener {
                FriendRepo.decline(item.fromUid)
                toast(R.string.request_deleted)
            }

            row.root.setOnClickListener { onRowTapped(item) }
            row.ivMore.setOnClickListener { showRowMenu(it, item) }
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val QUOTE_LENGTH = 60
    }
}