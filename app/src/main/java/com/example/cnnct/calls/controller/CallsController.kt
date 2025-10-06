// File: app/src/main/java/com/example/cnnct/calls/controller/CallsController.kt
package com.example.cnnct.calls.controller

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.cnnct.agora.AgoraManager
import com.example.cnnct.calls.InCallActivity
import com.example.cnnct.calls.model.CallDoc
import com.example.cnnct.calls.repository.CallRepository
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*

private const val AGORA_APP_ID = "3678d2cf11ad47579391de324b308fcd"
private const val AGORA_TOKEN_URL = "https://get-agora-token-840694397310.europe-west1.run.app"

class CallsController(
    private val context: Context,
    val repo: CallRepository = CallRepository()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _incomingCall = MutableStateFlow<CallDoc?>(null)
    val incomingCall: StateFlow<CallDoc?> = _incomingCall

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var incomingWatcher: ListenerRegistration? = null
    private var currentCallId: String? = null
    private var ringTimeoutJob: Job? = null

    init {
        Log.d("CallsController", "Init: AgoraManager.init()")
        AgoraManager.init(context, AGORA_APP_ID)
    }

    fun startCall(
        calleeId: String,
        onCreated: (callId: String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        scope.launch {
            try {
                val channelId = UUID.randomUUID().toString()
                val callId = repo.createCall(calleeId, channelId)
                currentCallId = callId

                Log.d("CallsController", "startCall() callId=$callId channelId=$channelId")

                val intent = Intent(context, InCallActivity::class.java).apply {
                    putExtra("callId", callId)
                    putExtra("callerId", FirebaseAuth.getInstance().currentUser?.uid ?: "")
                    putExtra("channelId", channelId) // consistency
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.d("CallsController", "startCall(): launched InCallActivity")

                repo.listenToCall(callId) { callDoc ->
                    Log.d("CallsController", "listenToCall update=${callDoc?.status}")
                    callDoc?.let { handleCallUpdate(it) }
                }

                startRingTimeout(callId)
                onCreated(callId)
            } catch (t: Throwable) {
                Log.e("CallsController", "startCall failed: ${t.message}", t)
                onError(t)
            }
        }
    }

    fun acceptCall(callId: String) {
        scope.launch {
            Log.d("CallsController", "acceptCall() callId=$callId")
            repo.updateCallStatus(callId, "in-progress", startedAt = Timestamp.now())
            cancelRingTimeout()
        }
    }

    fun rejectCall(callId: String) {
        scope.launch {
            Log.d("CallsController", "rejectCall() callId=$callId")
            repo.updateCallStatus(callId, "rejected", endedAt = Timestamp.now(), endedReason = "rejected")
            cancelRingTimeout()
        }
    }

    fun endCall(callId: String) {
        scope.launch {
            Log.d("CallsController", "endCall() callId=$callId")
            val call = repo.getCall(callId) ?: return@launch
            val endedAt = Timestamp.now()
            val duration = if (call.startedAt != null) (endedAt.seconds - call.startedAt.seconds) else 0L
            repo.updateCallStatus(callId, "ended", endedAt = endedAt, duration = duration, endedReason = "hangup")
            AgoraManager.leaveChannel()
        }
    }

    private fun startRingTimeout(callId: String) {
        Log.d("CallsController", "startRingTimeout() for callId=$callId")
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(30_000)
            val call = repo.getCall(callId) ?: return@launch
            if (call.status == "ringing") {
                Log.d("CallsController", "Ring timeout → marking missed")
                repo.updateCallStatus(callId, "missed", endedAt = Timestamp.now(), endedReason = "timeout")
            }
        }
    }

    private fun cancelRingTimeout() {
        Log.d("CallsController", "cancelRingTimeout()")
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
    }

    suspend fun requestAgoraToken(channelId: String, uid: Int = 0): TokenResponse =
        withContext(Dispatchers.IO) {
            Log.d("CallsController", "requestAgoraToken() channelId=$channelId uid=$uid")
            val idToken = auth.currentUser?.getIdToken(true)?.await()?.token
                ?: throw IllegalStateException("No idToken")

            val jsonPayload = """{"channelId":"$channelId","uidInt":$uid}"""
            val body = jsonPayload.toRequestBody("application/json".toMediaType())
            val client = OkHttpClient()

            val request = Request.Builder()
                .url(AGORA_TOKEN_URL)
                .post(body)
                .addHeader("Authorization", "Bearer $idToken")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val responseBody = response.body?.string()
                Log.d("AgoraToken", "HTTP $code | body=$responseBody")

                if (!response.isSuccessful) throw IllegalStateException("Token request failed: $code $responseBody")
                if (responseBody.isNullOrEmpty()) throw IllegalStateException("Empty token response")

                val json = org.json.JSONObject(responseBody)
                return@withContext TokenResponse(
                    token = json.optString("token"),
                    channelId = json.optString("channelId"),
                    uid = json.optInt("uid", 0)
                )
            }
        }

    data class TokenResponse(val token: String?, val channelId: String?, val uid: Int?)

    fun clear() {
        Log.d("CallsController", "clear()")
        stopIncomingWatcher()
        scope.cancel()
        // Do NOT destroy the Agora engine here. InCallActivity needs it.
        // AgoraManager.destroy() should be called on full app shutdown or logout.
    }

    fun startIncomingWatcher() {
        if (incomingWatcher != null) return
        val uid = auth.currentUser?.uid ?: return

        Log.d("CallsController", "startIncomingWatcher() for uid=$uid")

        val q = db.collection("calls")
            .whereEqualTo("calleeId", uid)
            .whereEqualTo("status", "ringing")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1)

        incomingWatcher = q.addSnapshotListener { snap, err ->
            if (err != null) {
                Log.e("CallsController", "Incoming watcher error: ${err.message}")
                return@addSnapshotListener
            }
            if (snap == null || snap.isEmpty) {
                Log.d("CallsController", "Incoming watcher: no ringing calls")
                _incomingCall.value = null
                return@addSnapshotListener
            }
            val doc = snap.documents.firstOrNull()
            Log.d("CallsController", "Incoming watcher got new call=$doc")
            _incomingCall.value = doc?.toObject(CallDoc::class.java)
        }
    }

    fun stopIncomingWatcher() {
        Log.d("CallsController", "stopIncomingWatcher()")
        incomingWatcher?.remove()
        incomingWatcher = null
        _incomingCall.value = null
    }

    private fun handleCallUpdate(call: CallDoc) {
        Log.d("CallsController", "handleCallUpdate() status=${call.status}")
        when (call.status) {
            // Joining is handled by InCallActivity to avoid double joins and UI races.
            "rejected", "missed", "ended" -> {
                Log.d("CallsController", "Call ended reason=${call.endedReason}")
                AgoraManager.leaveChannel()
                currentCallId = null
                cancelRingTimeout()
            }
        }
    }
}
