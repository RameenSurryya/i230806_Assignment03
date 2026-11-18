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
import com.rameen.i230806.data.Api
import com.rameen.i230806.data.AppNotification
import com.rameen.i230806.data.Cached
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.FriendRepo
import com.rameen.i230806.data.Json
import com.rameen.i230806.data.NotifType
import com.rameen.i230806.data.Reaction
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityNotificationsBinding
import com.rameen.i230806.databinding.ItemNotificationBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.home.CommentsActivity
import com.rameen.i230806.ui.home.YourStoryActivity
import com.rameen.i230806.ui.profile.openProfile
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Times
import org.json.JSONObject

/**
 * 18 Notifications, from our server (notifications/list.php, Assignment 3).
 *
 * The server adds the rows itself when something happens (friend requests and accepted
 * requests now; reactions, comments and story reactions in steps 5 and 6). This screen loads
 * the newest 30 every time it shows (Cached: the saved copy first, so it also works offline):
 *  - New: not read yet, on the light teal band, time in bold teal.
 *  - Earlier: already seen.
 * When you leave the screen they are all marked read (notifications/read.php), which also
 * clears the red number on the bell tab (badges.php).
 *
 * Tapping a row: friend rows open that person's profile, post rows open the comments of that
 * post, story rows open your story. A friend request that is still waiting has Confirm /
 * Delete. "..." removes the row. "See previous notifications" loads 30 more.
 */
class NotificationsActivity : BaseActivity() {

    private lateinit var binding: ActivityNotificationsBinding

    /** One row: the notification, who did it, and whether their friend request still waits. */
    private data class Row(val item: AppNotification, val from: User?, var waiting: Boolean)

    private val newRows = mutableListOf<Row>()
    private val earlierRows = mutableListOf<Row>()
    private val newAdapter = NotifAdapter(newRows, isNew = true)
    private val earlierAdapter = NotifAdapter(earlierRows, isNew = false)

    private var limit = PAGE_SIZE

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

        binding.btnSeePrevious.setOnClickListener {
            limit += PAGE_SIZE
            load()
        }
    }

    override fun onStart() {
        super.onStart()
        load()
    }

    /** The newest [limit] notifications, split into New and Earlier. */
    private fun load() {
        Cached.get("notifications/list.php", mapOf("limit" to limit)) { json ->
            if (isDestroyed) return@get
            val all = Json.objects(json.optJSONArray("notifications")).map { rowOf(it) }
            newRows.clear()
            newRows.addAll(all.filter { !it.item.read })
            earlierRows.clear()
            earlierRows.addAll(all.filter { it.item.read })
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

    /** One notification from the server's JSON. */
    private fun rowOf(json: JSONObject): Row {
        val item = AppNotification(
            id = json.optLong("id").toString(),
            type = Json.str(json, "type"),
            fromUid = json.optLong("from_id").toString(),
            postId = if (json.isNull("post_id")) "" else json.optLong("post_id").toString(),
            text = Json.str(json, "text"),
            timestamp = json.optLong("created_at"),
            read = json.optBoolean("read"),
        )
        val from = json.optJSONObject("from")?.let { Json.user(it) }
        return Row(item, from, json.optBoolean("waiting"))
    }

    private fun setVisible(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Leaving the screen: everything shown has now been seen. One request for all of them. */
    override fun onStop() {
        if (newRows.isNotEmpty()) {
            Api.post("notifications/read.php", JSONObject()) { refreshBadges() }
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
            Api.post("notifications/delete.php", JSONObject().put("id", item.id.toLong())) { result ->
                if (isDestroyed) return@post
                if (result.ok) {
                    toast(R.string.notif_removed)
                    load()
                } else {
                    toast(result.error ?: getString(R.string.error_generic))
                }
            }
            true
        }
        popup.show()
    }

    /** Confirm / Delete: the buttons go away at once; the action is sent (or queued offline). */
    private fun answerRequest(entry: Row, action: String) {
        entry.waiting = false
        newAdapter.notifyDataSetChanged()
        earlierAdapter.notifyDataSetChanged()
        FriendRepo.act(this, action, entry.item.fromUid)
    }

    // ---------------------------------------------------------------------------------------
    // Rows (item_notification.xml)
    // ---------------------------------------------------------------------------------------

    private inner class NotifHolder(val row: ItemNotificationBinding) : RecyclerView.ViewHolder(row.root)

    private inner class NotifAdapter(
        private val rows: List<Row>,
        private val isNew: Boolean,
    ) : RecyclerView.Adapter<NotifHolder>() {

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            NotifHolder(ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: NotifHolder, position: Int) {
            val entry = rows[position]
            val item = entry.item
            val row = holder.row

            // Name and avatar of the person who did it (sent with the notification).
            row.tvText.text = textOf(item, entry.from?.name ?: getString(R.string.notif_someone))
            Avatars.bind(row.tvAvatar, row.ivAvatarPhoto, entry.from)
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
            setVisible(row.llRequestButtons, entry.waiting)
            row.btnConfirm.setOnClickListener {
                answerRequest(entry, FriendRepo.ACCEPT)
                toast(R.string.friends_request_confirmed)
            }
            row.btnDelete.setOnClickListener {
                answerRequest(entry, FriendRepo.DECLINE)
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
