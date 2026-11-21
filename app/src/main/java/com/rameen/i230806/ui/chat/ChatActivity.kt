package com.rameen.i230806.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rameen.i230806.R
import com.rameen.i230806.data.ChatRepo
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.Message
import com.rameen.i230806.data.MsgType
import com.rameen.i230806.data.PickMode
import com.rameen.i230806.data.Outbox
import com.rameen.i230806.data.PostRepo
import com.rameen.i230806.data.Reaction
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityChatBinding
import com.rameen.i230806.databinding.ItemChatIntroBinding
import com.rameen.i230806.databinding.ItemMessageBinding
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.ui.home.CameraActivity
import com.rameen.i230806.ui.home.CommentsActivity
import com.rameen.i230806.ui.home.PhotoPickerActivity
import com.rameen.i230806.ui.profile.openProfile
import com.rameen.i230806.util.Avatars
import com.rameen.i230806.util.Images
import com.rameen.i230806.util.Times

/**
 * 21 Chat with one person (Extra.UID, or Extra.CHAT_ID when opened from a notification).
 *
 *  - Assignment 3: messages come from SQLite at once (works offline) and from
 *    chat/messages.php every 2 seconds while the screen shows (only what changed since the
 *    last answer). Bubbles from the same person sent close together form a group: joined
 *    corners, their avatar only beside the last one.
 *  - Send text with the paper plane, or a thumbs-up when the field is empty. Camera sends a
 *    photo; the gallery button offers Photo, Video or File. Offline, messages show
 *    "Sending..." and go out when the internet is back. While I type, the other phone shows
 *    "typing" (chat/typing.php).
 *  - Long-press a message: Reply, Love, and for my own messages within 5 minutes
 *    Edit and Delete for everyone. The server checks the 5 minutes and refuses later changes.
 *  - "Seen": the other person opened the chat after my last message -> tiny avatar under it.
 *  - Screenshots: when I take one, a grey line "Umer took a screenshot" is added to the chat
 *    (Android 14+: the official ScreenCaptureCallback; older phones: watching for a new
 *    picture named "Screenshot..." in the gallery, if gallery access was allowed).
 *  - Phone / video icons start a call; the info icon and the header open their profile.
 */
class ChatActivity : BaseActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var otherUid: String
    private lateinit var chatId: String

    private var other: User? = null
    private var isFriend = false
    private val messages = mutableListOf<Message>()
    private var otherReadAt = 0L                 // when they last opened this chat
    private var replyTo: Message? = null         // the message my next one answers

    private val adapter = MessageAdapter()

    private val handler = Handler(Looper.getMainLooper())
    private var typingSent = false
    private var typingSentAt = 0L
    private val stopTyping = Runnable { setTyping(false) }

    /** Shared posts already asked from the server, and the ones that were deleted. */
    private val fetchedPosts = mutableSetOf<String>()
    private val gonePosts = mutableSetOf<String>()

    /** The server's time of the last answer: the next request asks only for changes since then. */
    private var since = 0L
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** Video and file pickers (Android's own document picker). */
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendVideo(uri)
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendFile(uri)
    }

    /** Camera in photo mode returns one photo (Extra.MEDIA_URI). */
    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.getStringExtra(Extra.MEDIA_URI)
            if (result.resultCode == RESULT_OK && uri != null) sendPhoto(Uri.parse(uri))
        }

    /** Photo picker in single-image mode returns one photo (Extra.PICKED_URIS). */
    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.getStringArrayListExtra(Extra.PICKED_URIS)?.firstOrNull()
            if (result.resultCode == RESULT_OK && uri != null) sendPhoto(Uri.parse(uri))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Who is this chat with? From a profile: their uid. From a notification: the chat id.
        otherUid = intent.getStringExtra(Extra.UID)
            ?: intent.getStringExtra(Extra.CHAT_ID)?.let { ChatRepo.otherUidOf(it) }
                    ?: ""
        if (otherUid.isEmpty() || otherUid == myUid) {
            finish()
            return
        }
        chatId = Db.chatId(myUid, otherUid)

        setupHeader()
        setupList()
        setupInput()
        Db.getUser(otherUid) { user -> if (user != null && !isDestroyed) showOther(user) }
        reload()

        // Messages written offline that were sent later: redraw.
        Outbox.synced.observe(this) { reload() }
    }

    override fun onResume() {
        super.onResume()
        openChatId = chatId
        ChatRepo.markRead(otherUid)
        // Remove this chat's notification (KinnectMessagingService uses chatId.hashCode() as its id).
        NotificationManagerCompat.from(this).cancel(chatId.hashCode())
    }

    override fun onPause() {
        openChatId = null
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        startScreenshotDetection()
        handler.post(poll)
    }

    override fun onStop() {
        handler.removeCallbacks(poll)
        stopScreenshotDetection()
        setTyping(false)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Header
    // ---------------------------------------------------------------------------------------

    private fun setupHeader() {
        binding.ivBack.setOnClickListener { finish() }
        binding.llHeaderPerson.setOnClickListener { openProfile(otherUid) }
        binding.ivInfo.setOnClickListener { openProfile(otherUid) }
        binding.ivCall.setOnClickListener {
            openScreen(VoiceCallActivity::class.java) {
                putExtra(Extra.UID, otherUid)
                putExtra(Extra.OUTGOING, true)
            }
        }
        binding.ivVideo.setOnClickListener {
            openScreen(VideoCallActivity::class.java) {
                putExtra(Extra.UID, otherUid)
                putExtra(Extra.OUTGOING, true)
            }
        }
    }

    /** Name, avatar, online dot and "Active now" / "Active 5m ago". */
    private fun showOther(user: User) {
        other = user
        binding.tvHeaderName.text = user.name
        Avatars.bind(binding.tvHeaderAvatar, binding.ivHeaderAvatarPhoto, user)
        Avatars.bind(binding.tvTypingAvatar, binding.ivTypingAvatarPhoto, user)
        binding.vHeaderOnline.visibility = if (user.online) View.VISIBLE else View.GONE
        binding.llTypingBubble.contentDescription = getString(R.string.cd_is_typing, firstName())

        val sinceSeen = ChatRepo.now() - user.lastSeen
        val status = when {
            user.online -> getString(R.string.chat_status)
            user.lastSeen == 0L || sinceSeen > DAY_MS -> ""
            sinceSeen < MINUTE_MS -> getString(R.string.chat_active_just_now)
            else -> getString(R.string.chat_active_ago, Times.ago(this, user.lastSeen))
        }
        binding.tvHeaderStatus.text = status
        binding.tvHeaderStatus.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()   // avatars and names inside the list
    }

    private fun firstName(): String = other?.name.orEmpty().substringBefore(' ')

    // ---------------------------------------------------------------------------------------
    // Live data
    // ---------------------------------------------------------------------------------------

    /** Draws the messages saved on the phone. */
    private fun reload() {
        val wasAtBottom = !binding.rvMessages.canScrollVertically(1)
        val oldLast = messages.lastOrNull()?.id
        messages.clear()
        messages.addAll(ChatRepo.cachedMessages(chatId))
        adapter.notifyDataSetChanged()
        val newLast = messages.lastOrNull()
        if (wasAtBottom || (newLast?.id != oldLast && newLast?.senderId == myUid)) scrollToEnd()
    }

    /** Asks the server what changed; new messages from them also count as read while I'm here. */
    private fun refresh() {
        ChatRepo.refresh(otherUid, since) { state ->
            if (state == null || isDestroyed) return@refresh
            val hadNew = since == 0L || ChatRepo.cachedMessages(chatId, 1).lastOrNull()?.id != messages.lastOrNull()?.id
            since = state.time
            state.other?.let { showOther(it) }
            isFriend = state.friends
            otherReadAt = state.otherReadAt
            binding.llTyping.visibility = if (state.typing) View.VISIBLE else View.GONE
            reload()
            if (hadNew && isResumedNow()) ChatRepo.markRead(otherUid)
        }
    }

    private fun isResumedNow() = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    private fun scrollToEnd() {
        if (adapter.itemCount > 0) binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
    }

    // ---------------------------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------------------------

    private fun setupInput() {
        binding.etMessage.doAfterTextChanged { text ->
            val hasText = !text.isNullOrBlank()
            // Thumbs-up while empty, paper plane once there is text.
            binding.ivSend.setImageResource(if (hasText) R.drawable.ic_send else R.drawable.ic_like)
            binding.ivSend.contentDescription = getString(if (hasText) R.string.cd_send else R.string.cd_send_like)
            // "typing": on while there is text, off 4 seconds after the last key.
            handler.removeCallbacks(stopTyping)
            if (hasText) {
                setTyping(true)
                handler.postDelayed(stopTyping, TYPING_TIMEOUT_MS)
            } else {
                setTyping(false)
            }
        }
        binding.ivSend.setOnClickListener { sendText() }
        binding.ivCancelReply.setOnClickListener { setReply(null) }
        binding.ivCamera.setOnClickListener {
            takePhoto.launch(Intent(this, CameraActivity::class.java).putExtra(Extra.PICK_MODE, PickMode.POST))
        }
        binding.ivGallery.setOnClickListener { showAttachMenu(it) }
    }

    /** Gallery button: Photo (Kinnect's picker), Video or File (Android's document picker). */
    private fun showAttachMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, ATTACH_PHOTO, 0, R.string.chat_attach_photo)
        popup.menu.add(0, ATTACH_VIDEO, 1, R.string.chat_attach_video)
        popup.menu.add(0, ATTACH_FILE, 2, R.string.chat_attach_file)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ATTACH_PHOTO -> pickPhoto.launch(
                    Intent(this, PhotoPickerActivity::class.java).putExtra(Extra.PICK_MODE, PickMode.SINGLE_IMAGE)
                )
                ATTACH_VIDEO -> pickVideo.launch("video/*")
                ATTACH_FILE -> pickFile.launch("*/*")
            }
            true
        }
        popup.show()
    }

    /** Only writes to the database when the state really changes. */
    private fun setTyping(typing: Boolean) {
        if (!::chatId.isInitialized) return
        val now = System.currentTimeMillis()
        // Typing lasts 6 s on the server: renew it every 3 s while still typing.
        if (typing == typingSent && (!typing || now - typingSentAt < TYPING_RENEW_MS)) return
        typingSent = typing
        typingSentAt = now
        ChatRepo.setTyping(otherUid, typing)
    }

    private fun sendText() {
        val text = binding.etMessage.text.toString().trim()
        ChatRepo.sendText(this, otherUid, text.ifEmpty { getString(R.string.chat_like_text) }, replyTo)
        binding.etMessage.setText("")
        setReply(null)
        reload()
        scrollToEnd()
    }

    /** Photos are shrunk and uploaded with the message (offline: queued with it). */
    private fun sendPhoto(uri: Uri) {
        ChatRepo.sendImage(this, otherUid, uri, replyTo)
        afterSendingMedia()
    }

    private fun sendVideo(uri: Uri) {
        if (sizeOf(uri) > MAX_UPLOAD_BYTES) {
            toast(R.string.chat_file_too_big)
            return
        }
        ChatRepo.sendVideo(this, otherUid, uri, replyTo)
        afterSendingMedia()
    }

    private fun sendFile(uri: Uri) {
        val size = sizeOf(uri)
        if (size > MAX_UPLOAD_BYTES) {
            toast(R.string.chat_file_too_big)
            return
        }
        ChatRepo.sendFile(this, otherUid, uri, nameOf(uri), size, replyTo)
        afterSendingMedia()
    }

    private fun afterSendingMedia() {
        setReply(null)
        reload()
        scrollToEnd()
    }

    /** The file's name and size as the phone's file picker reports them. */
    private fun nameOf(uri: Uri): String = query(uri, OpenableColumns.DISPLAY_NAME)?.toString() ?: "file"
    private fun sizeOf(uri: Uri): Long = (query(uri, OpenableColumns.SIZE) as? Long) ?: 0L

    private fun query(uri: Uri, column: String): Any? = try {
        contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null
            else if (column == OpenableColumns.SIZE) cursor.getLong(0) else cursor.getString(0)
        }
    } catch (e: Exception) {
        null
    }

    /** Shows or hides "Replying to ..." above the input. */
    private fun setReply(message: Message?) {
        replyTo = message
        binding.llReplyBar.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message == null) return
        binding.tvReplyTo.text =
            if (message.senderId == myUid) getString(R.string.chat_replying_to_self)
            else getString(R.string.chat_replying_to, firstName())
        binding.tvReplyText.text = previewOf(message)
        binding.etMessage.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(binding.etMessage, 0)
    }

    /** What a message looks like in one line: its text, "Photo" or "Shared a post". */
    private fun previewOf(message: Message): String = when {
        message.text.isNotEmpty() -> message.text
        message.type == MsgType.IMAGE -> getString(R.string.preview_photo)
        message.type == MsgType.VIDEO -> getString(R.string.preview_video)
        message.type == MsgType.FILE -> getString(R.string.preview_file, message.fileName)
        message.type == MsgType.POST -> getString(R.string.preview_post)
        else -> ""
    }

    // ---------------------------------------------------------------------------------------
    // Long-press menu: Reply, Love, Edit, Delete
    // ---------------------------------------------------------------------------------------

    private fun showMenu(anchor: View, message: Message) {
        if (message.deleted || message.type == MsgType.SYSTEM) return
        val loved = message.reactions[myUid] == Reaction.LOVE.key
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_REPLY, 0, R.string.chat_menu_reply)
        popup.menu.add(0, MENU_LOVE, 1, if (loved) R.string.chat_menu_unlove else R.string.chat_menu_love)
        if (ChatRepo.canChange(message)) {
            if (message.type == MsgType.TEXT) popup.menu.add(0, MENU_EDIT, 2, R.string.chat_menu_edit)
            popup.menu.add(0, MENU_DELETE, 3, R.string.chat_menu_delete)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REPLY -> setReply(message)
                MENU_LOVE -> {
                    ChatRepo.react(this, message, if (loved) null else Reaction.LOVE.key)
                    reload()
                }
                MENU_EDIT -> editMessage(message)
                MENU_DELETE -> ChatRepo.delete(message) { error ->
                    if (isDestroyed) return@delete
                    if (error != null) toast(error)   // e.g. "only within 5 minutes" from the server
                    reload()
                }
            }
            true
        }
        popup.show()
    }

    /** A small dialog with the text; Save changes it (if still within 5 minutes). */
    private fun editMessage(message: Message) {
        val field = EditText(this).apply {
            setText(message.text)
            setSelection(message.text.length)
        }
        val margin = resources.getDimensionPixelSize(R.dimen.screen_margin)
        val box = FrameLayout(this).apply {
            setPadding(margin, margin / 2, margin, 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_edit_title)
            .setView(box)
            .setPositiveButton(R.string.chat_edit_save) { _, _ ->
                val newText = field.text.toString().trim()
                if (newText.isNotEmpty() && newText != message.text) {
                    ChatRepo.edit(message, newText) { error ->
                        if (isDestroyed) return@edit
                        if (error != null) toast(error)   // the server refuses after 5 minutes
                        reload()
                    }
                }
            }
            .setNegativeButton(R.string.chat_edit_cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------------------------
    // Screenshot detection
    // ---------------------------------------------------------------------------------------

    private var captureCallback: Any? = null      // Activity.ScreenCaptureCallback on Android 14+
    private var observing = false
    private var lastScreenshotAt = 0L

    /** Android 13 and older: a new gallery picture whose name contains "screenshot". */
    private val screenshotObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = checkNewestPicture()
    }

    private fun startScreenshotDetection() {
        if (!::chatId.isInitialized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = Activity.ScreenCaptureCallback { onScreenshot() }
            registerScreenCaptureCallback(mainExecutor, callback)
            captureCallback = callback
        } else if (canReadGallery()) {
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, screenshotObserver)
            observing = true
        }
    }

    private fun stopScreenshotDetection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            (captureCallback as? Activity.ScreenCaptureCallback)?.let { unregisterScreenCaptureCallback(it) }
            captureCallback = null
        }
        if (observing) {
            contentResolver.unregisterContentObserver(screenshotObserver)
            observing = false
        }
    }

    private fun canReadGallery(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Looks at the newest picture: a screenshot saved in the last 10 seconds? */
    private fun checkNewestPicture() {
        val columns = arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED)
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return
                val name = cursor.getString(0).orEmpty()
                val addedMs = cursor.getLong(1) * 1000   // stored in seconds
                if (name.contains("screenshot", ignoreCase = true) &&
                    System.currentTimeMillis() - addedMs < RECENT_MS
                ) {
                    onScreenshot()
                }
            }
        } catch (e: SecurityException) {
            // Gallery access was taken away while the chat was open: just skip.
        }
    }

    /** Adds "Umer took a screenshot" to the chat (once per screenshot). */
    private fun onScreenshot() {
        val now = System.currentTimeMillis()
        if (now - lastScreenshotAt < RECENT_MS) return
        lastScreenshotAt = now
        Db.getUser(myUid) { me ->
            ChatRepo.sendSystem(this, otherUid, getString(R.string.chat_screenshot, me?.name.orEmpty()))
        }
        toast(getString(R.string.chat_screenshot_toast, firstName()))
    }

    // ---------------------------------------------------------------------------------------
    // The list: row 0 is the intro, then one row per message
    // ---------------------------------------------------------------------------------------

    private fun setupList() {
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = adapter
        // Keyboard opening makes the list shorter: keep the newest message in view.
        binding.rvMessages.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) binding.rvMessages.post { scrollToEnd() }
        }
    }

    /** Two messages are in one group: same sender, both normal, less than 5 minutes apart. */
    private fun sameGroup(a: Message?, b: Message?): Boolean =
        a != null && b != null && a.senderId == b.senderId &&
                a.type != MsgType.SYSTEM && b.type != MsgType.SYSTEM &&
                b.replyToId.isEmpty() && !startsNewPart(a, b) &&
                b.timestamp - a.timestamp <= GROUP_MS

    /** A time separator goes above [b] when more than an hour passed since [a]. */
    private fun startsNewPart(a: Message?, b: Message): Boolean = a == null || b.timestamp - a.timestamp > PART_MS

    private inner class IntroHolder(val view: ItemChatIntroBinding) : RecyclerView.ViewHolder(view.root)
    private inner class MessageHolder(val view: ItemMessageBinding) : RecyclerView.ViewHolder(view.root)

    private inner class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = messages.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) TYPE_INTRO else TYPE_MESSAGE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_INTRO) IntroHolder(ItemChatIntroBinding.inflate(inflater, parent, false))
            else MessageHolder(ItemMessageBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is IntroHolder) bindIntro(holder.view)
            else if (holder is MessageHolder) bindMessage(holder.view, position - 1)
        }

        /** Big avatar, name, "You're friends on Kinnect · Lives in Karachi". */
        private fun bindIntro(view: ItemChatIntroBinding) {
            val user = other
            Avatars.bind(view.tvAvatar, view.ivAvatarPhoto, user)
            view.tvName.text = user?.name.orEmpty()
            val relation = getString(if (isFriend) R.string.chat_intro_friends else R.string.chat_intro_not_friends)
            val city = user?.city.orEmpty()
            view.tvSubtitle.text = if (city.isEmpty()) relation else getString(R.string.chat_intro_city, relation, city)
        }

        private fun bindMessage(view: ItemMessageBinding, index: Int) {
            val message = messages[index]
            val previous = messages.getOrNull(index - 1)
            val next = messages.getOrNull(index + 1)
            val mine = message.senderId == myUid
            val isLast = index == messages.size - 1
            view.root.tag = message.id

            // Time separator, and the space above this row.
            val newPart = startsNewPart(previous, message)
            view.tvDay.visibility = if (newPart) View.VISIBLE else View.GONE
            if (newPart) view.tvDay.text = Times.dayHeader(this@ChatActivity, message.timestamp)
            val first = !sameGroup(previous, message)
            val last = !sameGroup(message, next)
            val gap = when {
                newPart -> 0
                first -> R.dimen.chat_group_gap
                else -> R.dimen.chat_bubble_gap
            }
            view.root.setPadding(0, if (gap == 0) 0 else resources.getDimensionPixelSize(gap), 0, 0)

            // "Aisha took a screenshot": a grey centred line and nothing else.
            val system = message.type == MsgType.SYSTEM
            view.tvSystem.visibility = if (system) View.VISIBLE else View.GONE
            view.llRow.visibility = if (system) View.GONE else View.VISIBLE
            if (system) {
                view.tvSystem.text = message.text
                view.tvReplyLabel.visibility = View.GONE
                view.tvMeta.visibility = View.GONE
                view.vSeen.visibility = View.GONE
                return
            }

            // Sides: mine on the right without an avatar, theirs on the left with it.
            val side = if (mine) Gravity.END else Gravity.START
            view.llRow.gravity = Gravity.BOTTOM or side
            view.llContent.gravity = side
            view.flAvatar.visibility = when {
                mine -> View.GONE
                last -> View.VISIBLE
                else -> View.INVISIBLE   // keeps the bubbles lined up
            }
            if (!mine) Avatars.bind(view.tvAvatar, view.ivAvatarPhoto, other)

            bindReply(view, message, mine, side)
            bindContent(view, message, mine, first, last)
            bindReaction(view, message, side)
            bindMeta(view, message, mine, isLast)
        }

        /** "You replied to Aisha" and the quoted message peeking out above the bubble. */
        private fun bindReply(view: ItemMessageBinding, message: Message, mine: Boolean, side: Int) {
            val isReply = message.replyToId.isNotEmpty() && !message.deleted
            view.tvReplyLabel.visibility = if (isReply) View.VISIBLE else View.GONE
            view.tvQuote.visibility = if (isReply) View.VISIBLE else View.GONE
            (view.tvQuote.layoutParams as FrameLayout.LayoutParams).gravity = side
            if (!isReply) return

            val toMe = message.replyToSenderId == myUid
            view.tvReplyLabel.text = when {
                mine && toMe -> getString(R.string.chat_replied_self)
                mine -> getString(R.string.chat_replied_you, firstName())
                toMe -> getString(R.string.chat_replied_to_you, firstName())
                else -> getString(R.string.chat_replied_them, firstName())
            }
            placeUnderBubble(view.tvReplyLabel, side)
            view.tvQuote.text = message.replyToText.ifEmpty { getString(R.string.preview_photo) }
        }

        /** The photo, shared post and / or text bubble. */
        private fun bindContent(view: ItemMessageBinding, message: Message, mine: Boolean, first: Boolean, last: Boolean) {
            val showImage = message.type == MsgType.IMAGE && !message.deleted
            val showPost = message.type == MsgType.POST && !message.deleted
            // Videos and files are a text bubble with an icon line; tapping opens them.
            val attachment = !message.deleted && (message.type == MsgType.VIDEO || message.type == MsgType.FILE)
            val showText = message.deleted || message.text.isNotEmpty() || attachment
            view.flImage.visibility = if (showImage) View.VISIBLE else View.GONE
            view.llPost.visibility = if (showPost) View.VISIBLE else View.GONE
            view.tvText.visibility = if (showText) View.VISIBLE else View.GONE

            if (showImage) {
                view.flImage.clipToOutline = true
                Images.load(view.ivImage, message.mediaId)
            }
            if (showPost) bindPost(view, message)

            if (message.deleted) {
                // "Message deleted": grey and italic on both sides.
                view.tvText.text = getString(R.string.preview_deleted)
                view.tvText.setTypeface(view.tvText.typeface, Typeface.ITALIC)
                view.tvText.setTextColor(ContextCompat.getColor(this@ChatActivity, R.color.text_grey))
                view.tvText.setBackgroundResource(R.drawable.bg_bubble_received)
            } else {
                view.tvText.text = when {
                    message.type == MsgType.VIDEO -> getString(R.string.chat_video_bubble)
                    message.type == MsgType.FILE -> getString(R.string.chat_file_bubble, message.fileName, sizeText(message.fileSize))
                    else -> message.text
                }
                view.tvText.typeface = Typeface.create(view.tvText.typeface, Typeface.NORMAL)
                view.tvText.setTextColor(ContextCompat.getColor(this@ChatActivity, if (mine) R.color.white else R.color.text_dark))
                view.tvText.setBackgroundResource(bubbleShape(mine, first, last))
            }

            view.tvText.setOnClickListener(if (attachment) View.OnClickListener { openAttachment(message) } else null)

            // Long-press any part of the message for the menu.
            val longPress = View.OnLongClickListener { showMenu(it, message); true }
            view.tvText.setOnLongClickListener(longPress)
            view.flImage.setOnLongClickListener(longPress)
            view.llPost.setOnLongClickListener(longPress)
        }

        /** Joined corners inside a group (the drawables from Assignment 1). */
        private fun bubbleShape(mine: Boolean, first: Boolean, last: Boolean): Int = when {
            first && last -> if (mine) R.drawable.bg_bubble_sent else R.drawable.bg_bubble_received
            first -> if (mine) R.drawable.bg_bubble_sent_first else R.drawable.bg_bubble_received_first
            last -> if (mine) R.drawable.bg_bubble_sent_last else R.drawable.bg_bubble_received_last
            else -> if (mine) R.drawable.bg_bubble_sent_middle else R.drawable.bg_bubble_received_middle
        }

        /** Shared post card: loads the post, its author and first photo. Tap: its comments. */
        private fun bindPost(view: ItemMessageBinding, message: Message) {
            view.llPost.clipToOutline = true
            view.tvPostAuthor.text = ""
            view.tvPostText.text = ""
            view.ivPostImage.visibility = View.GONE
            view.llPost.setOnClickListener {
                openScreen(CommentsActivity::class.java) { putExtra(Extra.POST_ID, message.postId) }
            }
            val show = show@{ post: com.rameen.i230806.data.Post?, gone: Boolean ->
                if (view.root.tag != message.id) return@show   // row reused for another message
                if (post == null) {
                    if (gone) {
                        view.tvPostAuthor.setText(R.string.chat_post_unavailable)
                        view.llPost.setOnClickListener(null)
                    }
                    return@show
                }
                view.tvPostText.text = post.text
                view.tvPostText.visibility = if (post.text.isEmpty()) View.GONE else View.VISIBLE
                post.imageIds.firstOrNull()?.let { imageId ->
                    view.ivPostImage.visibility = View.VISIBLE
                    Images.load(view.ivPostImage, imageId)
                }
                Db.getUser(post.authorId) { author ->
                    if (view.root.tag == message.id) view.tvPostAuthor.text = author?.name.orEmpty()
                }
            }
            // The saved copy; the server is asked once per post while this screen is open.
            val cached = PostRepo.cachedPost(message.postId)
            if (cached != null || message.postId in gonePosts) show(cached, message.postId in gonePosts)
            if (fetchedPosts.add(message.postId)) {
                PostRepo.refreshPost(message.postId) { post, gone ->
                    if (gone) gonePosts.add(message.postId)
                    if (post != null || gone) show(post, gone)
                }
            }
        }

        /** Love heart on the bubble's bottom-left corner; the bubble makes room for half of it. */
        private fun bindReaction(view: ItemMessageBinding, message: Message, side: Int) {
            val reaction = message.reactions.values.firstNotNullOfOrNull { Reaction.of(it) }
            val show = reaction != null && !message.deleted
            view.flReaction.visibility = if (show) View.VISIBLE else View.GONE
            if (reaction != null) view.ivReaction.setImageResource(reaction.icon)

            val offset = if (show) resources.getDimensionPixelSize(R.dimen.chat_heart_offset) else 0
            val quoteTop = if (view.tvQuote.visibility == View.VISIBLE) resources.getDimensionPixelSize(R.dimen.chat_quote_visible) else 0
            val params = view.llContent.layoutParams as FrameLayout.LayoutParams
            params.gravity = side
            params.setMargins(offset, quoteTop, 0, offset)
            view.llContent.layoutParams = params
        }

        /** "Edited" / "Sent" under the bubble, and the seen avatar under my last message. */
        private fun bindMeta(view: ItemMessageBinding, message: Message, mine: Boolean, isLast: Boolean) {
            val seen = mine && isLast && !message.pending && otherReadAt >= message.timestamp
            val parts = mutableListOf<String>()
            if (message.edited && !message.deleted) parts.add(getString(R.string.chat_edited))
            if (message.pending) parts.add(getString(R.string.chat_sending))
            else if (mine && isLast && !seen) parts.add(getString(R.string.chat_sent))
            view.tvMeta.text = parts.joinToString(" · ")
            view.tvMeta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
            placeUnderBubble(view.tvMeta, if (mine) Gravity.END else Gravity.START)

            view.vSeen.visibility = if (seen) View.VISIBLE else View.GONE
            if (seen) {
                view.vSeen.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this@ChatActivity, Avatars.colorFor(otherUid)))
                view.vSeen.contentDescription = getString(R.string.cd_seen_by, firstName())
            }
        }

        /** "2.4 MB", "830 KB" */
        private fun sizeText(bytes: Long): String = when {
            bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
            bytes > 0 -> "${(bytes + 1023) / 1024} KB"
            else -> ""
        }

        /** Small captions line up with the bubble: right edge for mine, after the avatar for theirs. */
        private fun placeUnderBubble(label: View, side: Int) {
            val params = label.layoutParams as LinearLayout.LayoutParams
            params.gravity = side
            params.marginStart = if (side == Gravity.START) resources.getDimensionPixelSize(R.dimen.chat_meta_indent) else 0
            label.layoutParams = params
        }
    }

    /** Opens a video or file with whatever app the phone has for it (player, PDF viewer ...). */
    private fun openAttachment(message: Message) {
        if (message.pending || message.mediaId.isEmpty()) {
            toast(R.string.chat_sending)
            return
        }
        val type = if (message.type == MsgType.VIDEO) "video/*" else "*/*"
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(message.mediaId), type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: android.content.ActivityNotFoundException) {
            // No app for this type: let the browser download it.
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(message.mediaId)))
        }
    }

    companion object {
        /** The chat on screen right now, so no notification pops up for it (KinnectMessagingService). */
        @Volatile
        var openChatId: String? = null

        private const val TYPE_INTRO = 0
        private const val TYPE_MESSAGE = 1

        private const val MENU_REPLY = 1
        private const val MENU_LOVE = 2
        private const val MENU_EDIT = 3
        private const val MENU_DELETE = 4

        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = 24 * 60 * MINUTE_MS
        private const val GROUP_MS = 5 * MINUTE_MS        // bubbles closer than this join up
        private const val PART_MS = 60 * MINUTE_MS        // a new time separator after an hour
        private const val TYPING_TIMEOUT_MS = 4000L
        private const val TYPING_RENEW_MS = 3000L
        private const val POLL_MS = 2000L
        private const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024   // the server's limit (upload.php)

        private const val ATTACH_PHOTO = 1
        private const val ATTACH_VIDEO = 2
        private const val ATTACH_FILE = 3
        private const val RECENT_MS = 10_000L
    }
}