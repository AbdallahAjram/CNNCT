package com.example.cnnct.calls.view

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.cnnct.R
import com.example.cnnct.agora.AgoraManager
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.example.cnnct.calls.ActiveCallService
import com.example.cnnct.calls.view.InCallScreen
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

import com.example.cnnct.BuildConfig

class InCallActivity : ComponentActivity() {

    private val viewModel: com.example.cnnct.calls.viewmodel.InCallViewModel by viewModels {
        com.example.cnnct.calls.viewmodel.InCallViewModel.Factory
    }

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgoraManager.init(this, BuildConfig.AGORA_APP_ID) 

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            initUiThenBind()
        } else {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Microphone Permission")
                    .setMessage("This app requires microphone access to make calls. Please grant the permission to proceed.")
                    .setPositiveButton("OK") { _, _ ->
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
                    }
                    .setNegativeButton("Cancel") { _, _ -> finish() }
                    .show()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initUiThenBind()
            } else {
                Toast.makeText(this, "Microphone permission required for calls", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun initUiThenBind() {
        // Start Foreground Service to keep call alive
        val callId = intent.getStringExtra("callId") ?: "unknown"
        val serviceIntent = android.content.Intent(this, ActiveCallService::class.java).apply {
            putExtra("callId", callId)
            // Peer name might not be known yet, service defaults to "Connected".
            // We could update notification later if needed, but for now this ensures background survival.
            putExtra("peerName", "Caller") 
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            val state by viewModel.uiState.collectAsState()
            
            // Side effect: Handle ended/rejected
            LaunchedEffect(state.callStatus) {
                Log.d("InCallActivity", "Status changed to ${state.callStatus}")
                if (state.callStatus in listOf("ended", "rejected", "missed")) {
                    stopRingbackTone()
                    // Delay slightly to show "Call Ended" UI?
                    // Original code didn't delay much.
                    if (!isFinishing) {
                         kotlinx.coroutines.delay(1000)
                         endCallAndFinish()
                    }
                }
            }
            
            // Ringback logic
            val myUid = FirebaseAuth.getInstance().currentUser?.uid
            LaunchedEffect(state.call) {
                val call = state.call
                if (call != null) {
                    if (call.status == "ringing" && call.callerId == myUid) {
                        startRingbackTone()
                    } else {
                        stopRingbackTone()
                    }
                }
            }

            InCallScreen(
                callerName = state.peerName,
                callerPhone = state.peerPhone,
                callerPhotoUrl = state.peerPhotoUrl,
                initialElapsedSeconds = 0L,
                callStatus = state.callStatus,
                onEnd = { viewModel.endCall() },
                onToggleMute = { muted -> viewModel.toggleMute(muted) }

            )
            
            // Link Service Notification to Peer Name
            LaunchedEffect(state.peerName) {
                if (state.peerName != "Unknown") {
                     val updateIntent = android.content.Intent(this@InCallActivity, ActiveCallService::class.java).apply {
                        putExtra("callId", callId)
                        putExtra("peerName", state.peerName)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(updateIntent)
                    } else {
                        startService(updateIntent)
                    }
                }
            }
        }
    }
    
    // loadPeerInfoIntoUi REMOVED -> Handled by InCallViewModel

    private fun startRingbackTone() {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, R.raw.outgoing)
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        }
    }

    private fun stopRingbackTone() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun endCallAndFinish() {
        stopRingbackTone()
        stopService(android.content.Intent(this, ActiveCallService::class.java))
        
        // Activity finish happens. ViewModel handles Agora leave in onCleared.
        // But we want to ensure we leave channel explicitly if needed before destroy?
        // VM onCleared handles it.
        try { AgoraManager.leaveChannel() } catch (_: Throwable) {} // Redundant safety
        if (!isFinishing) finish() 
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRingbackTone()
        stopService(android.content.Intent(this, ActiveCallService::class.java))
        try { AgoraManager.leaveChannel() } catch (_: Throwable) {}
        // VM handles logic.
    }
}
