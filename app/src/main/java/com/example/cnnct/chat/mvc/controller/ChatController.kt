// file: com/cnnct/chat/mvc/controller/ChatController.kt
package com.cnnct.chat.mvc.controller

import android.content.ContentResolver
import android.net.Uri
import com.cnnct.chat.mvc.model.ChatRepository
import com.cnnct.chat.mvc.model.Message
import com.cnnct.chat.mvc.model.MessageDraft
import com.cnnct.chat.mvc.model.MessageType
import com.google.firebase.Firebase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
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

    // Track last preview we wrote for this user to avoid redundant writes
    private var lastPreviewMessageIdForMe: String? = null

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
                // Update my per-user last-message preview (ignoring hidden/deleted)
                pushMyPreviewIfChanged(chatId, list)
            }
        }
    }

    fun sendText(chatId: String, senderId: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        scope.launch {
            repo.sendMessage(chatId, senderId, MessageDraft(text = clean))
        }
    }

    fun sendAttachments(chatId: String, senderId: String, uris: List<Uri>, cr: ContentResolver) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            // sequential = preserves user-selected order
            uris.forEach { uri -> repo.sendAttachmentMessage(chatId, senderId, uri, cr) }
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
        // Only cancel the scope if we created it; don't cancel a caller-owned scope.
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
        // Optional no-op guard: skip if text unchanged
        val current = _messages.value.firstOrNull { it.id == messageId }?.text?.trim()
        if (!current.isNullOrEmpty() && current == trimmed) return

        scope.launch {
            repo.editMessage(chatId, messageId, currentUserId, trimmed)
        }
    }

    fun deleteForEveryone(chatId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        scope.launch {
            messageIds.forEach { id ->
                repo.deleteForEveryone(chatId, id, currentUserId)
            }
            // stream collector will recompute preview on next snapshot,
            // but we can also try now with current cache:
            pushMyPreviewIfChanged(chatId, _messages.value)
        }
    }

    fun deleteForMe(chatId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        scope.launch {
            messageIds.forEach { id ->
                repo.deleteForMe(chatId, id, currentUserId)
            }
            // Optimistically recompute my preview from current cache;
            // stream collector will also recompute when Firestore pushes the update.
            pushMyPreviewIfChanged(chatId, _messages.value)
        }
    }

    /* ============================================================
       Per-user Home preview (fixes: "Delete for me" still shows on Home)
       We write to: /userChats/{me}/chats/{chatId}
       ============================================================ */

    private fun pushMyPreviewIfChanged(chatId: String, list: List<Message>) {
        // Find latest message that is NOT deleted and NOT hidden for me
        val latest = list.asReversed().firstOrNull { m ->
            !m.deleted && (m.hiddenFor?.contains(currentUserId) != true)
        }

        val latestId = latest?.id
        if (latestId == lastPreviewMessageIdForMe) return  // no change

        lastPreviewMessageIdForMe = latestId

        scope.launch(Dispatchers.IO) {
            try {
                val db = Firebase.firestore
                val ref = db.collection("userChats")
                    .document(currentUserId)
                    .collection("chats")
                    .document(chatId)

                // Build a WhatsApp-like preview text for attachments
                val previewText: String? = when {
                    latest == null -> null
                    latest.type == MessageType.text -> latest.text?.take(500) // keep it short
                    latest.type == MessageType.image -> "Photo"
                    latest.type == MessageType.video -> "Video"
                    latest.type == MessageType.file -> latest.text ?: "File"
                    latest.type.name.equals("location", ignoreCase = true) -> "Location"
                    else -> latest.text ?: latest.type.name.lowercase().replaceFirstChar { it.uppercase() }
                }

                val ts = latest?.createdAt ?: latest?.createdAtClient

                val data = hashMapOf<String, Any?>(
                    "lastMessageId" to latest?.id,
                    "lastMessageText" to previewText,
                    "lastMessageType" to latest?.type?.name,
                    "lastMessageSenderId" to latest?.senderId,
                    "lastMessageTimestamp" to ts,
                    "updatedAt" to FieldValue.serverTimestamp()
                )

                ref.set(data, SetOptions.merge())
            } catch (_: Exception) {
                // ignore; next snapshot will try again
            }
        }
    }
}
