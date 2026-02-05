package com.abdallah.cnnct.calls.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abdallah.cnnct.calls.model.CallDoc
import com.abdallah.cnnct.calls.model.UserCallLog
import com.abdallah.cnnct.calls.repository.CallRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class CallLogUi(
    val log: UserCallLog,
    val peerName: String = "Unknown",
    val peerPhotoUrl: String? = null
)

data class CallsUiState(
    val logs: List<CallLogUi> = emptyList(),
    val incomingCall: CallDoc? = null,
    val incomingCallerName: String? = null,
    val incomingCallerPhoto: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

class CallsViewModel(
    private val repo: CallRepository,
    private val userRepo: com.abdallah.cnnct.chat.core.repository.UserRepository,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) : ViewModel() {

    // Helper to get current UID
    private val currentUid: String
        get() = auth.currentUser?.uid ?: ""

    // UI State
    private val _uiState = MutableStateFlow(CallsUiState())
    val uiState: StateFlow<CallsUiState> = _uiState.asStateFlow()

    init {
        // Start observing if user is logged in
        if (currentUid.isNotEmpty()) {
            observeLogs()
            observeIncoming()
        }
    }

    private fun observeLogs() {
        viewModelScope.launch {
            repo.createCallLogsFlow(currentUid).collect { rawLogs ->
                // Batch fetch profiles
                val peerIds = rawLogs.map { it.peerId }.distinct()
                val profiles = try {
                    userRepo.getUsers(peerIds).associateBy { it.uid }
                } catch (e: Exception) {
                    emptyMap()
                }

                val uiLogs = rawLogs.map { log ->
                    val profile = profiles[log.peerId]
                    CallLogUi(
                        log = log,
                        peerName = profile?.displayName?.ifBlank { "Unknown" } ?: "Unknown",
                        peerPhotoUrl = profile?.photoUrl
                    )
                }
                
                _uiState.update { it.copy(logs = uiLogs) }
            }
        }
    }

    private fun observeIncoming() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                repo.incomingCallFlow(),
                userRepo.listenBlockedPeers()
            ) { call, blockedIds ->
                if (call != null && blockedIds.contains(call.callerId)) {
                    null // Filter out blocked
                } else {
                    call
                }
            }.collect { call ->
                if (call == null) {
                    _uiState.update { it.copy(incomingCall = null, incomingCallerName = null, incomingCallerPhoto = null) }
                } else {
                    val peerId = if (call.callerId == currentUid) call.calleeId else call.callerId
                    val profile = try {
                        userRepo.getUser(peerId ?: "")
                    } catch (e: Exception) { null }
                    
                    _uiState.update { 
                        it.copy(
                            incomingCall = call,
                            incomingCallerName = profile?.displayName?.ifBlank { "Unknown" } ?: "Unknown",
                            incomingCallerPhoto = profile?.photoUrl
                        ) 
                    }
                }
            }
        }
    }

    fun startCall(
        calleeId: String,
        onSuccess: (callId: String, channelId: String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                val channelId = UUID.randomUUID().toString()
                val callId = repo.createCall(calleeId, channelId)
                
                // We don't join channel or store "activeCallId" here. 
                // That happens in InCallActivity via InCallViewModel.
                onSuccess(callId, channelId)
            } catch (e: Exception) {
                onError(e.message ?: "Failed to start call")
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun acceptCall(callId: String) {
        viewModelScope.launch {
            // Optimistic update handled by Repo -> Flow
            repo.updateCallStatus(callId, "in-progress", startedAt = com.google.firebase.Timestamp.now())
        }
    }

    fun rejectCall(callId: String) {
        viewModelScope.launch {
            repo.updateCallStatus(callId, "rejected", endedAt = com.google.firebase.Timestamp.now(), endedReason = "rejected")
        }
    }

    fun endCall(callId: String) {
        viewModelScope.launch {
            val call = repo.getCall(callId)
            val endedAt = com.google.firebase.Timestamp.now()
            val duration = if (call?.startedAt != null) (endedAt.seconds - call.startedAt.seconds) else 0L
            repo.updateCallStatus(callId, "ended", endedAt = endedAt, duration = duration, endedReason = "hangup")
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                CallsViewModel(
                    CallRepository(db),
                    com.abdallah.cnnct.chat.core.repository.UserRepository(db)
                )
            }
        }
    }
}
