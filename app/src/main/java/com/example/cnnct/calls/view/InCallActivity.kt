package com.example.cnnct.calls

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.example.cnnct.agora.AgoraManager
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.calls.view.InCallScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InCallActivity : ComponentActivity() {
    private lateinit var callsController: CallsController
    private var callId: String? = null
    private var callerId: String? = null
    private var latestCallStatus: String = "ringing"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        callsController = CallsController(this)

        callId = intent.getStringExtra("callId")
        callerId = intent.getStringExtra("callerId")

        setContent {
            InCallScreen(
                callerName = callerId ?: "Caller",
                initialElapsedSeconds = 0L,
                callStatus = latestCallStatus,
                onEnd = { endCallAndFinish() },
                onToggleMute = { muted -> AgoraManager.muteLocalAudio(muted) }
            )
        }

        // Observe call document - update UI or finish if call ends remotely
        lifecycleScope.launch {
            callsController.incomingCall.collectLatest { callDoc ->
                if (callDoc == null) return@collectLatest
                if (callId != null && callDoc.callId != callId) return@collectLatest

                latestCallStatus = callDoc.status

                if (callDoc.status == "ended" || callDoc.status == "rejected" || callDoc.status == "missed") {
                    // Make sure we leave Agora and finish
                    try {
                        AgoraManager.leaveChannel()
                        AgoraManager.destroy()
                    } catch (t: Throwable) {
                        // ignore
                    }
                    finish()
                }
            }
        }
    }

    private fun endCallAndFinish() {
        val id = callId
        if (id != null) {
            callsController.endCall(id)
        } else {
            // still ensure Agora is left
            try {
                AgoraManager.leaveChannel()
                AgoraManager.destroy()
            } catch (t: Throwable) {
                // ignore
            }
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        callsController.clear()
    }
}
