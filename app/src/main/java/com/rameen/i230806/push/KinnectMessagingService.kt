package com.rameen.i230806.push

import android.Manifest
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rameen.i230806.KinnectApp
import com.rameen.i230806.R
import com.rameen.i230806.data.CallType
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
import com.rameen.i230806.ui.chat.ChatActivity
import com.rameen.i230806.ui.chat.IncomingCallActivity
import com.rameen.i230806.ui.home.HomeActivity

/**
 * Receives the pushes sent by PushSender (registered in AndroidManifest.xml).
 *
 * Pushes carry only "data" (no "notification" part), so this runs every time, whether the app
 * is open, in the background or closed, and builds the notification itself:
 *
 *  - "message": "Aisha Khan · See you at 5" on the Messages channel. Tapping opens that chat
 *    (with Home behind it, so Back works). Skipped while that same chat is on screen.
 *  - "call": app open -> the Incoming call screen opens straight away;
 *    app in the background -> a ringing notification on the Calls channel that opens it.
 *
 * Every push names who it is for (toUid). If someone else is logged in on this phone now,
 * the push is ignored, so a logged-out account never gets notifications here.
 */
class KinnectMessagingService : FirebaseMessagingService() {

    /** Firebase gave this phone a new token: save it so pushes keep reaching us. */
    override fun onNewToken(token: String) {
        val me = Db.uid ?: return
        Db.user(me).child("fcmToken").setValue(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["toUid"] != Db.uid) return      // not for the account logged in here
        when (data["type"]) {
            PushSender.TYPE_MESSAGE -> showMessage(data)
            PushSender.TYPE_CALL -> showCall(data)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Messages
    // ---------------------------------------------------------------------------------------

    private fun showMessage(data: Map<String, String>) {
        val chatId = data["chatId"].orEmpty()
        if (chatId.isEmpty() || chatId == ChatActivity.openChatId) return   // already reading it

        // Home underneath the chat, so Back from the chat lands in the app, not outside it.
        val home = Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chat = Intent(this, ChatActivity::class.java).putExtra(Extra.CHAT_ID, chatId)
        val open = PendingIntent.getActivities(
            this, chatId.hashCode(), arrayOf(home, chat),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = data["body"].orEmpty()
        val notification = NotificationCompat.Builder(this, KinnectApp.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_messenger)
            .setColor(ContextCompat.getColor(this, R.color.teal))
            .setContentTitle(data["title"].orEmpty())
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One notification per chat: a newer message replaces the older one.
        notify(chatId.hashCode(), notification)
    }

    // ---------------------------------------------------------------------------------------
    // Calls
    // ---------------------------------------------------------------------------------------

    private fun showCall(data: Map<String, String>) {
        val callId = data["callId"].orEmpty()
        if (callId.isEmpty()) return
        val callType = data["callType"] ?: CallType.VOICE
        val incoming = Intent(this, IncomingCallActivity::class.java)
            .putExtra(Extra.CALL_ID, callId)
            .putExtra(Extra.UID, data["fromUid"].orEmpty())
            .putExtra(Extra.CALL_TYPE, callType)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        // App on screen: show the Incoming call screen right away.
        if (appIsInForeground()) {
            startActivity(incoming)
            return
        }

        // App in the background: Android doesn't let us open a screen, so the phone shows a
        // ringing notification instead; tapping it opens the Incoming call screen.
        val open = PendingIntent.getActivity(
            this, callId.hashCode(), incoming,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, KinnectApp.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_phone)
            .setColor(ContextCompat.getColor(this, R.color.teal))
            .setContentTitle(data["title"].orEmpty())
            .setContentText(
                getString(if (callType == CallType.VIDEO) R.string.notif_call_video else R.string.notif_call_voice)
            )
            .setSubText(getString(R.string.notif_call_tap))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(RING_TIMEOUT_MS)    // a call nobody answers stops ringing
            .build()
        // IncomingCallActivity removes it with the same id (callId.hashCode()).
        notify(callId.hashCode(), notification)
    }

    /** True when one of our screens is visible right now. */
    private fun appIsInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** Android 13+ shows nothing unless the user allowed notifications (asked on Chats). */
    private fun notify(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(this).notify(id, notification)
    }

    private companion object {
        const val RING_TIMEOUT_MS = 45_000L
    }
}