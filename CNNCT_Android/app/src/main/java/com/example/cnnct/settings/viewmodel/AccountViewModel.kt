package com.example.cnnct.settings.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cnnct.settings.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AccountViewModel(
    private val repo: SettingsRepository
) : ViewModel() {

    // Expose profile flow directly
    val profileFlow = repo.profileFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    init {
        refreshProfile()
    }

    fun refreshProfile() = viewModelScope.launch {
        repo.refreshProfile()
    }

    fun updateDisplayName(name: String) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        try {
            repo.updateDisplayName(name)
            repo.refreshProfile()
        } catch (e: Exception) {
            // Handle error (maybe expose via a separate shared flow or state)
        }
    }

    fun updateAbout(about: String) = viewModelScope.launch {
        try {
            repo.updateAbout(about)
            repo.refreshProfile()
        } catch (e: Exception) {
            // Handle error
        }
    }

    fun uploadAndSaveAvatar(uri: Uri, onSuccess: () -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
        try {
            repo.uploadAndSaveAvatar(uri)
            repo.refreshProfile()
            onSuccess()
        } catch (e: Exception) {
            onError(e.message ?: "Upload failed")
        }
    fun deleteAccount(onSuccess: () -> Unit, onError: (String) -> Unit) = viewModelScope.launch {
        try {
            // 1. Delete DB Data
            repo.deleteUserData()
            
            // 2. Delete Auth User
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            user?.delete()?.await()
            
            onSuccess()
        } catch (e: Exception) {
            if (e is com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException) {
                onError("Please logout and login again to delete your account (Security requirement).")
            } else {
                onError(e.message ?: "Failed to delete account")
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AccountViewModel(SettingsRepository())
            }
        }
    }
}
