package com.example.cnnct.calls.view

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.calls.model.UserCallLog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    controller: CallsController,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    val uiLogs = remember { mutableStateListOf<UserCallLog>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // ---- lightweight in-memory user cache ----
    data class UserLite(
        val uid: String,
        val displayName: String?,
        val name: String?,
        val phoneNumber: String?,
        val photoUrl: String?
    )

    val db = remember { FirebaseFirestore.getInstance() }
    val userCache = remember { mutableStateMapOf<String, UserLite>() }

    suspend fun fetchUserLite(userId: String): UserLite? = try {
        val snap = db.collection("users").document(userId).get().await()
        if (!snap.exists()) null else UserLite(
            uid = userId,
            displayName = snap.getString("displayName"),
            name = snap.getString("name"),
            phoneNumber = snap.getString("phoneNumber"),
            photoUrl = snap.getString("photoUrl")
        )
    } catch (_: Exception) { null }

    fun displayName(u: UserLite?) =
        when {
            u == null -> "Unknown"
            !u.displayName.isNullOrBlank() -> u.displayName!!
            !u.name.isNullOrBlank() -> u.name!!
            else -> "Unknown"
        }

    fun mapCallDocToLog(doc: DocumentSnapshot, me: String): UserCallLog? {
        if (!doc.exists()) return null
        val callerId = doc.getString("callerId") ?: return null
        val calleeId = doc.getString("calleeId") ?: return null
        val status = doc.getString("status") ?: "ended"
        val startedAt = doc.getTimestamp("startedAt")
        val endedAt = doc.getTimestamp("endedAt")
        val durationFromDoc = doc.getLong("duration")?.toLong()

        val outgoing = callerId == me
        val peerId = if (outgoing) calleeId else callerId
        val direction = if (outgoing) "outgoing" else "incoming"

        val computedDuration = when {
            durationFromDoc != null -> durationFromDoc
            startedAt != null && endedAt != null -> (endedAt.seconds - startedAt.seconds).coerceAtLeast(0)
            else -> null
        }

        val uiStatus = when {
            status == "missed" -> "missed"
            status == "rejected" -> "rejected"
            startedAt != null && (status == "ended" || status == "in-progress" || status == "accepted") -> "answered"
            else -> status
        }

        return UserCallLog(
            callId = doc.id,
            peerId = peerId,
            direction = direction,
            status = uiStatus,
            startedAt = startedAt,
            endedAt = endedAt ?: doc.getTimestamp("createdAt"),
            duration = computedDuration
        )
    }

    // ---- realtime listener for call logs ----
    DisposableEffect(uid) {
        var regCaller: ListenerRegistration? = null
        var regCallee: ListenerRegistration? = null

        fun rebuild(callerDocs: List<DocumentSnapshot>?, calleeDocs: List<DocumentSnapshot>?) {
            val combined = buildList {
                callerDocs?.forEach { mapCallDocToLog(it, uid)?.let { add(it) } }
                calleeDocs?.forEach { mapCallDocToLog(it, uid)?.let { add(it) } }
            }
            val dedup = combined
                .associateBy { it.callId }
                .values
                .sortedWith(
                    compareByDescending<UserCallLog> { it.endedAt?.seconds ?: 0L }
                        .thenByDescending { it.startedAt?.seconds ?: 0L }
                )
            uiLogs.clear()
            uiLogs.addAll(dedup)

            val peers = dedup.map { it.peerId }.toSet().filter { it !in userCache }
            peers.forEach { pid -> scope.launch { fetchUserLite(pid)?.let { userCache[pid] = it } } }
        }

        var latestCaller: List<DocumentSnapshot>? = null
        var latestCallee: List<DocumentSnapshot>? = null

        if (uid.isNotEmpty()) {
            regCaller = db.collection("calls")
                .whereEqualTo("callerId", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(100)
                .addSnapshotListener { snap, err ->
                    if (err != null) return@addSnapshotListener
                    latestCaller = snap?.documents ?: emptyList()
                    rebuild(latestCaller, latestCallee)
                }

            regCallee = db.collection("calls")
                .whereEqualTo("calleeId", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(100)
                .addSnapshotListener { snap, err ->
                    if (err != null) return@addSnapshotListener
                    latestCallee = snap?.documents ?: emptyList()
                    rebuild(latestCaller, latestCallee)
                }
        }

        onDispose {
            regCaller?.remove()
            regCallee?.remove()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Call Logs") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (onBack != null) onBack()
                        else (context as? ComponentActivity)?.finish()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (uiLogs.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No calls yet", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn {
                    items(uiLogs.size, key = { uiLogs[it].callId }) { i ->
                        val log = uiLogs[i]
                        val peer = userCache[log.peerId]
                        CallRow(
                            log = log,
                            onClick = {
                                scope.launch {
                                    controller.startCall(
                                        log.peerId,
                                        onCreated = { },
                                        onError = { }
                                    )
                                }
                            },
                            peerName = displayName(peer),
                            peerPhotoUrl = peer?.photoUrl
                        )
                    }
                }
            }

            val incoming by controller.incomingCall.collectAsState()
            incoming?.let { call ->
                val myUid = uid
                val peerId = if (call.callerId == myUid) call.calleeId else call.callerId

                val cached = userCache[peerId]
                LaunchedEffect(peerId) {
                    if (cached == null) fetchUserLite(peerId)?.let { userCache[peerId] = it }
                }
                val peer = userCache[peerId]
                val name = displayName(peer)
                val phone = peer?.phoneNumber
                val photo = peer?.photoUrl

                when {
                    call.status == "ringing" && call.calleeId == myUid -> {
                        IncomingCallScreen(
                            callerId = call.callerId,
                            callerName = name,
                            callerPhotoUrl = photo,
                            onAccept = { controller.acceptCall(call.callId) },
                            onReject = { controller.rejectCall(call.callId) }
                        )
                    }
                    call.status == "in-progress" || call.status == "accepted" -> {
                        InCallScreen(
                            callerName = name,
                            callerPhone = phone,
                            callerPhotoUrl = photo,
                            initialElapsedSeconds = 0L,
                            callStatus = call.status,
                            onEnd = { controller.endCall(call.callId) },
                            onToggleMute = { muted ->
                                com.example.cnnct.agora.AgoraManager.muteLocalAudio(muted)
                            }
                        )
                    }
                }
            }
        }
    }
}
