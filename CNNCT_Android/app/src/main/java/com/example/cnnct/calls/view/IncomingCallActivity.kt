package com.example.cnnct.calls.view

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.example.cnnct.calls.repository.CallRepository
import com.example.cnnct.calls.view.IncomingCallScreen
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import com.example.cnnct.calls.PushService
import com.example.cnnct.calls.view.InCallActivity

class IncomingCallActivity : ComponentActivity() {
    
    // Use delegation to get ViewModel with Factory
    private val viewModel: com.example.cnnct.calls.viewmodel.IncomingCallViewModel by viewModels {
        com.example.cnnct.calls.viewmodel.IncomingCallViewModel.Factory
    }

    // Call Repository for manual listener if needed, OR use VM state.
    // VM exposes uiState.call. Status changes handled there.
    // But we need to Navigate when status becomes in-progress.
    // Listening to VM state is better.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("IncomingCallActivity", "onCreate()")

        // callId extracted by ViewModel from SavedStateHandle automatically?
        // Note: SavedStateHandle uses Intent extras. 
        // We ensure Intent passed "callId" which is correct.

        setContent {
            // Compose states for caller profile could be in VM?
            // VM: IncomingCallViewModel. Not currently fetching profile.
            // Let's keep profile fetching here for simplicity or move to VM.
            // VM has callDoc.
            
            // To observe side-effects (navigation), we can check state.call.status
            val uiState by viewModel.uiState.collectAsState()
            val callDoc = uiState.call
            
            // Profile Fetching -> Handled by VM
            val callerId = intent.getStringExtra("callerId")

            // Navigation Logic based on Call Status
            LaunchedEffect(callDoc?.status) {
                when (callDoc?.status) {
                    "in-progress" -> {
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
                         NotificationManagerCompat.from(this@IncomingCallActivity)
                            .cancel(PushService.CALL_NOTIF_ID)
                        Log.d("IncomingCallActivity", "Call finished with ${callDoc?.status}, finishing")
                        finish()
                    }
                }
            }

            IncomingCallScreen(
                callerId = callerId ?: "Unknown caller",
                callerName = uiState.callerName,
                callerPhotoUrl = uiState.callerPhotoUrl,
                onAccept = { viewModel.acceptCall() },
                onReject = { viewModel.rejectCall() }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // No manual controller cleanup needed.
    }
}
