package com.abdallah.cnnct.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.abdallah.cnnct.settings.model.UserProfile
import com.abdallah.cnnct.chat.core.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class PersonInfoUiState(
    val loading: Boolean = true,
    val profile: UserProfile? = null,
    val error: String? = null,
    val isBlocked: Boolean = false
)

class PersonInfoViewModel(
    private val uid: String,
    private val userRepo: UserRepository,
    private val chatRepo: com.cnnct.chat.mvc.model.ChatRepository,
    private val currentUserId: String
) : ViewModel() {

    private val _state = MutableStateFlow(PersonInfoUiState())
    val state = _state.asStateFlow()

    private val _errorEvents = kotlinx.coroutines.channels.Channel<String>()
    val errorEvents = _errorEvents.receiveAsFlow()

    init {
        // Load profile
        viewModelScope.launch {
            try {
                val profile = userRepo.getUser(uid)
                _state.value = _state.value.copy(loading = false, profile = profile)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
        
        // Listen to blocked status
        viewModelScope.launch {
            userRepo.listenBlockedPeers().collect { blockedSet ->
                _state.value = _state.value.copy(isBlocked = blockedSet.contains(uid))
            }
        }
    }

    fun block() = viewModelScope.launch {
        try {
            // 1. Block in user list
            userRepo.blockPeer(uid)
            // 2. Update chat flags (if chat exists or create it to store flag)
            val chatId = chatRepo.ensurePrivateChat(currentUserId, uid)
            chatRepo.setBlockStatus(chatId, currentUserId, uid, true)
        } catch (e: Exception) {
            _errorEvents.send(e.message ?: "Failed to block user")
        }
    }

    fun unblock() = viewModelScope.launch {
        try {
            // 1. Unblock in user list
            userRepo.unblockPeer(uid)
            // 2. Clear chat flags
            val chatId = chatRepo.ensurePrivateChat(currentUserId, uid)
            chatRepo.setBlockStatus(chatId, currentUserId, uid, false)
        } catch (e: Exception) {
            _errorEvents.send(e.message ?: "Failed to unblock user")
        }
    }
    
    suspend fun getOrCreatePrivateChatId(): String {
        return chatRepo.ensurePrivateChat(currentUserId, uid)
    }

    fun reportUser(reason: String) = viewModelScope.launch {
        try {
            userRepo.reportUser(currentUserId, uid, reason) // Use generic helper or direct ID
            _errorEvents.send("Report submitted. We will review this account.")
        } catch (e: Exception) {
            _errorEvents.send("Failed to submit report: ${e.message}")
        }
    }

    companion object {
        fun factory(uid: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val userRepo = UserRepository(db)
                val chatRepo = com.cnnct.chat.mvc.model.FirestoreChatRepository(db)
                val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                return PersonInfoViewModel(uid, userRepo, chatRepo, currentUserId) as T
            }
        }
    }
}
