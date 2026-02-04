package com.example.cnnct.calls.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cnnct.agora.AgoraManager
import com.example.cnnct.calls.model.CallDoc
import com.example.cnnct.calls.repository.CallRepository
import com.google.firebase.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InCallUiState(
    val call: CallDoc? = null,
    val isMuted: Boolean = false,
    val callStatus: String = "connecting", // UI facing status
    val error: String? = null,
    
    // Peer Profile
    val peerName: String = "Unknown",
    val peerPhone: String? = null,
    val peerPhotoUrl: String? = null
)

class InCallViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repo: CallRepository,
    private val userRepo: com.example.cnnct.chat.core.repository.UserRepository
) : ViewModel() {

    private val callId: String = checkNotNull(savedStateHandle["callId"])
    // Optional: could pass 'callerId' to determine if we are the caller for timeout logic
    private val initialCallerId: String? = savedStateHandle["callerId"]

    private val _uiState = MutableStateFlow(InCallUiState())
    val uiState: StateFlow<InCallUiState> = _uiState.asStateFlow()

    private var ringTimeoutJob: Job? = null
    private var joinAttempted = false

    init {
        observeCall()
        // If we are the caller, we might want to start timeout.
        // But we need to check the call doc first to know if we are the caller.
    }

    private var currentPeerId: String? = null

    private fun observeCall() {
        viewModelScope.launch {
            repo.callFlow(callId).collect { doc ->
                Log.d("InCallViewModel", "Observed callId=$callId doc.status=${doc?.status} caller=${doc?.callerId}")
                if (doc == null) return@collect
                
                _uiState.update { it.copy(call = doc, callStatus = doc.status) }

                // Peer Info
                val myUid = userRepo.me()
                val peerId = if (doc.callerId == myUid) doc.calleeId else doc.callerId
                
                if (peerId != null && peerId != currentPeerId) {
                    currentPeerId = peerId
                    fetchProfile(peerId)
                }

                // Ringing Timeout Logic (if I am the caller)
                if (doc.status == "ringing" && doc.callerId == initialCallerId) {
                    startRingTimeout(doc)
                } else {
                    cancelRingTimeout()
                }

                // Auto-Join Logic
                if (doc.status == "in-progress" && !joinAttempted) {
                    joinChannel(doc.channelId)
                }

                // Auto-Leave logic handled by Activity closing on "ended" status via collection
            }
        }
    }
    
    private fun fetchProfile(uid: String) {
        viewModelScope.launch {
            try {
                val profile = userRepo.getUser(uid)
                _uiState.update { 
                    it.copy(
                        peerName = profile.displayName.ifBlank { "Unknown" },
                        peerPhone = profile.phoneNumber,
                        peerPhotoUrl = profile.photoUrl
                    )
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun startRingTimeout(doc: CallDoc) {
        if (ringTimeoutJob != null) return
        ringTimeoutJob = viewModelScope.launch {
            delay(30_000)
            if (_uiState.value.call?.status == "ringing") {
                 Log.d("InCallViewModel", "Ring timeout -> marking missed")
                 repo.updateCallStatus(callId, "missed", endedAt = Timestamp.now(), endedReason = "timeout")
            }
        }
    }

    private fun cancelRingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
    }

    private fun joinChannel(channelId: String) {
        joinAttempted = true
        viewModelScope.launch {
            try {
                // Request token
                val tokenResponse = repo.requestAgoraToken(channelId, 0)
                
                // AgoraManager needs to be initialized by Activity (Context).
                // We assume it is ready.
                // Wait for init not strictly possible here without suspended check on Manager?
                // Using simple delay or assuming Activity did it.
                AgoraManager.joinChannel(
                    token = tokenResponse.token,
                    channelName = channelId,
                    uid = tokenResponse.uid ?: 0
                )
            } catch (e: Exception) {
                Log.e("InCallViewModel", "Join failed", e)
                _uiState.update { it.copy(error = "Connection failed") }
            }
        }
    }

    fun toggleMute(muted: Boolean) {
        AgoraManager.muteLocalAudio(muted)
        _uiState.update { it.copy(isMuted = muted) }
    }

    fun endCall() {
        viewModelScope.launch {
            val call = _uiState.value.call ?: return@launch
            val endedAt = Timestamp.now()
            val duration = if (call.startedAt != null) (endedAt.seconds - call.startedAt.seconds) else 0L
            
            repo.updateCallStatus(
                callId, 
                "ended", 
                endedAt = endedAt, 
                duration = duration, 
                endedReason = "hangup"
            )
            AgoraManager.leaveChannel()
        }
    }

    override fun onCleared() {
        super.onCleared()
        AgoraManager.leaveChannel() 
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val savedStateHandle = createSavedStateHandle()
                InCallViewModel(
                    savedStateHandle,
                    CallRepository(db),
                    com.example.cnnct.chat.core.repository.UserRepository(db)
                )
            }
        }
    }
}
