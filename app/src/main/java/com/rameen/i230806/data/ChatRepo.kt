package com.rameen.i230806.data

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.rameen.i230806.R
import com.rameen.i230806.push.PushSender

/**
 * Everything that changes chats (used by Chat, Chats and Share to chat). How it is stored
 * (see Models.kt):
 *
 *   messages/{chatId}/{msgId}      Message
 *   userChats/{uid}/{chatId}       ChatSummary: one row per conversation on the Chats screen,
 *                                  stored for BOTH people (each with their own unread count)
 *   typing/{chatId}/{uid}          true while that person is typing
 *   chatReads/{chatId}/{uid}       when that person last opened the chat ("Seen")
 *
 * chatId is the two uids in alphabetical order (Db.chatId), so both phones use the same one.
 * Sending writes the message and both summaries in ONE updateChildren call, so the Chats list
 * can never show a message that isn't in the chat. The other person's unread count goes up
 * with ServerValue.increment(1), and they get a push notification (PushSender).
 *
 * Your own messages can be edited or deleted for EDIT_WINDOW_MS (5 minutes) after sending.
 */
object ChatRepo {

    const val EDIT_WINDOW_MS = 5 * 60 * 1000L

    private val me: String get() = Db.uid ?: ""

    /** The other person in a chat: chatId is "uidA_uidB". */
    fun otherUidOf(chatId: String): String = chatId.split("_").firstOrNull { it != me } ?: ""

    // ---------------------------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------------------------

    fun sendText(context: Context, otherUid: String, text: String, replyTo: Message? = null) {
        send(context, otherUid, Message(type = MsgType.TEXT, text = text).withReply(replyTo), text)
    }

    fun sendImage(context: Context, otherUid: String, mediaId: String, replyTo: Message? = null) {
        send(
            context, otherUid,
            Message(type = MsgType.IMAGE, mediaId = mediaId).withReply(replyTo),
            context.getString(R.string.preview_photo),
        )
    }

    /** Share to chat: the post, with an optional message. */
    fun sendPost(context: Context, otherUid: String, postId: String, text: String) {
        send(
            context, otherUid,
            Message(type = MsgType.POST, postId = postId, text = text),
            text.ifEmpty { context.getString(R.string.preview_post) },
        )
    }

    /** A grey line in the chat, e.g. "Aisha took a screenshot". No push notification. */
    fun sendSystem(context: Context, otherUid: String, text: String) {
        send(context, otherUid, Message(type = MsgType.SYSTEM, text = text), text, push = false)
    }

    /** Copies what is needed to show "replying to ..." above the new message. */
    private fun Message.withReply(replyTo: Message?): Message {
        if (replyTo != null) {
            replyToId = replyTo.id
            replyToText = replyTo.text
            replyToSenderId = replyTo.senderId
        }
        return this
    }

    private fun send(context: Context, otherUid: String, message: Message, preview: String, push: Boolean = true) {
        if (me.isEmpty() || otherUid.isEmpty()) return
        val chatId = Db.chatId(me, otherUid)
        val ref = Db.messages(chatId).push()
        val now = Db.now()
        message.id = ref.key ?: ""
        message.senderId = me
        message.timestamp = now

        val paths = HashMap<String, Any?>()
        paths["messages/$chatId/${message.id}"] = message
        // My row on Chats
        paths["userChats/$me/$chatId/chatId"] = chatId
        paths["userChats/$me/$chatId/otherUid"] = otherUid
        paths["userChats/$me/$chatId/lastMessage"] = preview
        paths["userChats/$me/$chatId/lastSenderId"] = me
        paths["userChats/$me/$chatId/timestamp"] = now
        // Their row on Chats, with one more unread message
        paths["userChats/$otherUid/$chatId/chatId"] = chatId
        paths["userChats/$otherUid/$chatId/otherUid"] = me
        paths["userChats/$otherUid/$chatId/lastMessage"] = preview
        paths["userChats/$otherUid/$chatId/lastSenderId"] = me
        paths["userChats/$otherUid/$chatId/timestamp"] = now
        paths["userChats/$otherUid/$chatId/unread"] = ServerValue.increment(1)
        Db.root.updateChildren(paths)

        if (push) {
            Db.getUser(me) { user ->
                PushSender.sendMessage(context, otherUid, user?.name.orEmpty(), preview, chatId)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Editing, deleting, reacting
    // ---------------------------------------------------------------------------------------

    /** True for my own messages, not yet deleted, sent less than 5 minutes ago. */
    fun canChange(message: Message): Boolean =
        message.senderId == me && !message.deleted && message.type != MsgType.SYSTEM &&
                Db.now() - message.timestamp <= EDIT_WINDOW_MS

    /** Changes the text of my message (within 5 minutes). Returns false if it's too late. */
    fun edit(chatId: String, message: Message, newText: String): Boolean {
        if (!canChange(message) || message.type != MsgType.TEXT) return false
        Db.messages(chatId).child(message.id).updateChildren(mapOf("text" to newText, "edited" to true))
        updatePreviewIfLast(chatId, message, newText)
        return true
    }

    /** Deletes my message for everyone (within 5 minutes): the bubble then says "Message deleted". */
    fun delete(context: Context, chatId: String, message: Message): Boolean {
        if (!canChange(message)) return false
        val paths = HashMap<String, Any?>()
        paths["messages/$chatId/${message.id}/deleted"] = true
        paths["messages/$chatId/${message.id}/text"] = ""
        paths["messages/$chatId/${message.id}/mediaId"] = ""
        if (message.mediaId.isNotEmpty()) paths["media/${message.mediaId}"] = null
        Db.root.updateChildren(paths)
        updatePreviewIfLast(chatId, message, context.getString(R.string.preview_deleted))
        return true
    }

    /** If [message] is the newest in the chat, both rows on Chats show [preview] instead. */
    private fun updatePreviewIfLast(chatId: String, message: Message, preview: String) {
        val other = otherUidOf(chatId)
        Db.userChats(me).child(chatId).child("timestamp")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if ((snapshot.value as? Number)?.toLong() != message.timestamp) return
                    Db.root.updateChildren(
                        mapOf(
                            "userChats/$me/$chatId/lastMessage" to preview,
                            "userChats/$other/$chatId/lastMessage" to preview,
                        )
                    )
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    /** Puts my reaction on a message (double tap = love), or removes it with null. */
    fun react(chatId: String, message: Message, reactionKey: String?) {
        val ref = Db.messages(chatId).child(message.id).child("reactions").child(me)
        if (reactionKey == null) ref.removeValue() else ref.setValue(reactionKey)
    }

    // ---------------------------------------------------------------------------------------
    // Read receipts and typing
    // ---------------------------------------------------------------------------------------

    /** Opening a chat: my unread count goes to 0 and "Seen" time is updated for the other person. */
    fun markRead(chatId: String) {
        Db.chatReads(chatId).child(me).setValue(Db.now())
        // Only if the row exists, so opening an empty chat doesn't create a half row on Chats.
        Db.userChats(me).child(chatId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) snapshot.ref.child("unread").setValue(0)
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    /** "typing..." for the other person. Removed automatically if my phone disconnects. */
    fun setTyping(chatId: String, typing: Boolean) {
        val ref = Db.typing(chatId).child(me)
        if (typing) {
            ref.setValue(true)
            ref.onDisconnect().removeValue()
        } else {
            ref.removeValue()
        }
    }
}