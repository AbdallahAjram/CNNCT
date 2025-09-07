package com.example.cnnct.calls

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.calls.view.IncomingCallScreen
import kotlinx.coroutines.launch

class IncomingCallActivity : ComponentActivity() {
    private lateinit var callsController: CallsController
    private var callId: String? = null
    private var callerId: String? = null
    private var channelId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read extras (what you should pass when launching the Activity)
        callId = intent.getStringExtra("callId")
        callerId = intent.getStringExtra("callerId")
        channelId = intent.getStringExtra("channelId")

        // If the activity was launched without a callId, bail out
        if (callId.isNullOrBlank()) {
            finish()
            return
        }

        // Make the Activity show on lockscreen and turn screen on (best-effort)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Init controller
        callsController = CallsController(this)

        setContent {
            IncomingCallScreen(
                callerId = callerId ?: "Unknown",
                onAccept = {
                    // Accept the call: update Firestore state via controller
                    lifecycleScope.launch {
                        try {
                            callId?.let { id -> callsController.acceptCall(id) }
                        } catch (t: Throwable) {
                            // ignore — controller already logs errors; optionally show UI
                        } finally {
                            // Close the incoming UI; the app should navigate to the in-call UI if needed
                            finish()
                        }
                    }
                },
                onReject = {
                    lifecycleScope.launch {
                        try {
                            callId?.let { id -> callsController.rejectCall(id) }
                        } catch (t: Throwable) {
                            // ignore
                        } finally {
                            finish()
                        }
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callsController.clear()
    }
}
