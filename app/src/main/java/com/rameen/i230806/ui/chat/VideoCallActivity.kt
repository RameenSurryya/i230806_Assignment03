package com.rameen.i230806.ui.chat

import android.os.Bundle
import android.view.View
import com.rameen.i230806.R
import com.rameen.i230806.data.CallType
import com.rameen.i230806.data.User
import com.rameen.i230806.databinding.ActivityVideoCallBinding
import com.rameen.i230806.util.Avatars

/**
 * Video call (not in the design; same look as the voice call 22). The calling logic is shared
 * with the voice call (CallActivityBase). This screen adds the cameras:
 *  - my camera in the small corner frame as soon as the call starts
 *  - their camera full screen when they join (their avatar hides then)
 *  - buttons: flip front / back camera, camera off / on, mute, hang up
 */
class VideoCallActivity : CallActivityBase() {

    override val callType = CallType.VIDEO

    private lateinit var binding: ActivityVideoCallBinding
    private var muted = false
    private var cameraOff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Cut my camera to the frame's rounded corners (XML cannot do this).
        binding.flLocalVideo.clipToOutline = true

        binding.ivEndCall.setOnClickListener { hangUp() }
        binding.ivFlip.setOnClickListener { agora.switchCamera() }
        binding.ivMute.setOnClickListener {
            muted = !muted
            agora.setMuted(muted)
            showToggle(binding.ivMute, muted)
            binding.ivMute.contentDescription = getString(if (muted) R.string.cd_unmute else R.string.cd_mute)
        }
        binding.ivCamera.setOnClickListener {
            cameraOff = !cameraOff
            agora.setCameraOff(cameraOff)
            showToggle(binding.ivCamera, cameraOff)
            binding.flLocalVideo.visibility = if (cameraOff) View.INVISIBLE else View.VISIBLE
            binding.ivCamera.contentDescription = getString(if (cameraOff) R.string.cd_camera_on else R.string.cd_camera_off)
        }
        startCall()
    }

    override fun onJoined() = agora.showLocalVideo(binding.flLocalVideo)

    override fun onRemoteJoined(uid: Int) {
        agora.showRemoteVideo(binding.flRemoteVideo, uid)
        binding.flCallAvatar.visibility = View.GONE
    }

    override fun showPerson(user: User) {
        binding.tvCallName.text = user.name
        Avatars.bind(binding.tvCallAvatar, binding.ivCallAvatarPhoto, user)
    }

    override fun showStatus(text: String) {
        binding.tvCallStatus.text = text
    }
}