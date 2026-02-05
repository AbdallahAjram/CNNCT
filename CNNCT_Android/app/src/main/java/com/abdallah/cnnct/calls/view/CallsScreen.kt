package com.abdallah.cnnct.calls.view

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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abdallah.cnnct.calls.viewmodel.CallsViewModel
import com.google.firebase.auth.FirebaseAuth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    viewModel: CallsViewModel = viewModel(factory = CallsViewModel.Factory),
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsState()
    val logs = state.logs
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""

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
            if (logs.isEmpty()) {
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
                    items(logs.size, key = { logs[it].log.callId }) { i ->
                        val logUi = logs[i]
                        CallRow(
                            log = logUi.log,
                            onClick = {
                                viewModel.startCall(
                                    calleeId = logUi.log.peerId,
                                    onSuccess = { callId, channelId ->
                                        val intent = android.content.Intent(context, InCallActivity::class.java).apply {
                                            putExtra("callId", callId)
                                            putExtra("callerId", uid)
                                            putExtra("channelId", channelId)
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    },
                                    onError = { /* Handle error */ }
                                )
                            },
                            peerName = logUi.peerName,
                            peerPhotoUrl = logUi.peerPhotoUrl
                        )
                    }
                }
            }

            // Inline Incoming Call Handling (from ViewModel state)
            val incoming = state.incomingCall
            incoming?.let { call ->
                val myUid = uid
                val peerId = if (call.callerId == myUid) call.calleeId else call.callerId
                val name = state.incomingCallerName ?: "Unknown"
                val photo = state.incomingCallerPhoto

                when {
                    call.status == "ringing" && call.calleeId == myUid -> {
                        IncomingCallScreen(
                            callerId = call.callerId,
                            callerName = name,
                            callerPhotoUrl = photo,
                            onAccept = { viewModel.acceptCall(call.callId) }, 
                            onReject = { viewModel.rejectCall(call.callId) }
                        )
                    }
                    call.status == "in-progress" || call.status == "accepted" -> {
                         // Fallback logic if InCallActivity hasn't taken over yet?
                        InCallScreen(
                            callerName = name,
                            callerPhone = null,
                            callerPhotoUrl = photo,
                            initialElapsedSeconds = 0L,
                            callStatus = call.status,
                            onEnd = { viewModel.endCall(call.callId) },
                            onToggleMute = { muted ->
                                com.abdallah.cnnct.agora.AgoraManager.muteLocalAudio(muted)
                            }
                        )
                    }
                }
            }
        }
    }
}
