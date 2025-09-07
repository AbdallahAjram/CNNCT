package com.example.cnnct.calls.view

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.calls.model.UserCallLog
import com.google.firebase.auth.FirebaseAuth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.ui.graphics.Color
import com.google.firebase.Timestamp
import kotlinx.coroutines.launch

@Composable
fun CallsScreen(controller: CallsController, modifier: Modifier = Modifier) {
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    val calls = remember { mutableStateListOf<UserCallLog>() }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val repo = controller // controller has the repo internally but for UI we'll re-subscribe
        // Use repo.fetchUserCallLogs (callsRepository) to supply the list. For simplicity we'll call through controller -> repo omitted; instead just instantiate repo
        val callRepo = com.example.cnnct.calls.repository.CallRepository()
        callRepo.fetchUserCallLogs(uid) { list ->
            calls.clear()
            calls.addAll(list)
        }
        onDispose { }
    }

    Box(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (calls.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(64.dp))
                Text("No calls yet", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn {
                items(calls.size) { idx ->
                    CallRow(calls[idx], onClick = {
                        // redial: call controller.startCall with peerId
                        scope.launch {
                            controller.startCall(calls[idx].peerId, onCreated = { callId ->
                                // optional: navigate to in-call screen automatically
                            }, onError = { /* show error */ })
                        }
                    })
                }
            }
        }

        // Observe incoming call state (full-screen overlay)
        val incoming by controller.incomingCall.collectAsState()
        incoming?.let { call ->
            if (call.status == "ringing" && call.calleeId == uid) {
                IncomingCallScreen(callerId = call.callerId, onAccept = {
                    controller.acceptCall(call.callId)
                }, onReject = {
                    controller.rejectCall(call.callId)
                })
            } else if (call.status == "in-progress" || call.status == "accepted") {
                InCallScreen(onEnd = { controller.endCall(call.callId) }, onToggleMute = { muted ->
                    com.example.cnnct.agora.AgoraManager.muteLocalAudio(muted)
                })
            }
        }
    }
}
