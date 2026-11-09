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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
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
 *  - Outgoing (Extra.OUTGOING = true, Extra.UID): from the Chat header. Rings the other
 *    person (CallRepo.start), shows "Calling...", gives up after 45 seconds ("No answer").
 *  - Answered (Extra.OUTGOING = false, Extra.UID = caller, Extra.CALL_ID): from the
 *    Incoming call screen, which already set the call to "accepted".
 *
 * Then both phones join the same Agora channel (AgoraCall). When the other person's audio
 * arrives, the timer starts. The call ends when either side hangs up (the node in calls/ is
 * removed), the callee declines, Agora loses the other phone, or nobody answers.
 * When it ends, the caller adds "Voice call · 03:12" or "Missed voice call" to the chat,
 * and each side writes its own call log.
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
            if (!outgoing) loadIncomingCall { CallRepo.end(it) }
            toast(if (callType == CallType.VIDEO) R.string.call_need_camera else R.string.call_need_mic)
            finish()
            return
        }
        if (!agora.hasAppId()) {
            if (!outgoing) loadIncomingCall { CallRepo.end(it) }
            toast(R.string.call_no_app_id)
            finish()
            return
        }

        if (outgoing) {
            showStatus(getString(R.string.call_calling))
            CallRepo.start(this, otherUid, callType) { started ->
                if (isDestroyed) return@start
                if (started == null) {
                    toast(getString(R.string.call_busy, otherName))
                    finish()
                } else {
                    begin(started)
                }
            }
        } else {
            showStatus(getString(R.string.call_connecting))
            loadIncomingCall { begin(it) }
        }
    }

    /** The call I answered: calls/{me}, checked against Extra.CALL_ID. */
    private fun loadIncomingCall(onLoaded: (Call) -> Unit) {
        val callId = intent.getStringExtra(Extra.CALL_ID).orEmpty()
        Db.calls(myUid).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val incoming = snapshot.getValue(Call::class.java)
                if (incoming == null || incoming.callId != callId) {
                    if (!isFinishing) {
                        toast(R.string.call_ended)
                        finish()
                    }
                } else {
                    onLoaded(incoming)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if (!isFinishing) finish()
            }
        })
    }

    /** Joins the Agora channel and follows the call in the database. */
    private fun begin(started: Call) {
        call = started
        agora.join(started.channel)
        onJoined()
        if (outgoing) handler.postDelayed(noAnswer, RING_TIMEOUT_MS)

        watch(Db.calls(started.calleeId)) { snapshot ->
            if (finished) return@watch
            val now = snapshot.getValue(Call::class.java)
            when {
                // Removed (or replaced by a newer call): the other side hung up.
                now == null || now.callId != started.callId ->
                    endCall(CallStatus.ENDED, R.string.call_ended, removeNode = false)
                now.status == CallStatus.DECLINED -> endCall(CallStatus.DECLINED, R.string.call_declined)
                now.status == CallStatus.ACCEPTED && !connected -> {
                    handler.removeCallbacks(noAnswer)
                    showStatus(getString(R.string.call_connecting))
                }
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
     * Ends the call once: stops Agora, removes calls/{callee} (unless the other side already
     * did), writes my call log, and (caller only) the grey line in the chat.
     */
    private fun endCall(status: String, @StringRes message: Int?, removeNode: Boolean = true) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)

        val ended = call
        if (ended != null) {
            if (removeNode) CallRepo.end(ended)
            val seconds = if (connected) (System.currentTimeMillis() - connectedAt) / 1000 else 0
            CallRepo.log(ended, if (connected) CallStatus.ENDED else status, ended.timestamp, seconds)
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
    }
}