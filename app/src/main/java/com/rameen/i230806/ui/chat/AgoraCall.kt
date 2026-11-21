package com.rameen.i230806.ui.chat

import android.app.Activity
import android.util.Log
import android.view.SurfaceView
import android.widget.FrameLayout
import com.rameen.i230806.R
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas

/**
 * The real audio / video of a call, with the Agora SDK. Both phones join the same Agora
 * "channel" (its name is the callId) and Agora's servers connect them.
 *
 * The App ID is in res/values/agora.xml. The project has an App Certificate, so joining needs a
 * token. Assignment 3: our server makes it (backend/lib/agora.php) and sends it with the call,
 * so the certificate never ships inside the app.
 *
 *   val agora = AgoraCall(this, video = true, onRemoteJoined = { uid -> ... }, onRemoteLeft = { ... })
 *   agora.join(channel, token)   // after the microphone (and camera) permission is granted
 *   agora.leave()                // when the call ends
 *
 * Agora calls the event handler on its own thread, so every callback is moved to the main thread.
 */
class AgoraCall(
    private val activity: Activity,
    private val video: Boolean,
    private val onRemoteJoined: (Int) -> Unit,
    private val onRemoteLeft: () -> Unit,
    private val onError: () -> Unit,
) {
    private var engine: RtcEngine? = null

    private val events = object : IRtcEngineEventHandler() {
        override fun onUserJoined(uid: Int, elapsed: Int) {
            activity.runOnUiThread { onRemoteJoined(uid) }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            activity.runOnUiThread { onRemoteLeft() }
        }

        override fun onError(err: Int) {
            Log.w(TAG, "Agora error $err")
        }
    }

    /** The App ID was filled in (not the placeholder). */
    fun hasAppId(): Boolean = !appId().startsWith("PASTE")

    private fun appId(): String = activity.getString(R.string.agora_app_id).trim()

    /** Creates the engine and joins [channel] with the microphone (and camera for video). */
    fun join(channel: String, token: String?) {
        try {
            val config = RtcEngineConfig().apply {
                mContext = activity.applicationContext
                mAppId = appId()
                mEventHandler = events
            }
            val rtc = RtcEngine.create(config)
            engine = rtc
            rtc.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION)
            // Voice calls start on the earpiece, video calls on the loudspeaker.
            rtc.setDefaultAudioRoutetoSpeakerphone(video)
            if (video) rtc.enableVideo()

            val options = ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                publishMicrophoneTrack = true
                publishCameraTrack = video
                autoSubscribeAudio = true
                autoSubscribeVideo = video
            }
            // Token from our server (null if the server has no certificate: testing mode).
            // uid 0: Agora picks one.
            val result = rtc.joinChannel(token, channel, 0, options)
            if (result != 0) onError()
        } catch (e: Exception) {
            Log.w(TAG, "Agora could not start: ${e.message}")
            onError()
        }
    }

    /** Shows my own camera inside [container]. */
    fun showLocalVideo(container: FrameLayout) {
        val rtc = engine ?: return
        val surface = SurfaceView(activity)
        surface.setZOrderMediaOverlay(true)      // drawn above the other person's video
        container.removeAllViews()
        container.addView(surface)
        rtc.setupLocalVideo(VideoCanvas(surface, VideoCanvas.RENDER_MODE_HIDDEN, 0))
        rtc.startPreview()
    }

    /** Shows the other person's camera ([uid] from onRemoteJoined) inside [container]. */
    fun showRemoteVideo(container: FrameLayout, uid: Int) {
        val rtc = engine ?: return
        val surface = SurfaceView(activity)
        container.removeAllViews()
        container.addView(surface)
        rtc.setupRemoteVideo(VideoCanvas(surface, VideoCanvas.RENDER_MODE_HIDDEN, uid))
    }

    fun setMuted(muted: Boolean) {
        engine?.muteLocalAudioStream(muted)
    }

    fun setSpeaker(on: Boolean) {
        engine?.setEnableSpeakerphone(on)
    }

    fun setCameraOff(off: Boolean) {
        engine?.muteLocalVideoStream(off)
        if (off) engine?.stopPreview() else engine?.startPreview()
    }

    fun switchCamera() {
        engine?.switchCamera()
    }

    /** Leaves the channel and frees the engine. Safe to call twice. */
    fun leave() {
        val rtc = engine ?: return
        engine = null
        if (video) rtc.stopPreview()
        rtc.leaveChannel()
        RtcEngine.destroy()
    }

    private companion object {
        const val TAG = "AgoraCall"
    }
}