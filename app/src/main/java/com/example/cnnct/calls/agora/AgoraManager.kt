package com.example.cnnct.agora

import android.content.Context
import io.agora.rtc2.*

object AgoraManager {
    private var rtcEngine: RtcEngine? = null
    private var joined = false

    fun init(context: Context, appId: String) {
        if (rtcEngine != null) return

        val config = RtcEngineConfig()
        config.mContext = context.applicationContext
        config.mAppId = appId
        config.mEventHandler = object : IRtcEngineEventHandler() {
            override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {}
            override fun onUserJoined(uid: Int, elapsed: Int) {}
            override fun onUserOffline(uid: Int, reason: Int) {}
        }

        rtcEngine = RtcEngine.create(config)

        // Set the channel profile after creation
        rtcEngine?.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION)
    }

    fun joinChannel(token: String?, channelId: String, uid: Int = 0) {
        rtcEngine ?: throw IllegalStateException("Agora not initialized")
        if (joined) return
        rtcEngine?.enableAudio()
        rtcEngine?.joinChannel(token, channelId, "", uid)
        joined = true
    }

    fun leaveChannel() {
        rtcEngine?.leaveChannel()
        joined = false
    }

    fun muteLocalAudio(mute: Boolean) {
        rtcEngine?.muteLocalAudioStream(mute)
    }

    fun destroy() {
        try {
            rtcEngine?.leaveChannel()
            RtcEngine.destroy()
        } finally {
            rtcEngine = null
            joined = false
        }
    }
}
