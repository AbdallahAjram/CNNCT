package com.abdallah.cnnct.notifications.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abdallah.cnnct.notifications.model.NotificationSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.resumeWithException

/**
 * Simple Firestore-backed repo embedded for convenience.
 * If you already have a central Settings repository, swap this out and keep the ViewModel.
 */
class NotificationSettingsRepository(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private fun userDoc(): DocumentReference {
        val uid = auth.currentUser?.uid ?: throw IllegalStateException("No user")
        return firestore.collection("users").document(uid)
    }

    suspend fun save(settings: NotificationSettings) {
        userDoc().set(
            mapOf(
                "notificationsEnabled" to settings.notificationsEnabled,
                "chatNotificationsEnabled" to settings.chatNotificationsEnabled,
                "callNotificationsEnabled" to settings.callNotificationsEnabled
            ),
            com.google.firebase.firestore.SetOptions.merge()
        ).await()
    }

    fun listen(
        onUpdate: (NotificationSettings) -> Unit,
        onError: (Throwable) -> Unit
    ): com.google.firebase.firestore.ListenerRegistration {
        return userDoc().addSnapshotListener { snap, err ->
            if (err != null) {
                onError(err); return@addSnapshotListener
            }
            val s = NotificationSettings(
                notificationsEnabled = snap?.getBoolean("notificationsEnabled") ?: true,
                chatNotificationsEnabled = snap?.getBoolean("chatNotificationsEnabled") ?: true,
                callNotificationsEnabled = snap?.getBoolean("callNotificationsEnabled") ?: true
            )
            onUpdate(s)
        }
    }
}

/** Tiny await() helper to avoid full coroutines-play-services dep if you’re not using it elsewhere. */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) cont.resume(task.result, null)
            else cont.resumeWithException(task.exception ?: RuntimeException("Task failed"))
        }
    }

class NotificationSettingsViewModel(
    private val repo: NotificationSettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(NotificationSettings())
    val state: StateFlow<NotificationSettings> = _state.asStateFlow()

    private var registration: com.google.firebase.firestore.ListenerRegistration? = null

    // One-shot toast/UX events if you want to show “Saved” feedback (optional).
    val events = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        // Start listening to Firestore for live updates
        registration = repo.listen(
            onUpdate = { s -> _state.value = s },
            onError = { /* swallow or log; could emit an event */ }
        )
    }

    override fun onCleared() {
        registration?.remove()
        super.onCleared()
    }

    fun setGlobal(enabled: Boolean) = updateAndSave(
        _state.value.copy(
            notificationsEnabled = enabled,
            // If global is OFF, force sub-toggles to true internally (neutral) or leave as-is?
            // We’ll leave as-is so the user’s last choice sticks when they re-enable.
        )
    )

    fun setChats(enabled: Boolean) = updateAndSave(
        _state.value.copy(chatNotificationsEnabled = enabled)
    )

    fun setCalls(enabled: Boolean) = updateAndSave(
        _state.value.copy(callNotificationsEnabled = enabled)
    )

    private fun updateAndSave(next: NotificationSettings) {
        _state.update { next }
        viewModelScope.launch {
            try {
                repo.save(next)
                // events.tryEmit("Saved") // optional
            } catch (_: Throwable) {
                // events.tryEmit("Couldn’t save")
            }
        }
    }
}
