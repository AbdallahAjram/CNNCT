package com.example.cnnct.calls.repository

import android.util.Log
import com.example.cnnct.calls.model.CallDoc
import com.example.cnnct.calls.model.UserCallLog
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

class CallRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val callsCol = db.collection("calls")
    private val userCallsRoot = db.collection("userCalls") // userCalls/{uid}/calls/{callId}

    private fun currentUid(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")

    /**
     * Create a call doc with a **client-side timestamp**.
     * Safer if your Firestore rules require a concrete timestamp for `createdAt`.
     */
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
            Log.d("CallRepo", "createCall SUCCESS: $callId")
        } catch (e: Exception) {
            Log.e("CallRepo", "createCall FAILED for $callId", e)
            throw e
        }
        return callId
    }

    /**
     * Create a call doc with a **server timestamp**.
     * Requires Firestore rules to allow `FieldValue.serverTimestamp()`.
     */
    suspend fun createCallDoc(calleeId: String, channelId: String): String {
        val callId = db.collection("calls").document().id
        val callerUid = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")
        val call = mapOf(
            "callId" to callId,
            "callerId" to callerUid,
            "calleeId" to calleeId,
            "channelId" to channelId,
            "status" to "ringing",
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            db.collection("calls").document(callId).set(call).await()
            Log.d("CallRepo", "createCallDoc SUCCESS: $callId")
        } catch (e: Exception) {
            Log.e("CallRepo", "createCallDoc FAILED for $callId", e)
            throw e
        }
        return callId
    }

    /**
     * Update call status and optional metadata.
     */
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

        try {
            callsCol.document(callId).update(data).await()
            Log.d("CallRepo", "updateCallStatus SUCCESS: $callId -> $status")
        } catch (e: Exception) {
            Log.e("CallRepo", "updateCallStatus FAILED for $callId", e)
            throw e
        }
    }

    /**
     * Write call logs for the **current user only**.
     * Cloud Functions should be used to write logs for the other peer.
     */
    suspend fun writeUserCallLogsForEnd(callId: String, callDoc: CallDoc) {
        val me = currentUid()
        val (peerId, direction) = if (me == callDoc.callerId) {
            callDoc.calleeId to "outgoing"
        } else {
            callDoc.callerId to "incoming"
        }

        val statusForLog = if (callDoc.status == "ended") "completed" else callDoc.status

        val log = UserCallLog(
            callId = callId,
            peerId = peerId,
            direction = direction,
            status = statusForLog,
            startedAt = callDoc.startedAt,
            endedAt = callDoc.endedAt,
            duration = callDoc.duration
        )

        try {
            userCallsRoot.document(me)
                .collection("calls")
                .document(callId)
                .set(log)
                .await()
            Log.d("CallRepo", "writeUserCallLogsForEnd SUCCESS: $callId for $me")
        } catch (e: Exception) {
            Log.e("CallRepo", "writeUserCallLogsForEnd FAILED for $callId for $me", e)
            throw e
        }
    }

    /**
     * Listen to changes on a specific call document.
     */
    fun listenToCall(callId: String, callback: (CallDoc?) -> Unit): ListenerRegistration {
        return callsCol.document(callId).addSnapshotListener { snap, e ->
            if (e != null) {
                Log.e("CallRepo", "listenToCall ERROR for $callId", e)
                callback(null)
                return@addSnapshotListener
            }
            if (snap != null && snap.exists()) {
                val cd = snap.toObject(CallDoc::class.java)
                callback(cd)
            } else callback(null)
        }
    }

    /**
     * Fetch a user's call logs in descending order of start time.
     */
    fun fetchUserCallLogs(uid: String, onUpdate: (List<UserCallLog>) -> Unit): ListenerRegistration {
        return userCallsRoot.document(uid)
            .collection("calls")
            .orderBy("startedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snaps, err ->
                if (err != null) {
                    Log.e("CallRepo", "fetchUserCallLogs ERROR for $uid", err)
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }
                val list = snaps?.documents?.mapNotNull { it.toObject(UserCallLog::class.java) }
                    ?: emptyList()
                onUpdate(list)
            }
    }

    /**
     * Get a call doc once.
     */
    suspend fun getCall(callId: String): CallDoc? {
        return try {
            val snap = callsCol.document(callId).get().await()
            snap?.toObject(CallDoc::class.java)
        } catch (e: Exception) {
            Log.e("CallRepo", "getCall FAILED for $callId", e)
            null
        }
    }

    /**
     * Watch for incoming ringing calls directed at the current user.
     */
    fun watchIncomingCallsForMe(onUpdate: (CallDoc?) -> Unit): ListenerRegistration {
        val me = auth.currentUser?.uid ?: return object : ListenerRegistration {
            override fun remove() {}
        }

        return callsCol
            .whereEqualTo("calleeId", me)
            .whereEqualTo("status", "ringing")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    Log.e("CallRepo", "watchIncomingCallsForMe ERROR for $me", e)
                    onUpdate(null)
                    return@addSnapshotListener
                }
                val doc = snap?.documents?.firstOrNull()?.toObject(CallDoc::class.java)
                onUpdate(doc)
            }
    }
}
