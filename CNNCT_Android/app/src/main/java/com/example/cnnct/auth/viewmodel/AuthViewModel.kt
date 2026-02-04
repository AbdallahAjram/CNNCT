package com.example.cnnct.auth.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cnnct.auth.repository.AuthRepository
import com.example.cnnct.notifications.viewmodel.NotificationSettingsViewModel
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Success(val user: FirebaseUser?) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
    object NavigateToHome : AuthUiState()
    object NavigateToCompleteProfile : AuthUiState()
}


@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAuthStateFlow().collect { user ->
                _currentUser.value = user
                if (user != null) {
                    checkProfileCompletion(user.uid)
                }
            }
        }
    }

    private fun checkProfileCompletion(uid: String) {
        viewModelScope.launch {
            try {
                _uiState.value = AuthUiState.Loading
                val isComplete = repository.isProfileComplete(uid)
                if (isComplete) {
                    _uiState.value = AuthUiState.NavigateToHome
                } else {
                    _uiState.value = AuthUiState.NavigateToCompleteProfile
                }
            } catch (e: Exception) {
                // If check fails, maybe network? Default to staying or error?
                // Let's assume stay and let user retry or UI handle it.
                _uiState.value = AuthUiState.Error("Profile check failed: ${e.message}")
            }
        }
    }

    fun signIn(email: String, pass: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            try {
                repository.signInWithEmail(email, pass)
                // Flow listener will pick up user change and trigger checkProfileCompletion
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(e.message ?: "Login failed")
            }
        }
    }

    fun signUp(name: String, displayName: String, email: String, phone: String, pass: String) {
         viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            try {
                repository.signUpWithEmail(name, displayName, email, phone, pass)
                // Flow listener picks up user, triggers checkProfile -> should go to Home (as signup creates profile)
                // Actually signup logic in Repo creates complete profile.
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(e.message ?: "Signup failed")
            }
        }
    }

    fun googleSignIn(idToken: String) {
        viewModelScope.launch {
             _uiState.value = AuthUiState.Loading
            try {
                val isProfileExistingAndComplete = repository.signInWithGoogle(idToken)
                // listener picks up user.
                // but we also know if completion is needed immediately from repo return?
                // repo return logic checks existence.
                // We rely on the init block observer to call checkProfileCompletion which handles the navigation decision.
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(e.message ?: "Google Sign-in failed")
            }
        }
    }
    
    fun completeProfile(name: String, displayName: String, phoneDigits: String, phoneLocked: Boolean) {
        val uid = _currentUser.value?.uid ?: return
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            try {
                repository.updateProfile(uid, name, displayName, phoneDigits, phoneLocked)
                _uiState.value = AuthUiState.NavigateToHome
            } catch (e: Exception) {
                 _uiState.value = AuthUiState.Error(e.message ?: "Profile update failed")
            }
        }
    }

    fun resetPassword(email: String) {
         viewModelScope.launch {
             try {
                 repository.resetPassword(email)
                 // Maybe show a toast/snackbar via a side-effect channel instead of Error state?
                 // For now, reusing Error to pass message or we need a proper Message event.
                 _uiState.value = AuthUiState.Error("Password reset email sent") // abusing Error for success msg
             } catch (e: Exception) {
                 _uiState.value = AuthUiState.Error(e.message ?: "Failed to send reset email")
             }
         }
    }
    
    fun resetState() {
        _uiState.value = AuthUiState.Idle
    }
}
