// app/src/main/java/com/example/cnnct/calls/InCallActivity.kt
package com.example.cnnct.calls

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
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.calls.repository.CallRepository
import com.example.cnnct.calls.view.InCallScreen
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class InCallActivity : ComponentActivity() {
    private lateinit var callsController: CallsController
    private lateinit var repo: CallRepository

    private var callId: String? = null
    private var callerId: String? = null

    // UI state observed by Compose
    private var uiCallerName by mutableStateOf("Unknown")
    private var uiCallerPhone by mutableStateOf<String?>(null)
    private var uiCallerPhotoUrl by mutableStateOf<String?>(null)
    private var uiCallStatus by mutableStateOf("ringing")

    private var mediaPlayer: MediaPlayer? = null
    private var joinAttempted = false

    private val REQUEST_CODE_PERMISSIONS = 101
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("InCallActivity", "onCreate()")

        AgoraManager.init(this, "3678d2cf11ad47579391de324b308fcd")
        callsController = CallsController(this)
        repo = CallRepository()

        callId = intent.getStringExtra("callId")
        callerId = intent.getStringExtra("callerId")
        Log.d("InCallActivity", "extras callId=$callId callerId=$callerId")

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        } else {
            initUiThenBind()
        }
    }

    private fun allPermissionsGranted() =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) initUiThenBind()
            else {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun initUiThenBind() {
        // Render immediately
        setContent {
            InCallScreen(
                callerName = uiCallerName,
                callerPhone = uiCallerPhone,
                callerPhotoUrl = uiCallerPhotoUrl,
                initialElapsedSeconds = 0L,
                callStatus = uiCallStatus,
                onEnd = { endCallAndFinish() },
                onToggleMute = { muted -> AgoraManager.muteLocalAudio(muted) }
            )
        }

        // Resolve call + preload peer profile
        lifecycleScope.launch {
            val id = callId ?: return@launch
            val myUid = FirebaseAuth.getInstance().currentUser?.uid

            val doc = try {
                FirebaseFirestore.getInstance().collection("calls").document(id).get().await()
            } catch (e: Exception) {
                Log.e("InCallActivity", "Failed to load call doc: ${e.message}")
                null
            }

            if (doc != null && doc.exists()) {
                val docCaller = doc.getString("callerId")
                val docCallee = doc.getString("calleeId")
                val status = doc.getString("status") ?: "ringing"
                uiCallStatus = status

                if (callerId.isNullOrBlank()) callerId = docCaller

                val peerUid = when (myUid) {
                    docCaller -> docCallee
                    docCallee -> docCaller
                    else -> docCallee
                }

                // Load peer profile (displayName, phoneNumber, photoUrl with storage fallback)
                peerUid?.let { loadPeerInfoIntoUi(it) }

                // Play ringback only if I am the original caller
                if (myUid != null && myUid == docCaller && status == "ringing") startRingbackTone()
            }

            // Start listening for status changes
            observeCall()
        }
    }

    private fun observeCall() {
        val id = callId ?: return
        lifecycleScope.launch {
            repo.listenToCall(id) { callDoc ->
                if (callDoc == null) return@listenToCall
                Log.d("InCallActivity", "listenToCall status=${callDoc.status}")
                uiCallStatus = callDoc.status

                when (callDoc.status) {
                    "in-progress" -> {
                        stopRingbackTone()
                        if (!joinAttempted) {
                            joinAttempted = true
                            lifecycleScope.launch {
                                try {
                                    val tokenResponse = withContext(Dispatchers.IO) {
                                        callsController.requestAgoraToken(callDoc.channelId, 0)
                                    }
                                    val ready = AgoraManager.waitUntilInitialized(5_000)
                                    if (ready && !AgoraManager.isJoined()) {
                                        AgoraManager.joinChannel(
                                            token = tokenResponse.token,
                                            channelName = callDoc.channelId,
                                            uid = tokenResponse.uid ?: 0
                                        )
                                    }
                                } catch (t: Throwable) {
                                    Log.e("InCallActivity", "joinChannel failed: ${t.message}", t)
                                }
                            }
                        }
                    }
                    "ended", "rejected", "missed" -> {
                        stopRingbackTone()
                        try { AgoraManager.leaveChannel() } catch (_: Throwable) {}
                        finish()
                    }
                }
            }
        }
    }

    private suspend fun loadPeerInfoIntoUi(uid: String) {
        try {
            val db = FirebaseFirestore.getInstance()
            val userDoc = db.collection("users").document(uid).get().await()
            uiCallerName = userDoc.getString("displayName")
                ?: userDoc.getString("name")
                        ?: "Unknown"
            uiCallerPhone = userDoc.getString("phoneNumber")

            // Prefer photoUrl from the user doc, fallback to Storage avatar
            uiCallerPhotoUrl = userDoc.getString("photoUrl") ?: run {
                try {
                    FirebaseStorage.getInstance()
                        .reference
                        .child("avatars/$uid/avatar.jpg")
                        .downloadUrl
                        .await()
                        .toString()
                } catch (_: Exception) { null }
            }

            Log.d("InCallActivity", "Peer loaded name=$uiCallerName phone=$uiCallerPhone photo=$uiCallerPhotoUrl")
        } catch (e: Exception) {
            Log.e("InCallActivity", "loadPeerInfoIntoUi failed: ${e.message}")
            uiCallerName = "Unknown"
        }
    }

    private fun startRingbackTone() {
        stopRingbackTone()
        mediaPlayer = MediaPlayer.create(this, R.raw.outgoing)
        mediaPlayer?.isLooping = true
        mediaPlayer?.start()
    }

    private fun stopRingbackTone() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun endCallAndFinish() {
        stopRingbackTone()
        val id = callId
        if (id != null) {
            lifecycleScope.launch {
                try {
                    // Mark ended via controller; it also leaves channel
                    callsController.endCall(id)
                } catch (_: Exception) {
                } finally {
                    try { AgoraManager.leaveChannel() } catch (_: Throwable) {}
                    finish()
                }
            }
        } else {
            try { AgoraManager.leaveChannel() } catch (_: Throwable) {}
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRingbackTone()
        try { AgoraManager.leaveChannel() } catch (_: Throwable) {}
    }
}
