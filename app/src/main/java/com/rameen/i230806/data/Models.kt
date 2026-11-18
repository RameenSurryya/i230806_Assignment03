package com.rameen.i230806.data

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.firebase.database.IgnoreExtraProperties
import com.rameen.i230806.R

/*
 * Everything Kinnect stores in the Firebase Realtime Database.
 *
 * Firebase turns JSON into these classes by calling an empty constructor and then setting each
 * field, so every field is a `var` with a default value. A field's name is its JSON key:
 * User.name is stored at users/{uid}/name.
 *
 * The whole database (Db.kt has a function for each path):
 *
 *   users/{uid}                    User
 *   usernames/{username}           uid           (one owner per username)
 *   friendRequests/{toUid}/{fromUid}  timestamp
 *   friends/{uid}/{friendUid}      timestamp     (stored on both sides)
 *   closeFriends/{uid}/{friendUid} true
 *   posts/{postId}                 Post          (reactions live inside the post)
 *   comments/{postId}/{commentId}  Comment
 *   stories/{uid}/{storyId}        Story
 *   media/{mediaId}                { data: Base64 text, type: "image" | "video", ownerId }
 *   notifications/{uid}/{id}       AppNotification
 *   userChats/{uid}/{chatId}       ChatSummary   (one row per conversation in Chats)
 *   messages/{chatId}/{msgId}      Message
 *   typing/{chatId}/{uid}          true while typing
 *   chatReads/{chatId}/{uid}       timestamp of the last message that user has seen
 *   calls/{calleeUid}              Call          (the ringing call, if any)
 *   callLogs/{uid}/{callId}        CallLog
 *   listings/{id}                  Listing       (Marketplace)
 *
 * Pictures are never stored inside a post or user. They live under media/ and the post only
 * keeps their ids, so loading the feed doesn't download every picture at once.
 */

@IgnoreExtraProperties
data class User(
    var uid: String = "",
    var name: String = "",
    var nameLower: String = "",       // "jacob west": lets Search match "jac" (Firebase is case-sensitive)
    var username: String = "",
    var usernameLower: String = "",
    var email: String = "",
    var bio: String = "",
    var website: String = "",
    var phone: String = "",
    var gender: String = "",
    var birthday: String = "",        // "14 Mar 2004"
    var work: String = "",            // Details on the profile: "Designer at ..."
    var study: String = "",           // "Studied at ..."
    var city: String = "",            // "Lives in ..."
    var photoId: String = "",         // profile picture: media/{photoId}, "" = initials only
    var coverId: String = "",         // cover photo: media/{coverId}
    var setupDone: Boolean = false,   // false until the first-time Edit profile is saved
    var online: Boolean = false,
    var lastSeen: Long = 0,
    var createdAt: Long = 0,
    var fcmToken: String = "",        // this user's phone, for push notifications
)

@IgnoreExtraProperties
data class Post(
    var id: String = "",
    var authorId: String = "",
    var text: String = "",
    var imageIds: List<String> = emptyList(),      // media ids, in the order they were picked
    var timestamp: Long = 0,
    var reactions: Map<String, String> = emptyMap(), // uid -> reaction key ("like", "love" ...)
    var commentCount: Long = 0,
    var shareCount: Long = 0,
    var pending: Boolean = false,                  // created offline, not sent to the server yet
)

@IgnoreExtraProperties
data class Comment(
    var id: String = "",
    var postId: String = "",
    var authorId: String = "",
    var text: String = "",
    var imageId: String = "",                   // image comment, "" if none
    var parentId: String = "",                  // "" = top-level comment, else the comment it replies to
    var timestamp: Long = 0,
    var likes: Map<String, Boolean> = emptyMap(), // uid -> true
    var pending: Boolean = false,               // written offline, not sent to the server yet
)

@IgnoreExtraProperties
data class Story(
    var id: String = "",
    var authorId: String = "",
    var mediaId: String = "",
    var mediaType: String = MediaType.IMAGE,
    var label: String = "",                     // text sticker from the story editor
    var audience: String = Audience.PUBLIC,
    var timestamp: Long = 0,
    var expiresAt: Long = 0,                    // timestamp + 24 hours
    var viewers: Map<String, Long> = emptyMap(),   // uid -> when they watched it
    var reactions: Map<String, String> = emptyMap(), // uid -> reaction key
)

@IgnoreExtraProperties
data class AppNotification(
    var id: String = "",
    var type: String = "",                      // NotifType
    var fromUid: String = "",
    var postId: String = "",
    var text: String = "",                      // e.g. the comment that was replied to
    var timestamp: Long = 0,
    var read: Boolean = false,
)

@IgnoreExtraProperties
data class ChatSummary(
    var chatId: String = "",
    var otherUid: String = "",
    var lastMessage: String = "",
    var lastSenderId: String = "",
    var timestamp: Long = 0,
    var unread: Long = 0,                       // messages this user hasn't opened yet
)

@IgnoreExtraProperties
data class Message(
    var id: String = "",
    var senderId: String = "",
    var type: String = MsgType.TEXT,
    var text: String = "",
    var mediaId: String = "",                   // image messages
    var postId: String = "",                    // shared posts
    var replyToId: String = "",                 // the message this one replies to
    var replyToText: String = "",
    var replyToSenderId: String = "",
    var edited: Boolean = false,
    var deleted: Boolean = false,
    var timestamp: Long = 0,
    var reactions: Map<String, String> = emptyMap(), // uid -> "love"
)

@IgnoreExtraProperties
data class Call(
    var callId: String = "",
    var callerId: String = "",
    var calleeId: String = "",
    var channel: String = "",                   // Agora channel name both phones join
    var type: String = CallType.VOICE,
    var status: String = CallStatus.RINGING,
    var timestamp: Long = 0,
)

@IgnoreExtraProperties
data class CallLog(
    var callId: String = "",
    var otherUid: String = "",
    var type: String = CallType.VOICE,
    var outgoing: Boolean = false,
    var status: String = CallStatus.ENDED,
    var startedAt: Long = 0,
    var durationSec: Long = 0,
)

@IgnoreExtraProperties
data class Listing(
    var id: String = "",
    var title: String = "",
    var price: String = "",                     // "Rs 45,000"
    var place: String = "",
    var photo: String = "",                     // placeholder name: "sage", "rose" ...
    var justListed: Boolean = false,
    var timestamp: Long = 0,
)

/** The six reactions: database key, picture, name and the colour of "Like" when chosen. */
enum class Reaction(
    val key: String,
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    @ColorRes val color: Int,
) {
    LIKE("like", R.drawable.reaction_like, R.string.reaction_like, R.color.teal),
    LOVE("love", R.drawable.reaction_love, R.string.reaction_love, R.color.love_red),
    HAHA("haha", R.drawable.reaction_haha, R.string.reaction_haha, R.color.mustard),
    WOW("wow", R.drawable.reaction_wow, R.string.reaction_wow, R.color.mustard),
    SAD("sad", R.drawable.reaction_sad, R.string.reaction_sad, R.color.mustard),
    ANGRY("angry", R.drawable.reaction_angry, R.string.reaction_angry, R.color.accent_orange);

    companion object {
        /** The reaction stored as [key], or null for an unknown or missing key. */
        fun of(key: String?): Reaction? = entries.firstOrNull { it.key == key }
    }
}

/** Kinds of rows on the Notifications screen (18). */
object NotifType {
    const val FRIEND_REQUEST = "friend_request"
    const val FRIEND_ACCEPTED = "friend_accepted"
    const val POST_REACTION = "post_reaction"
    const val COMMENT = "comment"
    const val REPLY = "reply"
    const val COMMENT_LIKE = "comment_like"
    const val STORY_REACTION = "story_reaction"
}

object MsgType {
    const val TEXT = "text"
    const val IMAGE = "image"
    const val POST = "post"
    const val SYSTEM = "system"     // "Aisha took a screenshot"
}

object MediaType {
    const val IMAGE = "image"
    const val VIDEO = "video"
}

object Audience {
    const val PUBLIC = "public"
    const val CLOSE_FRIENDS = "close"
}

object CallType {
    const val VOICE = "voice"
    const val VIDEO = "video"
}

object CallStatus {
    const val RINGING = "ringing"
    const val ACCEPTED = "accepted"
    const val DECLINED = "declined"
    const val ENDED = "ended"
    const val MISSED = "missed"
}

/** How the Photo picker (08) was opened. */
object PickMode {
    const val POST = "post"             // several images for a post
    const val SINGLE_IMAGE = "single"   // one image: profile picture, cover, comment, chat
    const val STORY = "story"           // one image or one short video
}

/** Keys for the extras passed between screens with Intent.putExtra. */
object Extra {
    const val UID = "uid"                   // whose profile / chat
    const val POST_ID = "post_id"
    const val CHAT_ID = "chat_id"
    const val PICK_MODE = "pick_mode"
    const val PICKED_URIS = "picked_uris"   // result of the Photo picker
    const val MEDIA_URI = "media_uri"       // photo or video going into the story editor
    const val MEDIA_TYPE = "media_type"
    const val FIRST_TIME = "first_time"     // Edit profile opened right after sign up
    const val CALL_ID = "call_id"
    const val CHANNEL = "channel"
    const val CALL_TYPE = "call_type"
    const val OUTGOING = "outgoing"
}
