package com.cnnct.chat.mvc.controller

import android.content.ContentResolver
import android.net.Uri
import com.cnnct.chat.mvc.model.ChatRepository
import com.cnnct.chat.mvc.model.Message
import com.cnnct.chat.mvc.model.MessageDraft
import com.cnnct.chat.mvc.model.MessageType
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

class ChatController(
    private val repo: ChatRepository,
    private val currentUserId: String,
    private val externalScope: CoroutineScope? = null
) {
    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var streamJob: Job? = null
    private var metaJob: ListenerRegistration? = null

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    // 🔵 live memberMeta updates
    private val _memberMeta = MutableStateFlow<Map<String, Any>?>(null)
    val memberMeta: StateFlow<Map<String, Any>?> = _memberMeta

    private var lastPreviewMessageIdForMe: String? = null
    private var peerUserId: String? = null

    private val _iBlockedPeer = MutableStateFlow(false)
    val iBlockedPeer: StateFlow<Boolean> = _iBlockedPeer

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError

    fun setPeerUser(id: String?) { peerUserId = id }
    fun setIBlockedPeer(blocked: Boolean) { _iBlockedPeer.value = blocked }
    fun clearError() { _sendError.value = null }

    private fun Message.sentAtMs(): Long =
        this.createdAt?.toDate()?.time ?: this.createdAtClient?.toDate()?.time ?: 0L

    fun openChat(chatId: String) {
        streamJob?.cancel()
        metaJob?.remove()

        // 🔄 Stream messages live
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

        // 🔵 FIXED: Listen for memberMeta updates with MetadataChanges.INCLUDE
        metaJob = FirebaseFirestore.getInstance()
            .collection("chats")
            .document(chatId)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    println("🔥 memberMeta listener error: ${error.message}")
                    return@addSnapshotListener
                }
                val data = snapshot?.get("memberMeta") as? Map<String, Any>
                if (data != null) {
                    _memberMeta.value = data
                }
            }
    }

    fun sendText(chatId: String, senderId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (_iBlockedPeer.value) {
            _sendError.value = "You blocked this user. Unblock to chat."
            return
        }
        scope.launch {
            try {
                repo.sendMessage(chatId, senderId, MessageDraft(text = clean))
            } catch (e: Exception) {
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
                uris.forEach { uri ->
                    repo.sendAttachmentMessage(chatId, senderId, uri, cr)
                }
            } catch (e: Exception) {
                _sendError.value = "Attachment not sent. You may be blocked."
            }
        }
    }

    /** ⬇️ FIXED: Location sender with proper MessageType.location */
    fun sendLocation(chatId: String, senderId: String, lat: Double, lng: Double, address: String?) {
        if (_iBlockedPeer.value) {
            _sendError.value = "You blocked this user. Unblock to chat."
            return
        }
        scope.launch {
            try {
                val draft = MessageDraft(
                    type = MessageType.location, // ✅ CORRECT: Use location type
                    text = address,              // keep optional human-readable address in text
                    location = GeoPoint(lat, lng)
                )
                repo.sendMessage(chatId, senderId, draft)
            } catch (e: Exception) {
                _sendError.value = "Location not sent. You may be blocked."
            }
        }
    }

    fun markRead(chatId: String, userId: String, lastId: String?) {
        scope.launch {
            try {
                println("🎯 ChatController.markRead called: chat=$chatId, user=$userId, lastId=$lastId")
                repo.markRead(chatId, userId, lastId)
                println("✅ ChatController.markRead completed successfully")
            } catch (e: Exception) {
                println("❌ ChatController.markRead failed: ${e.message}")
            }
        }
    }

    fun markOpened(chatId: String, userId: String) {
        scope.launch {
            try {
                repo.markOpened(chatId, userId)
            } catch (e: Exception) {
                println("⚠️ markOpened failed: ${e.message}")
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        metaJob?.remove()
        if (externalScope == null) scope.cancel()
    }

    // 🔔 Mark incoming messages as delivered
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

    // 📝 Edit message
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

    // 🗑 Delete for everyone
    fun deleteForEveryone(chatId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        scope.launch {
            try {
                messageIds.forEach { id ->
                    repo.deleteForEveryone(chatId, id, currentUserId)
                }
                pushMyPreviewIfChanged(chatId, _messages.value)
            } catch (e: Exception) {
                _sendError.value = "Delete failed."
            }
        }
    }

    // 🗑 Delete for me
    fun deleteForMe(chatId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        scope.launch {
            try {
                messageIds.forEach { id ->
                    repo.deleteForMe(chatId, id, currentUserId)
                }
                pushMyPreviewIfChanged(chatId, _messages.value)
            } catch (e: Exception) {
                _sendError.value = "Delete failed."
            }
        }
    }

    // 🏠 Update home preview
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
                // ignore, next snapshot will catch up
            }
        }
    }
}