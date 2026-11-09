package com.rameen.i230806.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rameen.i230806.R
import com.rameen.i230806.data.ChatSummary
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityChatsBinding
import com.rameen.i230806.databinding.ItemChatNoteBinding
import com.rameen.i230806.databinding.ItemChatRowBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.home.SearchActivity
import com.rameen.i230806.ui.social.FriendsActivity
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Times

/**
 * 20 Chats: every conversation, newest first, live from userChats/{me}.
 *  - Each row: avatar (green dot when they're online), name, last message ("You: ..." for
 *    mine) and time. Unread chats have a bold name and the teal dot.
 *  - Notes row: friends who are online right now; tap one to chat.
 *  - The search field filters the rows by name. Compose opens Search; People opens Friends.
 *  - Android 13+: asks once for permission to show notifications (new messages and calls).
 */
class ChatsActivity : BaseActivity() {

    private lateinit var binding: ActivityChatsBinding

    /** Every conversation (newest first) and the people in them. */
    private val chats = mutableListOf<ChatSummary>()
    private val people = HashMap<String, User>()

    /** The rows currently shown (after the search filter). */
    private val shown = mutableListOf<ChatSummary>()
    private val adapter = ChatAdapter()

    private var onlineUids: Set<String> = emptySet()
    private var friendUids: Set<String> = emptySet()

    /** Android 13+ notification permission; the answer doesn't need handling here. */
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ivBack.setOnClickListener { finish() }
        binding.flCompose.setOnClickListener { openScreen(SearchActivity::class.java) }
        binding.llNavPeople.setOnClickListener {
            openScreen(FriendsActivity::class.java, Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }

        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = adapter
        binding.rvChats.isNestedScrollingEnabled = false   // the page's ScrollView scrolls instead
        binding.etSearch.doAfterTextChanged { applyFilter() }

        // My conversations, oldest first from Firebase, so reversed for the screen.
        watch(Db.userChats(myUid).orderByChild("timestamp")) { snapshot ->
            val list = snapshot.children
                .mapNotNull { it.getValue(ChatSummary::class.java)?.apply { chatId = it.key ?: chatId } }
                .filter { it.otherUid.isNotEmpty() }
                .reversed()
            loadPeople(list.map { it.otherUid }) {
                chats.clear()
                chats.addAll(list)
                applyFilter()
            }
        }

        // Who is online right now (green dots and the notes row).
        watch(Db.users().orderByChild("online").equalTo(true)) { snapshot ->
            onlineUids = snapshot.children.mapNotNull { it.key }.toSet()
            adapter.notifyDataSetChanged()
            showNotes()
        }
        watch(Db.friends(myUid)) { snapshot ->
            friendUids = snapshot.children.mapNotNull { it.key }.toSet()
            showNotes()
        }

        askForNotificationPermission()
    }

    /** Android 13+ only: notifications are off until the user allows them. */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Loads the profiles of [uids] (cached by Db.getUser), then calls [onDone]. */
    private fun loadPeople(uids: List<String>, onDone: () -> Unit) {
        val missing = uids.distinct()
        if (missing.isEmpty()) {
            onDone()
            return
        }
        var waiting = missing.size
        for (uid in missing) {
            Db.getUser(uid) { user ->
                if (user != null) people[uid] = user
                waiting--
                if (waiting == 0 && !isDestroyed) onDone()
            }
        }
    }

    /** Shows the chats whose name contains the search text (all of them when it's empty). */
    private fun applyFilter() {
        val query = binding.etSearch.text.toString().trim().lowercase()
        shown.clear()
        shown.addAll(
            if (query.isEmpty()) chats
            else chats.filter { people[it.otherUid]?.name.orEmpty().lowercase().contains(query) }
        )
        adapter.notifyDataSetChanged()

        binding.tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.setText(if (chats.isEmpty()) R.string.chats_empty else R.string.chats_no_results)
    }

    /** Notes row: my friends who are online, after "Your note". */
    private fun showNotes() {
        val online = friendUids.filter { it in onlineUids }
        loadPeople(online) {
            binding.llNotes.removeAllViews()
            for (uid in online) {
                val user = people[uid] ?: continue
                val note = ItemChatNoteBinding.inflate(layoutInflater, binding.llNotes, false)
                Avatars.bind(note.tvAvatar, note.ivAvatarPhoto, user)
                note.tvName.text = user.name.substringBefore(' ')
                note.root.contentDescription = getString(R.string.cd_open_chat, user.name)
                note.root.setOnClickListener { openChat(uid) }
                binding.llNotes.addView(note.root)
            }
        }
    }

    private fun openChat(otherUid: String) {
        openScreen(ChatActivity::class.java) { putExtra(Extra.UID, otherUid) }
    }

    // ---------------------------------------------------------------------------------------
    // Rows
    // ---------------------------------------------------------------------------------------

    private inner class Holder(val row: ItemChatRowBinding) : RecyclerView.ViewHolder(row.root)

    private inner class ChatAdapter : RecyclerView.Adapter<Holder>() {
        private val bold: Typeface? by lazy { ResourcesCompat.getFont(this@ChatsActivity, R.font.figtree_bold) }
        private val medium: Typeface? by lazy { ResourcesCompat.getFont(this@ChatsActivity, R.font.figtree_medium) }
        private val semibold: Typeface? by lazy { ResourcesCompat.getFont(this@ChatsActivity, R.font.figtree_semibold) }
        private val regular: Typeface? by lazy { ResourcesCompat.getFont(this@ChatsActivity, R.font.figtree_regular) }

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemChatRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val chat = shown[position]
            val row = holder.row
            val user = people[chat.otherUid]
            val unread = chat.unread > 0

            Avatars.bind(row.tvAvatar, row.ivAvatarPhoto, user)
            row.vOnline.visibility = if (chat.otherUid in onlineUids) View.VISIBLE else View.GONE
            row.tvName.text = user?.name.orEmpty()
            row.tvMessage.text =
                if (chat.lastSenderId == myUid) getString(R.string.chats_you_prefix, chat.lastMessage)
                else chat.lastMessage
            row.tvTime.text = getString(R.string.chats_time_sep, Times.chatList(this@ChatsActivity, chat.timestamp))

            // Unread: bold name, dark SemiBold message and time, teal dot (styles .Unread in XML).
            val dark = ContextCompat.getColor(this@ChatsActivity, R.color.text_dark)
            val grey = ContextCompat.getColor(this@ChatsActivity, R.color.text_grey)
            row.tvName.typeface = if (unread) bold else medium
            row.tvMessage.typeface = if (unread) semibold else regular
            row.tvTime.typeface = if (unread) semibold else regular
            row.tvMessage.setTextColor(if (unread) dark else grey)
            row.tvTime.setTextColor(if (unread) dark else grey)
            row.vUnread.visibility = if (unread) View.VISIBLE else View.GONE

            row.root.contentDescription = getString(R.string.cd_open_chat, user?.name.orEmpty())
            row.root.setOnClickListener { openChat(chat.otherUid) }
        }
    }
}