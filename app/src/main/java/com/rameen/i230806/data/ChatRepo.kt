package com.rameen.i230806.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Everything about chats (Chat, Chats, Share to chat, story replies, call lines).
 * Assignment 3: messages live in MySQL behind the chat/ PHP files and in SQLite on the phone.
 *
 *  - chatId = smaller user id + "_" + bigger one (Db.chatId), the same as the server.
 *  - Sending: the message is saved in SQLite at once with pending = true ("Sending..."), then
 *    sent through the Outbox. Offline it waits in the queue (with its UUID) and is sent in
 *    order when the internet is back; the server ignores a repeated UUID.
 *  - Reading: the Chat screen asks chat/messages.php every 2 seconds for what changed since
 *    the last answer (new, edited, deleted messages, reactions, "Seen", "typing").
 *  - Edit / delete: only my own messages, only within 5 minutes. The SERVER checks the time
 *    (chat/edit.php, chat/delete.php) and refuses later changes; the app hides the menu items
 *    after 5 minutes too, using the server's clock. They need the internet.
 *  - The push notification to the other phone is sent by the server (step 8).
 */
object ChatRepo {

    const val EDIT_WINDOW_MS = 5 * 60 * 1000L

    private val me: String get() = Db.uid ?: ""

    /** Server clock minus phone clock, from the last chat answer. */
    @Volatile private var serverOffset = 0L

    fun now(): Long = System.currentTimeMillis() + serverOffset

    fun rememberServerTime(time: Long) {
        if (time > 0) serverOffset = time - System.currentTimeMillis()
    }

    /** The other person in a chat: chatId is "idA_idB". */
    fun otherUidOf(chatId: String): String = chatId.split("_").firstOrNull { it != me } ?: ""

    private fun isLocal(id: String) = id.startsWith("local-")

    // ---------------------------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------------------------

    fun sendText(context: Context, otherUid: String, text: String, replyTo: Message? = null) =
        send(context, otherUid, MsgType.TEXT, text, replyTo = replyTo)

    fun sendImage(context: Context, otherUid: String, photo: Uri, replyTo: Message? = null) =
        send(context, otherUid, MsgType.IMAGE, "", media = photo, replyTo = replyTo)

    fun sendVideo(context: Context, otherUid: String, video: Uri, replyTo: Message? = null) =
        send(context, otherUid, MsgType.VIDEO, "", media = video, replyTo = replyTo)

    fun sendFile(context: Context, otherUid: String, file: Uri, name: String, size: Long, replyTo: Message? = null) =
        send(context, otherUid, MsgType.FILE, "", media = file, fileName = name, fileSize = size, replyTo = replyTo)

    /** Share to chat: the post, with an optional message. */
    fun sendPost(context: Context, otherUid: String, postId: String, text: String) =
        send(context, otherUid, MsgType.POST, text, postId = postId)

    /** A grey line in the chat, e.g. "Aisha took a screenshot" or a call line. */
    fun sendSystem(context: Context, otherUid: String, text: String) =
        send(context, otherUid, MsgType.SYSTEM, text)

    private fun send(
        context: Context,
        otherUid: String,
        type: String,
        text: String,
        media: Uri? = null,
        fileName: String = "",
        fileSize: Long = 0,
        postId: String = "",
        replyTo: Message? = null,
    ) {
        if (me.isEmpty() || otherUid.isEmpty()) return
        val chatId = Db.chatId(me, otherUid)
        val uuid = Outbox.newUuid()
        val localId = Outbox.localId(uuid)
        val created = now()

        // 1. Shown at once, as "Sending...".
        val local = JSONObject()
            .put("id", localId)
            .put("chat_id", chatId)
            .put("sender_id", me.toLong())
            .put("receiver_id", otherUid.toLong())
            .put("type", type)
            .put("text", text)
            .put("media_url", media?.toString() ?: JSONObject.NULL)
            .put("file_name", fileName)
            .put("file_size", fileSize)
            .put("post_id", postId.toLongOrNull() ?: JSONObject.NULL)
            .put("reply_to_id", replyTo?.id?.toLongOrNull() ?: JSONObject.NULL)
            .put("reply_to_text", replyTo?.text.orEmpty())
            .put("reply_to_type", replyTo?.type.orEmpty())
            .put("reply_to_sender_id", replyTo?.senderId?.toLongOrNull() ?: JSONObject.NULL)
            .put("created_at", created)
            .put("updated_at", created)
            .put("pending", true)
        Json.saveMessage(local)

        // 2. Sent (or queued).
        val body = JSONObject().put("to", otherUid.toLong()).put("type", type).put("text", text)
        if (postId.isNotEmpty()) body.put("post_id", postId.toLong())
        replyTo?.id?.toLongOrNull()?.let { body.put("reply_to_id", it) }
        Outbox.send(
            context, "message", "chat/send.php", body,
            listOfNotNull(media?.let { "media" to it }), uuid, localId,
        ) { result ->
            val saved = result?.json?.optJSONObject("message")
            if (result != null && result.ok && saved != null) {
                LocalDb.delete(LocalDb.MESSAGES, "id = ?", arrayOf(localId))
                Json.saveMessage(saved)
            } else if (result != null && !result.ok) {
                LocalDb.delete(LocalDb.MESSAGES, "id = ?", arrayOf(localId))   // refused (e.g. file too big)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------

    /** The newest messages of a chat on the phone, oldest first (pending ones included). */
    fun cachedMessages(chatId: String, limit: Int = 200): List<Message> =
        LocalDb.list(LocalDb.MESSAGES, "chat_id = ?", arrayOf(chatId), "created_at DESC", limit)
            .map { Json.message(it) }
            .reversed()

    /** What chat/messages.php says besides the messages. */
    class ChatState(
        val other: User?,
        val friends: Boolean,
        val otherReadAt: Long,
        val typing: Boolean,
        val time: Long,
    )

    /**
     * Asks the server what changed in the chat with [otherUid] since [since] (0 = the newest
     * 100) and saves it in SQLite. [onDone] gets the state, or null when offline.
     */
    fun refresh(otherUid: String, since: Long, onDone: (ChatState?) -> Unit) {
        Api.get("chat/messages.php", mapOf("with" to otherUid, "since" to since)) { result ->
            val json = result.json
            if (!result.ok || json == null) {
                onDone(null)
                return@get
            }
            rememberServerTime(json.optLong("time"))
            Json.objects(json.optJSONArray("messages")).forEach { Json.saveMessage(it) }
            onDone(
                ChatState(
                    other = json.optJSONObject("other")?.let { Json.saveUser(it) },
                    friends = json.optBoolean("friends"),
                    otherReadAt = json.optLong("other_read_at"),
                    typing = json.optBoolean("typing"),
                    time = json.optLong("time"),
                )
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Editing, deleting, reacting
    // ---------------------------------------------------------------------------------------

    /** Show Edit / Delete? My own, sent, not deleted, less than 5 minutes old (server clock). */
    fun canChange(message: Message): Boolean =
        message.senderId == me && !message.deleted && !message.pending && message.type != MsgType.SYSTEM &&
            now() - message.timestamp <= EDIT_WINDOW_MS

    /** Changes the text of my message. The server checks the 5 minutes; [onDone] gets its error. */
    fun edit(message: Message, newText: String, onDone: (error: String?) -> Unit) {
        Api.post("chat/edit.php", JSONObject().put("id", message.id.toLong()).put("text", newText)) { result ->
            result.json?.optJSONObject("message")?.takeIf { result.ok }?.let { Json.saveMessage(it) }
            onDone(if (result.ok) null else result.error)
        }
    }

    /** Deletes my message for everyone ("Message deleted"). A pending one just leaves the queue. */
    fun delete(message: Message, onDone: (error: String?) -> Unit) {
        if (isLocal(message.id)) {
            LocalDb.delete(LocalDb.MESSAGES, "id = ?", arrayOf(message.id))
            LocalDb.removePending(message.id.removePrefix("local-"))
            onDone(null)
            return
        }
        Api.post("chat/delete.php", JSONObject().put("id", message.id.toLong())) { result ->
            result.json?.optJSONObject("message")?.takeIf { result.ok }?.let { Json.saveMessage(it) }
            onDone(if (result.ok) null else result.error)
        }
    }

    /** Puts my reaction on a message (love), or removes it with null. Shown at once. */
    fun react(context: Context, message: Message, reactionKey: String?) {
        if (isLocal(message.id)) return
        LocalDb.get(LocalDb.MESSAGES, "id", message.id)?.let { json ->
            val reactions = json.optJSONObject("reactions") ?: JSONObject().also { json.put("reactions", it) }
            if (reactionKey == null) reactions.remove(me) else reactions.put(me, reactionKey)
            Json.saveMessage(json)
        }
        val body = JSONObject().put("id", message.id.toLong()).put("type", reactionKey ?: "")
        Outbox.send(context, "message_react", "chat/react.php", body)
    }

    // ---------------------------------------------------------------------------------------
    // Read receipts and typing
    // ---------------------------------------------------------------------------------------

    /** Opening a chat: my unread count goes to 0 and the other person sees "Seen". */
    fun markRead(otherUid: String) {
        if (otherUid.isEmpty() || !NetworkMonitor.isOnline) return
        Api.post("chat/read.php", JSONObject().put("with", otherUid.toLong())) { }
    }

    /** "typing..." for the other person (the server forgets it after 6 seconds by itself). */
    fun setTyping(otherUid: String, typing: Boolean) {
        if (otherUid.isEmpty() || !NetworkMonitor.isOnline) return
        Api.post("chat/typing.php", JSONObject().put("with", otherUid.toLong()).put("typing", typing)) { }
    }
}
