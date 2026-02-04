package com.cnnct.chat.mvc.controller

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cnnct.chat.mvc.model.ChatRepository
import com.cnnct.chat.mvc.model.Message
import com.cnnct.chat.mvc.model.MessageDraft
import com.cnnct.chat.mvc.model.MessageType
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 💡 EXPLANATION:
// Grouping state into a single data class is a core MVVM/Compose best practice.
// It prevents "race conditions" where the UI might show a loading spinner (State A)
// but an error message (State B) at slightly inconsistent times.
data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val sendError: String? = null,
    val memberMeta: Map<String, Any>? = null,
    val iBlockedPeer: Boolean = false
)

// 💡 EXPLANATION:
// Inheriting from Android's ViewModel() allows this class to survive configuration changes
// (like screen rotation) automatically. No more manual recreation in Activity.
class ChatViewModel(
    private val repo: ChatRepository,
    private val currentUserId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var lastPreviewMessageIdForMe: String? = null
    private var peerUserId: String? = null

    fun setPeerUser(id: String?) { peerUserId = id }
    
    fun setIBlockedPeer(blocked: Boolean) {
        _uiState.update { it.copy(iBlockedPeer = blocked) }
    }

    fun clearError() {
        _uiState.update { it.copy(sendError = null) }
    }

    private fun Message.sentAtMs(): Long =
        this.createdAt?.toDate()?.time ?: this.createdAtClient?.toDate()?.time ?: 0L

    fun openChat(chatId: String) {
        // 💡 EXPLANATION:
        // We use 'viewModelScope' instead of a manual 'externalScope'.
        // This scope is automatically cancelled when the ViewModel is cleared (screen closed).
        // This prevents battery vampires (zombie listeners running in background).
        streamJob?.cancel()

        // 💡 EXPLANATION:
        // Combined Flow collection.
        // We launch two coroutines for the two streams: Messages and MemberMeta.
        streamJob = viewModelScope.launch {
            launch {
                repo.streamMessages(chatId, pageSize = 50).collectLatest { list ->
                    val oldSize = _uiState.value.messages.size
                    _uiState.update { it.copy(messages = list) }
                    
                    if (list.size > oldSize) {
                        markDeliveredIfNeeded(chatId, currentUserId, list)
                    }
                    pushMyPreviewIfChanged(chatId, list)
                }
            }

            launch {
                repo.streamChatMemberMeta(chatId).collectLatest { meta ->
                   if (meta != null) {
                       _uiState.update { it.copy(memberMeta = meta) }
                   }
                }
            }
        }
    }

    fun sendText(chatId: String, senderId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        
        if (_uiState.value.iBlockedPeer) {
            _uiState.update { it.copy(sendError = "You blocked this user. Unblock to chat.") }
            return
        }

        viewModelScope.launch {
            try {
                repo.sendMessage(chatId, senderId, MessageDraft(text = clean))
            } catch (e: Exception) {
                _uiState.update { it.copy(sendError = "Message not sent. You may be blocked.") }
            }
        }
    }

    fun sendAttachments(chatId: String, senderId: String, uris: List<Uri>, cr: ContentResolver) {
        if (uris.isEmpty()) return
        if (_uiState.value.iBlockedPeer) {
            _uiState.update { it.copy(sendError = "You blocked this user. Unblock to chat.") }
            return
        }
        
        // 💡 EXPLANATION:
        // Dispatchers.IO is optimized for disk/network operations, perfect for file uploads.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                uris.forEach { uri ->
                    repo.sendAttachmentMessage(chatId, senderId, uri, cr)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(sendError = "Attachment not sent. You may be blocked.") }
            }
        }
    }

    fun sendLocation(chatId: String, senderId: String, lat: Double, lng: Double, address: String?) {
        if (_uiState.value.iBlockedPeer) {
            _uiState.update { it.copy(sendError = "You blocked this user. Unblock to chat.") }
            return
        }
        viewModelScope.launch {
            try {
                val draft = MessageDraft(
                    type = MessageType.location,
                    text = address,
                    location = GeoPoint(lat, lng)
                )
                repo.sendMessage(chatId, senderId, draft)
            } catch (e: Exception) {
                _uiState.update { it.copy(sendError = "Location not sent. You may be blocked.") }
            }
        }
    }

    fun markRead(chatId: String, userId: String, lastId: String?) {
        viewModelScope.launch {
            try {
                repo.markRead(chatId, userId, lastId)
            } catch (e: Exception) {
                println("markRead failed: ${e.message}")
            }
        }
    }

    fun markOpened(chatId: String, userId: String) {
        viewModelScope.launch {
            try {
                repo.markOpened(chatId, userId)
            } catch (e: Exception) {
                println("markOpened failed: ${e.message}")
            }
        }
    }

    // 💡 EXPLANATION:
    // No more manual stop()! The ViewModel's onCleared() is called automatically by Android.
    override fun onCleared() {
        super.onCleared()
        streamJob?.cancel()
        println("♻️ ChatViewModel cleared and listeners removed.")
    }

    private fun markDeliveredIfNeeded(chatId: String, currentUserId: String, newMessages: List<Message>) {
        viewModelScope.launch {
            val latestIncoming = newMessages
                .asSequence()
                .filter { it.senderId != currentUserId }
                .maxByOrNull { it.sentAtMs() }

            if (latestIncoming != null) {
                repo.updateLastOpenedAt(chatId, currentUserId)
            }
        }
    }
    
    fun editMessage(chatId: String, messageId: String, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return
        
        val current = _uiState.value.messages.firstOrNull { it.id == messageId }?.text?.trim()
        if (!current.isNullOrEmpty() && current == trimmed) return

        viewModelScope.launch {
            try {
                repo.editMessage(chatId, messageId, currentUserId, trimmed)
            } catch (e: Exception) {
                _uiState.update { it.copy(sendError = "Edit failed.") }
            }
        }
    }

    fun deleteForEveryone(chatId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        viewModelScope.launch {
            try {
                messageIds.forEach { id ->
                    repo.deleteForEveryone(chatId, id, currentUserId)
                }
                pushMyPreviewIfChanged(chatId, _uiState.value.messages)
            } catch (e: Exception) {
                _uiState.update { it.copy(sendError = "Delete failed.") }
            }
        }
    }

    fun deleteForMe(chatId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        viewModelScope.launch {
            try {
                messageIds.forEach { id ->
                    repo.deleteForMe(chatId, id, currentUserId)
                }
                pushMyPreviewIfChanged(chatId, _uiState.value.messages)
            } catch (e: Exception) {
                _uiState.update { it.copy(sendError = "Delete failed.") }
            }
        }
    }

    private fun pushMyPreviewIfChanged(chatId: String, list: List<Message>) {
        val latest = list.asReversed().firstOrNull { m ->
            !m.deleted && (m.hiddenFor?.contains(currentUserId) != true)
        }

        val latestId = latest?.id
        if (latestId == lastPreviewMessageIdForMe) return
        lastPreviewMessageIdForMe = latestId

        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.updateUserPreview(
                    ownerUserId = currentUserId,
                    chatId = chatId,
                    latest = latest
                )
            } catch (_: Exception) { }
        }
    }

    fun clearChat(chatId: String) {
        viewModelScope.launch {
            try {
                repo.clearChatForMe(chatId, currentUserId)
            } catch (e: Exception) {
                _uiState.update { it.copy(sendError = "Failed to clear chat.") }
            }
        }
    }
}
