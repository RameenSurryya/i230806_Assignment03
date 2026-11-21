package com.rameen.i230806.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.rameen.i230806.R
import com.rameen.i230806.data.Call
import com.rameen.i230806.data.CallRepo
import com.rameen.i230806.data.CallStatus
import com.rameen.i230806.data.CallType
import com.rameen.i230806.data.ChatRepo
import com.rameen.i230806.data.Db
import com.rameen.i230806.data.Extra
import com.rameen.i230806.data.User
import com.rameen.i230806.ui.BaseActivity
import com.rameen.i230806.util.Times

/**
 * Everything Voice call (22) and Video call share; they only add their own views.
 *
 * Opened two ways (extras):
 *  - Outgoing (Extra.OUTGOING = true, Extra.UID): from the Chat header. calls/start.php rings
 *    the other person (the server sends their phone a call push); "Calling..."; gives up after
 *    45 seconds ("No answer").
 *  - Answered (Extra.OUTGOING = false, Extra.UID = caller, Extra.CALL_ID): from the Incoming
 *    call screen; calls/answer.php sets the call to "accepted".
 *
 * Both answers carry the Agora token made by the server; both phones join the same Agora
 * channel (AgoraCall). While the screen shows, calls/status.php is asked every 1.5 seconds:
 * declined, ended or missed closes the call. When the other person's audio arrives, the
 * timer starts. When it ends, the caller adds "Voice call · 03:12" or "Missed voice call" to
 * the chat. The server's calls table is the call log (start, answer and end times).
 */
abstract class CallActivityBase : BaseActivity() {
    override val darkSystemBars = true // white status bar icons on the teal / black screen

    /** CallType.VOICE or CallType.VIDEO. */
    protected abstract val callType: String

    /** Name and avatar of the other person, once loaded. */
    protected abstract fun showPerson(user: User)

    /** "Calling...", "Connecting...", then the timer "03:12". */
    protected abstract fun showStatus(text: String)

    /** Video call: show my camera once the engine has started. */
    protected open fun onJoined() {}

    /** Video call: show their camera when they arrive. */
    protected open fun onRemoteJoined(uid: Int) {}

    protected lateinit var agora: AgoraCall
    protected lateinit var otherUid: String
    private var otherName = ""
    private var outgoing = false
    private var call: Call? = null

    /** Asks the server for the call's state every 1.5 s while the call lasts. */
    private val poll = object : Runnable {
        override fun run() {
            val current = call ?: return
            CallRepo.status(current.callId) { now -> if (now != null) onStatus(now) }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private var connected = false
    private var connectedAt = 0L
    private var finished = false

    private val handler = Handler(Looper.getMainLooper())

    /** Every second while connected: the timer under the name. */
    private val tick = object : Runnable {
        override fun run() {
            showStatus(Times.duration((System.currentTimeMillis() - connectedAt) / 1000))
            handler.postDelayed(this, 1000)
        }
    }

    /** Nobody answered in time. */
    private val noAnswer = Runnable { endCall(CallStatus.MISSED, R.string.call_no_answer) }

    /** Microphone (and camera for video): the answer comes back here. */
    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
            onPermissions(answers.values.all { it })
        }

    // ---------------------------------------------------------------------------------------
    // Start
    // ---------------------------------------------------------------------------------------

    /** Called by the subclass at the end of onCreate, after setContentView. */
    protected fun startCall() {
        otherUid = intent.getStringExtra(Extra.UID).orEmpty()
        outgoing = intent.getBooleanExtra(Extra.OUTGOING, false)
        if (otherUid.isEmpty() || otherUid == myUid) {
            finish()
            return
        }
        Db.getUser(otherUid) { user ->
            if (user != null) {
                otherName = user.name
                showPerson(user)
            }
        }
        agora = AgoraCall(
            activity = this,
            video = callType == CallType.VIDEO,
            onRemoteJoined = { uid -> remoteJoined(uid) },
            onRemoteLeft = { endCall(CallStatus.ENDED, R.string.call_ended) },
            onError = { endCall(CallStatus.MISSED, R.string.call_error) },
        )
        // System Back = hang up (a call screen shouldn't just disappear).
        onBackPressedDispatcher.addCallback(this) { hangUp() }

        if (hasPermissions()) onPermissions(true) else askPermissions.launch(permissions())
    }

    private fun permissions(): Array<String> =
        if (callType == CallType.VIDEO) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        else arrayOf(Manifest.permission.RECORD_AUDIO)

    private fun hasPermissions() = permissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun onPermissions(granted: Boolean) {
        if (!granted) {
            // No microphone: an answered call is ended so the caller isn't left waiting.
            if (!outgoing) endIncoming()
            toast(if (callType == CallType.VIDEO) R.string.call_need_camera else R.string.call_need_mic)
            finish()
            return
        }
        if (!agora.hasAppId()) {
            if (!outgoing) endIncoming()
            toast(R.string.call_no_app_id)
            finish()
            return
        }

        if (outgoing) {
            showStatus(getString(R.string.call_calling))
            CallRepo.start(otherUid, callType) { joined, error ->
                if (isDestroyed) return@start
                when {
                    joined != null -> begin(joined)
                    error == CallRepo.BUSY -> {
                        toast(getString(R.string.call_busy, otherName))
                        finish()
                    }
                    else -> {
                        toast(error ?: getString(R.string.call_error))
                        finish()
                    }
                }
            }
        } else {
            showStatus(getString(R.string.call_connecting))
            CallRepo.answer(intent.getStringExtra(Extra.CALL_ID).orEmpty()) { joined ->
                if (isDestroyed) return@answer
                if (joined == null) {
                    toast(R.string.call_ended)   // the caller hung up before I answered
                    finish()
                } else {
                    begin(joined)
                }
            }
        }
    }

    /** An incoming call I can't take (no permission / no App ID): tell the caller it's over. */
    private fun endIncoming() {
        intent.getStringExtra(Extra.CALL_ID)?.takeIf { it.isNotEmpty() }?.let { CallRepo.end(it) }
    }

    /** Joins the Agora channel with the server's token and starts following the call. */
    private fun begin(joined: CallRepo.Joined) {
        call = joined.call
        agora.join(joined.call.channel, joined.token)
        onJoined()
        if (outgoing) handler.postDelayed(noAnswer, RING_TIMEOUT_MS)
        handler.postDelayed(poll, POLL_MS)
    }

    /** What the server says about the call. */
    private fun onStatus(now: Call) {
        if (finished) return
        when (now.status) {
            CallStatus.DECLINED -> endCall(CallStatus.DECLINED, R.string.call_declined, tellServer = false)
            CallStatus.ENDED, CallStatus.MISSED -> endCall(CallStatus.ENDED, R.string.call_ended, tellServer = false)
            CallStatus.ACCEPTED -> if (!connected) {
                handler.removeCallbacks(noAnswer)
                showStatus(getString(R.string.call_connecting))
            }
        }
    }

    /** The other phone's audio / video arrived: the call is really on. */
    private fun remoteJoined(uid: Int) {
        if (finished) return
        if (!connected) {
            connected = true
            connectedAt = System.currentTimeMillis()
            handler.removeCallbacks(noAnswer)
            handler.post(tick)
        }
        onRemoteJoined(uid)
    }

    // ---------------------------------------------------------------------------------------
    // End
    // ---------------------------------------------------------------------------------------

    /** The red button. */
    protected fun hangUp() = endCall(CallStatus.MISSED, R.string.call_ended)

    /**
     * Ends the call once: stops Agora, tells the server (unless the server told us), and
     * (caller only) adds the grey line to the chat. The server keeps the call log.
     */
    private fun endCall(status: String, @StringRes message: Int?, tellServer: Boolean = true) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)

        val ended = call
        if (ended != null) {
            if (tellServer) CallRepo.end(ended.callId)
            val seconds = if (connected) (System.currentTimeMillis() - connectedAt) / 1000 else 0
            if (outgoing) ChatRepo.sendSystem(this, otherUid, chatLine(seconds))
        }
        if (::agora.isInitialized) agora.leave()
        if (message != null) toast(message)
        finish()
    }

    /** "Voice call · 03:12" or "Missed video call". */
    private fun chatLine(seconds: Long): String {
        val video = callType == CallType.VIDEO
        return if (connected) {
            getString(if (video) R.string.call_msg_video else R.string.call_msg_voice, Times.duration(seconds))
        } else {
            getString(if (video) R.string.call_msg_missed_video else R.string.call_msg_missed_voice)
        }
    }

    override fun onDestroy() {
        // Closed some other way (e.g. swiped away): still end the call properly.
        if (!finished && ::otherUid.isInitialized) endCall(CallStatus.MISSED, null)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Shared look of the round toggle buttons (mute, speaker, camera)
    // ---------------------------------------------------------------------------------------

    /** On: white circle with a teal icon. Off: the translucent circle with a white icon. */
    protected fun showToggle(button: ImageView, on: Boolean) {
        val white = ContextCompat.getColor(this, R.color.white)
        val teal = ContextCompat.getColor(this, R.color.teal)
        button.backgroundTintList = if (on) ColorStateList.valueOf(white) else null
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(if (on) teal else white))
        button.isSelected = on
    }

    private companion object {
        const val RING_TIMEOUT_MS = 45_000L
        const val POLL_MS = 1500L
    }
}