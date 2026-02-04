package com.example.cnnct.calls.repository

import android.util.Log
import com.example.cnnct.BuildConfig
import com.example.cnnct.calls.model.CallDoc
import com.example.cnnct.calls.model.UserCallLog
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val AGORA_TOKEN_URL = "https://get-agora-token-840694397310.europe-west1.run.app"

data class TokenResponse(val token: String?, val channelId: String?, val uid: Int?)

class CallRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val callsCol = db.collection("calls")
    private val userCallsRoot = db.collection("userCalls") // userCalls/{uid}/calls/{callId}

    private fun currentUid(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")

    suspend fun createCall(calleeId: String, channelId: String): String {
        val callId = callsCol.document().id
        val call = CallDoc(
            callId = callId,
            callerId = currentUid(),
            calleeId = calleeId,
            channelId = channelId,
            status = "ringing",
            createdAt = Timestamp.now()
        )
        try {
            callsCol.document(callId).set(call).await()
        } catch (e: Exception) {
            throw e
        }
        return callId
    }

    suspend fun updateCallStatus(
        callId: String,
        status: String,
        startedAt: Timestamp? = null,
        endedAt: Timestamp? = null,
        duration: Long? = null,
        endedReason: String? = null
    ) {
        val data = mutableMapOf<String, Any>(
            "status" to status,
            "updatedAt" to Timestamp.now()
        )
        startedAt?.let { data["startedAt"] = it }
        endedAt?.let { data["endedAt"] = it }
        duration?.let { data["duration"] = it }
        endedReason?.let { data["endedReason"] = it }

        callsCol.document(callId).update(data).await()
    }

    suspend fun getCall(callId: String): CallDoc? {
        return try {
            val snap = callsCol.document(callId).get().await()
            snap?.toObject(CallDoc::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Flow-based listener for a single call doc.
     */
    fun callFlow(callId: String): Flow<CallDoc?> = callbackFlow {
        val reg = callsCol.document(callId).addSnapshotListener { snap, e ->
            if (e != null) {
                trySend(null)
                return@addSnapshotListener
            }
            if (snap != null && snap.exists()) {
                trySend(snap.toObject(CallDoc::class.java))
            } else {
                trySend(null)
            }
        }
        awaitClose { reg.remove() }
    }

    /**
     * Flow-based listener for user call logs.
     */
    fun userCallLogsFlow(uid: String): Flow<List<UserCallLog>> = callbackFlow {
        val reg = userCallsRoot.document(uid)
            .collection("calls")
            .orderBy("startedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snaps, err ->
                if (err != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snaps?.documents?.mapNotNull { it.toObject(UserCallLog::class.java) }
                    ?: emptyList()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    /**
     * Flow-based listener for incoming ringing calls for ME.
     */
    fun incomingCallFlow(): Flow<CallDoc?> = callbackFlow {
        val me = auth.currentUser?.uid
        if (me == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val reg = callsCol
            .whereEqualTo("calleeId", me)
            .whereEqualTo("status", "ringing")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                val doc = snap?.documents?.firstOrNull()?.toObject(CallDoc::class.java)
                trySend(doc)
            }
        awaitClose { reg.remove() }
    }

    /**
     * Complex flow that merges caller and callee logs to mimic CallsScreen behavior.
     */
    fun createCallLogsFlow(uid: String): Flow<List<UserCallLog>> = callbackFlow {
        var callerDocs: List<CallDoc>? = null
        var calleeDocs: List<CallDoc>? = null

        fun emitCombined() {
            val all = mutableListOf<UserCallLog>()
            
            fun mapToLog(doc: CallDoc): UserCallLog {
                val outgoing = doc.callerId == uid
                val peerId = if (outgoing) doc.calleeId else doc.callerId
                val direction = if (outgoing) "outgoing" else "incoming"
                
                // UI Status Mapping
                val uiStatus = when (doc.status) {
                    "missed" -> "missed"
                    "rejected" -> "rejected"
                    // "ended", "in-progress", "accepted" -> "answered"
                    else -> if (doc.startedAt != null) "answered" else doc.status
                }
                
                val duration = doc.duration ?: if (doc.startedAt != null && doc.endedAt != null) 
                    (doc.endedAt.seconds - doc.startedAt.seconds).coerceAtLeast(0) 
                 else null

                return UserCallLog(
                    callId = doc.callId,
                    peerId = peerId,
                    direction = direction,
                    status = uiStatus, // Using mapped status or doc status? 
                                       // CallsScreen mapped it to "answered"/"missed".
                                       // Let's keep it simple or align with CallsScreen logic.
                    startedAt = doc.startedAt,
                    endedAt = doc.endedAt ?: doc.createdAt,
                    duration = duration
                )
            }

            callerDocs?.forEach { all.add(mapToLog(it)) }
            calleeDocs?.forEach { all.add(mapToLog(it)) }

            val sorted = all.distinctBy { it.callId }
                .sortedWith(
                   compareByDescending<UserCallLog> { it.endedAt?.seconds ?: 0L }
                       .thenByDescending { it.startedAt?.seconds ?: 0L }
                )
            
            trySend(sorted)
        }

        val r1 = callsCol
            .whereEqualTo("callerId", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    android.util.Log.e("CallRepo", "Caller logs error", e)
                    return@addSnapshotListener
                }
                callerDocs = snap?.toObjects(CallDoc::class.java) ?: emptyList()
                emitCombined()
            }

        val r2 = callsCol
            .whereEqualTo("calleeId", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    android.util.Log.e("CallRepo", "Callee logs error", e)
                    return@addSnapshotListener
                }
                calleeDocs = snap?.toObjects(CallDoc::class.java) ?: emptyList()
                emitCombined()
            }

        awaitClose { 
            r1.remove()
            r2.remove()
        }
    }

    /**
     * Request Agora Token from Cloud Function/Server.
     */
    suspend fun requestAgoraToken(channelId: String, uid: Int = 0): TokenResponse =
        withContext(Dispatchers.IO) {
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
}
