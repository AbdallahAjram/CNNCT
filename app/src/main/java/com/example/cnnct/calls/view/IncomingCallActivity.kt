package com.example.cnnct.calls

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.calls.view.IncomingCallScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class IncomingCallActivity : ComponentActivity() {
    private lateinit var callsController: CallsController
    private var callId: String? = null
    private var callerId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        callsController = CallsController(this)

        callId = intent.getStringExtra("callId")
        callerId = intent.getStringExtra("callerId")

        // Start the incoming watcher so CallsController will populate incomingCall flow.
        callsController.startIncomingWatcher()

        setContent {
            IncomingCallScreen(
                callerId = callerId ?: "Unknown caller",
                onAccept = { handleAccept() },
                onReject = { handleReject() }
            )
        }

        // Observe incoming call updates and react (accepted / in-progress / ended)
        lifecycleScope.launch {
            callsController.incomingCall.collectLatest { callDoc ->
                if (callDoc == null) return@collectLatest
                // If this activity was opened for a specific callId, only react to that call
                if (callId != null && callDoc.callId != callId) return@collectLatest

                when (callDoc.status) {
                    "accepted" -> {
                        // controller will handle Agora join; wait for "in-progress" then open in-call UI
                    }
                    "in-progress" -> {
                        // Launch the in-call activity
                        val i = Intent(this@IncomingCallActivity, InCallActivity::class.java).apply {
                            putExtra("callId", callDoc.callId)
                            putExtra("callerId", callDoc.callerId)
                        }
                        startActivity(i)
                        finish()
                    }
                    "rejected", "missed", "ended" -> {
                        // Call was declined / missed / ended - close incoming UI
                        finish()
                    }
                }
            }
        }
    }

    private fun handleAccept() {
        val id = callId
        if (id == null) {
            finish()
            return
        }
        callsController.acceptCall(id)
    }

    private fun handleReject() {
        val id = callId
        if (id == null) {
            finish()
            return
        }
        callsController.rejectCall(id)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        callsController.stopIncomingWatcher()
        callsController.clear()
    }
}
