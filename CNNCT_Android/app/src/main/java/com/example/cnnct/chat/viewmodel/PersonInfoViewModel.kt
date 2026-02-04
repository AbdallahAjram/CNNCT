package com.example.cnnct.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cnnct.settings.model.UserProfile
import com.example.cnnct.chat.core.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class PersonInfoUiState(
    val loading: Boolean = true,
    val profile: UserProfile? = null,
    val error: String? = null
)

class PersonInfoViewModel(
    private val uid: String,
    private val userRepo: UserRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PersonInfoUiState())
    val state = _state.asStateFlow()

    private val _errorEvents = kotlinx.coroutines.channels.Channel<String>()
    val errorEvents = _errorEvents.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                val profile = userRepo.getUser(uid)
                _state.value = PersonInfoUiState(loading = false, profile = profile)
            } catch (e: Exception) {
                _state.value = PersonInfoUiState(loading = false, error = e.message)
            }
        }
    }

    fun block() = viewModelScope.launch {
        try {
            userRepo.blockPeer(uid)
        } catch (e: Exception) {
            _errorEvents.send(e.message ?: "Failed to block user")
        }
    }
    
    suspend fun getOrCreatePrivateChatId(): String {
        return userRepo.getOrCreatePrivateChatWith(uid)
    }

    fun reportUser(reason: String) = viewModelScope.launch {
        try {
            userRepo.reportUser(userRepo.me(), uid, reason)
            _errorEvents.send("Report submitted. We will review this account.")
        } catch (e: Exception) {
            _errorEvents.send("Failed to submit report: ${e.message}")
        }
    }

    companion object {
        fun factory(uid: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PersonInfoViewModel(uid, UserRepository())
            }
        }
    }
}
