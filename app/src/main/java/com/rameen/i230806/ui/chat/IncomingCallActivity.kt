package com.rameen.i230806.ui.chat

import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.addCallback
import androidx.core.app.NotificationManagerCompat
import com.rameen.i230806.R
import com.rameen.i230806.data.CallRepo
import com.rameen.i230806.data.CallStatus
import com.rameen.i230806.data.CallType
import com.rameen.i230806.data.Db
import com.rameen.i230806.databinding.ActivityIncomingCallBinding
import com.rameen.i230806.data.Extra
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.util.Avatars

/**
 * Incoming call (not in the design; same look as the voice call 22). Opened by
 * KinnectMessagingService with Extra.CALL_ID, Extra.UID (the caller) and Extra.CALL_TYPE.
 *
 *  - Plays the phone's ringtone while it shows.
 *  - Asks calls/status.php every 1.5 s: if the caller cancels or gives up, the screen closes.
 *  - Answer: opens the voice or video call screen, which answers it on the server.
 *  - Decline (or Back): calls/decline.php; the caller's phone shows "Call declined".
 */
class IncomingCallActivity : BaseActivity() {
    override val darkSystemBars = true // white status bar icons on the teal screen

    private lateinit var binding: ActivityIncomingCallBinding
    private lateinit var callId: String
    private lateinit var callerUid: String
    private lateinit var callType: String

    private var answered = false
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            CallRepo.status(callId) { call ->
                if (call != null && call.status != CallStatus.RINGING && !answered && !isFinishing) finish()
            }
            handler.postDelayed(this, POLL_MS)
        }
    }
    private var ringtone: Ringtone? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        callId = intent.getStringExtra(Extra.CALL_ID).orEmpty()
        callerUid = intent.getStringExtra(Extra.UID).orEmpty()
        callType = intent.getStringExtra(Extra.CALL_TYPE) ?: CallType.VOICE
        if (callId.isEmpty() || callerUid.isEmpty()) {
            finish()
            return
        }
        // The ringing notification (if the app was in the background) isn't needed any more.
        NotificationManagerCompat.from(this).cancel(callId.hashCode())

        val video = callType == CallType.VIDEO
        binding.tvCallType.setText(if (video) R.string.call_incoming_video else R.string.call_incoming_voice)
        binding.ivAnswer.setImageResource(if (video) R.drawable.ic_video else R.drawable.ic_phone)
        Db.getUser(callerUid) { user ->
            binding.tvCallName.text = user?.name.orEmpty()
            Avatars.bind(binding.tvCallAvatar, binding.ivCallAvatarPhoto, user)
        }

        binding.ivAnswer.setOnClickListener { answer() }
        binding.ivDecline.setOnClickListener { decline() }
        onBackPressedDispatcher.addCallback(this) { decline() }

        // Still ringing? Closes the screen when the caller hangs up first.
        handler.post(poll)
        startRinging()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ringtone?.stop()
        super.onDestroy()
    }

    private fun startRinging() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            play()
        }
    }

    private fun answer() {
        answered = true
        ringtone?.stop()
        handler.removeCallbacks(poll)
        val screen = if (callType == CallType.VIDEO) VideoCallActivity::class.java else VoiceCallActivity::class.java
        openScreen(screen) {
            putExtra(Extra.UID, callerUid)
            putExtra(Extra.CALL_ID, callId)
            putExtra(Extra.OUTGOING, false)
        }
        finish()
    }

    private fun decline() {
        if (!answered) CallRepo.decline(callId)
        finish()
    }

    private companion object {
        const val POLL_MS = 1500L
    }
}
