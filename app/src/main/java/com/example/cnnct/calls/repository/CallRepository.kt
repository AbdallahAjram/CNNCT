package com.example.cnnct.calls.repository

import com.example.cnnct.calls.model.CallDoc
import com.example.cnnct.calls.model.UserCallLog
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await
import java.util.*

class CallRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val callsCol = db.collection("calls")
    private val userCallsRoot = db.collection("userCalls") // userCalls/{uid}/calls/{callId}

    private fun currentUid(): String = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")

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
        callsCol.document(callId).set(call).await()
        return callId
    }

    suspend fun updateCallStatus(callId: String, status: String, startedAt: Timestamp? = null, endedAt: Timestamp? = null, duration: Long? = null, endedReason: String? = null) {
        val data = mutableMapOf<String, Any>("status" to status, "updatedAt" to Timestamp.now())
        startedAt?.let { data["startedAt"] = it }
        endedAt?.let { data["endedAt"] = it }
        duration?.let { data["duration"] = it }
        endedReason?.let { data["endedReason"] = it }
        callsCol.document(callId).update(data).await()
    }

    suspend fun writeUserCallLogsForEnd(callId: String, callDoc: CallDoc) {
        // Write a single call-log entry for the current signed-in user only.
        // Do NOT attempt to write the other user's userCalls entry from this client
        // — that will be rejected by secure rules. Use a server/Cloud Function if you need
        // to create both sides atomically.

        val me = currentUid() // throws if not signed in

        // Determine which side I am and set direction/peerId accordingly
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

        // write to userCalls/{me}/calls/{callId}
        userCallsRoot.document(me)
            .collection("calls")
            .document(callId)
            .set(log)
            .await()
    }


    fun listenToCall(callId: String, callback: (CallDoc?) -> Unit) {
        callsCol.document(callId).addSnapshotListener { snap, e ->
            if (e != null) {
                callback(null)
                return@addSnapshotListener
            }
            if (snap != null && snap.exists()) {
                val cd = snap.toObject(CallDoc::class.java)
                callback(cd)
            } else callback(null)
        }
    }

    fun fetchUserCallLogs(uid: String, onUpdate: (List<UserCallLog>) -> Unit) {
        userCallsRoot.document(uid)
            .collection("calls")
            .orderBy("startedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snaps, err ->
                if (err != null) {
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }
                val list = snaps?.documents?.mapNotNull { it.toObject(UserCallLog::class.java) } ?: emptyList()
                onUpdate(list)
            }
    }

    suspend fun getCall(callId: String): CallDoc? {
        val snap = callsCol.document(callId).get().await()
        return snap?.toObject(CallDoc::class.java)
    }

    suspend fun createCallDoc(calleeId: String, channelId: String): String {
        val callId = db.collection("calls").document().id
        val call = mapOf(
            "callId" to callId,
            "callerId" to auth.currentUser!!.uid,
            "calleeId" to calleeId,
            "channelId" to channelId,
            "status" to "ringing",
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection("calls").document(callId).set(call).await()
        return callId
    }


    fun watchIncomingCallsForMe(onUpdate: (CallDoc?) -> Unit): ListenerRegistration {
        val me = auth.currentUser?.uid ?: return object: ListenerRegistration {
            override fun remove() {}
        }

        // Listen to ringing calls targeted at me (latest first)
        return callsCol
            .whereEqualTo("calleeId", me)
            .whereEqualTo("status", "ringing")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snap, e ->
                if (e != null) { onUpdate(null); return@addSnapshotListener }
                val doc = snap?.documents?.firstOrNull()?.toObject(CallDoc::class.java)
                onUpdate(doc)
            }
    }


}
