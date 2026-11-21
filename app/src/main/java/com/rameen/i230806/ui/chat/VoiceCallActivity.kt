package com.rameen.i230806.ui.chat

import android.os.Bundle
import android.view.View
import com.rameen.i230806.R
import com.rameen.i230806.data.Cached
import com.rameen.i230806.data.CallType
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityVoiceCallBinding
import com.rameen.i230806.util.Avatars

/**
 * 22 Voice call. The calling, answering and ending logic is shared with the video call
 * (CallActivityBase); this screen shows the person and handles its own buttons:
 *  - mute: my microphone off / on (white button while muted)
 *  - speaker: loudspeaker on / off (starts on the earpiece)
 *  - red button / Back: hang up. Transcript is visual only.
 */
class VoiceCallActivity : CallActivityBase() {

    override val callType = CallType.VOICE

    private lateinit var binding: ActivityVoiceCallBinding
    private var muted = false
    private var speaker = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ivEndCall.setOnClickListener { hangUp() }
        binding.ivMute.setOnClickListener {
            muted = !muted
            agora.setMuted(muted)
            showToggle(binding.ivMute, muted)
            binding.ivMute.contentDescription = getString(if (muted) R.string.cd_unmute else R.string.cd_mute)
        }
        binding.ivSpeaker.setOnClickListener {
            speaker = !speaker
            agora.setSpeaker(speaker)
            showToggle(binding.ivSpeaker, speaker)
        }
        startCall()
    }

    override fun showPerson(user: User) {
        binding.tvCallName.text = user.name
        Avatars.bind(binding.tvCallAvatar, binding.ivCallAvatarPhoto, user)
        // The love heart after the name: only for people in my close friends list
        // (users/profile.php answers "close": true for them).
        Cached.get("users/profile.php", mapOf("id" to user.uid)) { json ->
            if (!isDestroyed) binding.ivCallHeart.visibility = if (json.optBoolean("close")) View.VISIBLE else View.GONE
        }
    }

    override fun showStatus(text: String) {
        binding.tvCallStatus.text = text
    }
}