// file: com/cnnct/chat/mvc/controller/ChatController.kt
package com.cnnct.chat.mvc.controller

import android.content.ContentResolver
import android.net.Uri
import com.cnnct.chat.mvc.model.ChatRepository
import com.cnnct.chat.mvc.model.Message
import com.cnnct.chat.mvc.model.MessageDraft
import com.cnnct.chat.mvc.model.MessageType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

class ChatController(
    private val repo: ChatRepository,
    private val currentUserId: String,
    private val externalScope: CoroutineScope? = null
) {
    // Use external scope if provided, otherwise create our own supervisor scope
    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var streamJob: Job? = null
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    // last preview written
    private var lastPreviewMessageIdForMe: String? = null

    // Blocking awareness
    private var peerUserId: String? = null
    private val _iBlockedPeer = MutableStateFlow(false)
    val iBlockedPeer: StateFlow<Boolean> = _iBlockedPeer

    // Surface send errors (e.g., PERMISSION_DENIED when the other has blocked me)
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    fun setPeerUser(id: String?) { peerUserId = id }
    fun setIBlockedPeer(blocked: Boolean) { _iBlockedPeer.value = blocked }
    fun clearError() { _sendError.value = null }

    private fun Message.sentAtMs(): Long =
        this.createdAt?.toDate()?.time ?: this.createdAtClient?.toDate()?.time ?: 0L

    fun openChat(chatId: String) {
        streamJob?.cancel()
        streamJob = scope.launch {
            repo.streamMessages(chatId, pageSize = 50).collectLatest { list ->
                val oldSize = _messages.value.size
                _messages.value = list
                if (list.size > oldSize) {
                    markDeliveredIfNeeded(chatId, currentUserId, list)
                }
                pushMyPreviewIfChanged(chatId, list)
            }
        }
    }

    fun sendText(chatId: String, senderId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        // If I blocked the peer, don't send
        if (_iBlockedPeer.value) {
            _sendError.value = "You blocked this user. Unblock to chat."
            return
        }
        scope.launch {
            try {
                repo.sendMessage(chatId, senderId, MessageDraft(text = clean))
            } catch (e: Exception) {
                // PERMISSION_DENIED or other error (likely peer blocked me)
                _sendError.value = "Message not sent. You may be blocked."
            }
        }
    }

    fun sendAttachments(chatId: String, senderId: String, uris: List<Uri>, cr: ContentResolver) {
        if (uris.isEmpty()) return
        if (_iBlockedPeer.value) {
            _sendError.value = "You blocked this user. Unblock to chat."
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                uris.forEach { uri -> repo.sendAttachmentMessage(chatId, senderId, uri, cr) }
            } catch (e: Exception) {
                _sendError.value = "Attachment not sent. You may be blocked."
            }
        }
    }

    fun markRead(chatId: String, userId: String, lastId: String?) {
        scope.launch { repo.markRead(chatId, userId, lastId) }
    }

    fun markOpened(chatId: String, userId: String) {
        scope.launch { repo.markOpened(chatId, userId) }
    }

    fun stop() {
        streamJob?.cancel()
        if (externalScope == null) {
            scope.cancel()
        }
    }

    fun markDeliveredIfNeeded(chatId: String, currentUserId: String, newMessages: List<Message>) {
        scope.launch {
            val latestIncoming = newMessages
                .asSequence()
                .filter { it.senderId != currentUserId }
                .maxByOrNull { it.sentAtMs() }

            if (latestIncoming != null) {
                repo.updateLastOpenedAt(chatId, currentUserId)
            }
        }
    }

    // ===== Edit / Delete =====

    fun editMessage(chatId: String, messageId: String, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return
        val current = _messages.value.firstOrNull { it.id == messageId }?.text?.trim()
        if (!current.isNullOrEmpty() && current == trimmed) return

        scope.launch {
            try {
                repo.editMessage(chatId, messageId, currentUserId, trimmed)
            } catch (e: Exception) {
                _sendError.value = "Edit failed."
            }
        }
    }

    fun deleteForEveryone(chatId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        scope.launch {
            try {
                messageIds.forEach { id -> repo.deleteForEveryone(chatId, id, currentUserId) }
                pushMyPreviewIfChanged(chatId, _messages.value)
            } catch (e: Exception) {
                _sendError.value = "Delete failed."
            }
        }
    }

    fun deleteForMe(chatId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        scope.launch {
            try {
                messageIds.forEach { id -> repo.deleteForMe(chatId, id, currentUserId) }
                pushMyPreviewIfChanged(chatId, _messages.value)
            } catch (e: Exception) {
                _sendError.value = "Delete failed."
            }
        }
    }

    /* ============================================================
       Per-user Home preview
       ============================================================ */
    private fun pushMyPreviewIfChanged(chatId: String, list: List<Message>) {
        val latest = list.asReversed().firstOrNull { m ->
            !m.deleted && (m.hiddenFor?.contains(currentUserId) != true)
        }

        val latestId = latest?.id
        if (latestId == lastPreviewMessageIdForMe) return
        lastPreviewMessageIdForMe = latestId

        CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.updateUserPreview(
                    ownerUserId = currentUserId,
                    chatId = chatId,
                    latest = latest
                )
            } catch (_: Exception) {
                // ignore; next snapshot will try again
            }
        }
    }
}
