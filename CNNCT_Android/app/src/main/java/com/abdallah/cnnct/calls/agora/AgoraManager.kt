package com.abdallah.cnnct.agora

import android.content.Context
import android.media.AudioManager
import android.util.Log
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

object AgoraManager {
    private var rtcEngine: RtcEngine? = null
    private val initialized = AtomicBoolean(false)
    private var joined = false

    fun init(context: Context, appId: String) {
        if (rtcEngine != null) {
            Log.d("AgoraManager", "init() called but already initialized")
            return
        }

        try {
            Log.d("AgoraManager", "Initializing RtcEngine…")
            val config = RtcEngineConfig().apply {
                mContext = context.applicationContext
                mAppId = appId
                mEventHandler = object : IRtcEngineEventHandler() {
                    override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
                        Log.d("AgoraManager", "onJoinChannelSuccess channel=$channel uid=$uid")
                    }
                    override fun onUserJoined(uid: Int, elapsed: Int) {
                        Log.d("AgoraManager", "onUserJoined uid=$uid")
                    }
                    override fun onUserOffline(uid: Int, reason: Int) {
                        Log.d("AgoraManager", "onUserOffline uid=$uid reason=$reason")
                    }
                }
            }

            rtcEngine = RtcEngine.create(config)
            rtcEngine?.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION)
            rtcEngine?.setClientRole(Constants.CLIENT_ROLE_BROADCASTER)
            rtcEngine?.enableAudio()
            rtcEngine?.setDefaultAudioRoutetoSpeakerphone(true)
            rtcEngine?.setEnableSpeakerphone(true)

            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true

            initialized.set(true)
            Log.d("AgoraManager", "RtcEngine initialized OK")
        } catch (t: Throwable) {
            Log.e("AgoraManager", "Failed to init RtcEngine: ${t.message}", t)
            initialized.set(false)
            rtcEngine = null
        }
    }

    fun isInitialized(): Boolean = initialized.get()

    suspend fun waitUntilInitialized(timeoutMs: Long = 5_000): Boolean {
        val start = System.currentTimeMillis()
        while (!initialized.get()) {
            if (System.currentTimeMillis() - start > timeoutMs) return false
            delay(50)
        }
        return true
    }

    fun joinChannel(token: String?, channelName: String, uid: Int = 0) {
        val engine = rtcEngine ?: throw IllegalStateException("Agora not initialized. Call init() first.")
        if (joined) {
            Log.d("AgoraManager", "joinChannel() ignored; already joined")
            return
        }

        Log.d("AgoraManager", "Joining channel=$channelName uid=$uid")
        val options = ChannelMediaOptions().apply {
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            autoSubscribeAudio = true
            publishMicrophoneTrack = true
        }

        engine.joinChannel(token, channelName, uid, options)
        joined = true
    }

    fun leaveChannel() {
        Log.d("AgoraManager", "leaveChannel() called")
        rtcEngine?.leaveChannel()
        joined = false
    }

    fun muteLocalAudio(mute: Boolean) {
        Log.d("AgoraManager", "muteLocalAudio mute=$mute")
        rtcEngine?.muteLocalAudioStream(mute)
    }

    fun destroy() {
        Log.d("AgoraManager", "destroy() called")
        try {
            rtcEngine?.leaveChannel()
            RtcEngine.destroy()
        } finally {
            rtcEngine = null
            joined = false
            initialized.set(false)
        }
    }

    //test
    fun isJoined(): Boolean = joined
}
