// app/src/main/java/com/example/cnnct/calls/IncomingCallActivity.kt
package com.example.cnnct.calls

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.calls.repository.CallRepository
import com.example.cnnct.calls.view.IncomingCallScreen
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class IncomingCallActivity : ComponentActivity() {
    private lateinit var callsController: CallsController
    private lateinit var repo: CallRepository

    private var callId: String? = null
    private var callerId: String? = null
    private var lastStatus: String? = null

    // Guard so we only navigate once even if multiple signals arrive
    private var navigated: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("IncomingCallActivity", "onCreate()")

        callsController = CallsController(this)
        repo = CallRepository()

        callId = intent.getStringExtra("callId")
        callerId = intent.getStringExtra("callerId")
        Log.d("IncomingCallActivity", "callId=$callId callerId=$callerId")

        callsController.startIncomingWatcher()

        setContent {
            // Compose states for caller profile
            var callerName by remember { mutableStateOf<String?>(null) }
            var callerPhotoUrl by remember { mutableStateOf<String?>(null) }

            // Fetch profile once per callerId
            LaunchedEffect(callerId) {
                val uid = callerId
                if (!uid.isNullOrBlank()) {
                    try {
                        val snap = FirebaseFirestore.getInstance()
                            .collection("users")
                            .document(uid)
                            .get()
                            .await()
                        callerName = snap.getString("displayName") ?: snap.getString("name")
                        callerPhotoUrl = snap.getString("photoUrl")
                        Log.d("IncomingCallActivity", "Loaded caller profile name=$callerName photo=$callerPhotoUrl")
                    } catch (e: Exception) {
                        Log.e("IncomingCallActivity", "Profile fetch failed: ${e.message}")
                    }
                }
            }

            IncomingCallScreen(
                callerId = callerId ?: "Unknown caller",
                callerName = callerName,
                callerPhotoUrl = callerPhotoUrl,
                onAccept = { handleAccept() },
                onReject = { handleReject() }
            )
        }

        // Listen to call status and route once
        lifecycleScope.launch {
            val id = callId ?: return@launch
            repo.listenToCall(id) { callDoc ->
                if (callDoc == null) return@listenToCall
                Log.d("IncomingCallActivity", "listenToCall status=${callDoc.status}")

                if (callDoc.status == lastStatus) return@listenToCall
                lastStatus = callDoc.status

                // If we already navigated due to another signal, ignore further updates
                if (navigated) return@listenToCall

                when (callDoc.status) {
                    "in-progress" -> {
                        navigated = true
                        // close ringing notification in tray if present
                        NotificationManagerCompat.from(this@IncomingCallActivity)
                            .cancel(PushService.CALL_NOTIF_ID)

                        Log.d("IncomingCallActivity", "Launching InCallActivity (status=in-progress)")
                        val i = Intent(this@IncomingCallActivity, InCallActivity::class.java).apply {
                            putExtra("callId", callDoc.callId)
                            putExtra("callerId", callDoc.callerId)
                            putExtra("channelId", callDoc.channelId)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(i)
                        finish()
                    }
                    "rejected", "missed", "ended" -> {
                        navigated = true
                        NotificationManagerCompat.from(this@IncomingCallActivity)
                            .cancel(PushService.CALL_NOTIF_ID)
                        Log.d("IncomingCallActivity", "Call finished with ${callDoc.status}, finishing")
                        finish()
                    }
                }
            }
        }
    }

    private fun handleAccept() {
        Log.d("IncomingCallActivity", "handleAccept()")
        // Only update Firestore. Listener will navigate once status flips.
        callId?.let { id -> callsController.acceptCall(id) }
    }

    private fun handleReject() {
        Log.d("IncomingCallActivity", "handleReject()")
        // Only update Firestore. Listener will close the screen when status flips.
        callId?.let { id -> callsController.rejectCall(id) }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("IncomingCallActivity", "onDestroy()")
        callsController.stopIncomingWatcher()
        // Do not call callsController.clear() here; InCallActivity may still need resources.
    }
}
