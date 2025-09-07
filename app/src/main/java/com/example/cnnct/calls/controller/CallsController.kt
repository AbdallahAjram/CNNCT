package com.example.cnnct.calls.controller

import android.content.Context
import com.example.cnnct.agora.AgoraManager
import com.example.cnnct.calls.model.CallDoc
import com.example.cnnct.calls.repository.CallRepository
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import java.util.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import com.squareup.moshi.Moshi

private const val AGORA_APP_ID = "3678d2cf11ad47579391de324b308fcd"
private const val AGORA_TOKEN_URL = "https://get-agora-token-840694397310.europe-west1.run.app"

class CallsController(
    private val context: Context,
    private val repo: CallRepository = CallRepository()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _incomingCall = MutableStateFlow<CallDoc?>(null)
    val incomingCall: StateFlow<CallDoc?> = _incomingCall

    // Firestore + Auth used by the watcher
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // listener registrations
    private var incomingWatcher: ListenerRegistration? = null

    private var currentCallId: String? = null
    private var ringTimeoutJob: Job? = null

    init {
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

                // listen for updates for this call (repo exposes listenToCall)
                repo.listenToCall(callId) { callDoc -> callDoc?.let { handleCallUpdate(it) } }

                startRingTimeout(callId)
                onCreated(callId)
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    private fun handleCallUpdate(call: CallDoc) {
        _incomingCall.value = call
        when (call.status) {
            "accepted" -> {
                scope.launch {
                    try {
                        val tokenResponse = requestAgoraToken(call.channelId)
                        AgoraManager.joinChannel(tokenResponse.token, call.channelId, 0)
                        repo.updateCallStatus(call.callId, "in-progress", startedAt = Timestamp.now())
                    } catch (e: Throwable) {
                        // log and propagate if desired
                        e.printStackTrace()
                    }
                }
            }
            "ended" -> finalizeCall(call)
        }
    }

    fun acceptCall(callId: String) {
        scope.launch {
            val call = repo.getCall(callId) ?: return@launch
            repo.updateCallStatus(callId, "accepted", startedAt = Timestamp.now())
            cancelRingTimeout()
        }
    }

    fun rejectCall(callId: String) {
        scope.launch {
            repo.updateCallStatus(callId, "rejected", endedAt = Timestamp.now(), endedReason = "rejected")
            cancelRingTimeout()
        }
    }

    fun endCall(callId: String) {
        scope.launch {
            val call = repo.getCall(callId) ?: return@launch
            val endedAt = Timestamp.now()
            val duration = if (call.startedAt != null) (endedAt.seconds - call.startedAt.seconds) else 0L
            repo.updateCallStatus(callId, "ended", endedAt = endedAt, duration = duration, endedReason = "hangup")
            AgoraManager.leaveChannel()
        }
    }

    private fun finalizeCall(call: CallDoc) {
        scope.launch {
            try {
                repo.writeUserCallLogsForEnd(call.callId, call)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    private fun startRingTimeout(callId: String) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(30_000)
            val call = repo.getCall(callId) ?: return@launch
            if (call.status == "ringing") {
                repo.updateCallStatus(callId, "missed", endedAt = Timestamp.now(), endedReason = "timeout")
            }
        }
    }

    private fun cancelRingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
    }

    private suspend fun requestAgoraToken(channelId: String): TokenResponse {
        val idToken = auth.currentUser
            ?.getIdToken(false)
            ?.await()
            ?.token ?: throw IllegalStateException("No idToken")

        val url = AGORA_TOKEN_URL
        val payload = mapOf("channelId" to channelId)
        val moshi = Moshi.Builder().build()
        val jsonPayload = moshi.adapter(Map::class.java).toJson(payload)
        val body: RequestBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val client = OkHttpClient()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Authorization", "Bearer $idToken")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Token request failed: ${response.code}")
            val responseBody = response.body?.string() ?: throw IllegalStateException("Empty token response")
            val adapter = moshi.adapter(TokenResponse::class.java)
            return adapter.fromJson(responseBody) ?: throw IllegalStateException("Invalid token response")
        }
    }

    data class TokenResponse(val token: String?, val channelId: String?, val uid: Int?)

    fun clear() {
        stopIncomingWatcher()
        scope.cancel()
        AgoraManager.destroy()
    }

    /**
     * Start watching for incoming calls for the signed-in user.
     * Listens for most recent call doc where calleeId == my uid and status == "ringing".
     * Updates the incomingCall StateFlow so UI can react.
     */
    fun startIncomingWatcher() {
        if (incomingWatcher != null) return

        val uid = auth.currentUser?.uid ?: return

        val q = db.collection("calls")
            .whereEqualTo("calleeId", uid)
            .whereEqualTo("status", "ringing")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1)

        incomingWatcher = q.addSnapshotListener { snap, err ->
            if (err != null) {
                // best-effort: log, and keep state as-is
                err.printStackTrace()
                return@addSnapshotListener
            }

            if (snap == null || snap.isEmpty) {
                _incomingCall.value = null
                return@addSnapshotListener
            }

            val doc = snap.documents.firstOrNull()
            val call = doc?.toObject(CallDoc::class.java)
            _incomingCall.value = call
        }
    }

    /** Stop the incoming watcher and clear incoming call state */
    fun stopIncomingWatcher() {
        incomingWatcher?.remove()
        incomingWatcher = null
        _incomingCall.value = null
    }
}
