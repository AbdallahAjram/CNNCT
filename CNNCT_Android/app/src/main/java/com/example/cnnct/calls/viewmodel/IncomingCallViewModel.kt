package com.example.cnnct.calls.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cnnct.calls.model.CallDoc
import com.example.cnnct.calls.repository.CallRepository
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IncomingCallUiState(
    val call: CallDoc? = null,
    val callerName: String? = null,
    val callerPhotoUrl: String? = null
)

class IncomingCallViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repo: CallRepository,
    private val userRepo: com.example.cnnct.chat.core.repository.UserRepository
) : ViewModel() {

    private val callId: String = checkNotNull(savedStateHandle["callId"])
    private val callerId: String? = savedStateHandle["callerId"]
    
    // Store fetched callerId to prevent re-fetching
    private var fetchedCallerId: String? = null
    
    private val _uiState = MutableStateFlow(IncomingCallUiState())
    val uiState: StateFlow<IncomingCallUiState> = _uiState.asStateFlow()

    init {
        // Initial Fetch if callerId passed in intent
        if (!callerId.isNullOrBlank()) {
             fetchProfile(callerId)
        }
        observeCall()
    }

    private fun observeCall() {
        viewModelScope.launch {
            repo.callFlow(callId).collect { doc ->
                 if (doc != null) {
                    _uiState.update { it.copy(call = doc) }
                    // Fallback fetch if intent didn't have it or we want fresh info
                    if (doc.callerId != fetchedCallerId) {
                        fetchProfile(doc.callerId)
                    }
                }
            }
        }
    }
    
    private fun fetchProfile(uid: String) {
        if (fetchedCallerId == uid) return
        fetchedCallerId = uid
        viewModelScope.launch {
            try {
                val p = userRepo.getUser(uid)
                _uiState.update { 
                    it.copy(
                        callerName = p.displayName.ifBlank { "Unknown Caller" },
                        callerPhotoUrl = p.photoUrl
                    )
                }
            } catch (_: Exception) {}
        }
    }

    fun acceptCall() {
        viewModelScope.launch {
            repo.updateCallStatus(callId, "in-progress", startedAt = Timestamp.now())
        }
    }

    fun rejectCall() {
        viewModelScope.launch {
            repo.updateCallStatus(callId, "rejected", endedAt = Timestamp.now(), endedReason = "rejected")
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val savedStateHandle = createSavedStateHandle()
                IncomingCallViewModel(
                    savedStateHandle,
                    CallRepository(db),
                    com.example.cnnct.chat.core.repository.UserRepository(db)
                )
            }
        }
    }
}
