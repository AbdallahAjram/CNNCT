package com.cnnct.chat.mvc.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import com.example.cnnct.homepage.model.ChatSummary

interface ChatRepository {
    suspend fun ensurePrivateChat(userA: String, userB: String): String

    suspend fun sendMessage(
        chatId: String,
        senderId: String,
        draft: MessageDraft
    )

    fun streamMessages(chatId: String, pageSize: Int): Flow<List<Message>>

    suspend fun markOpened(chatId: String, userId: String)

    suspend fun markRead(chatId: String, userId: String, messageId: String?)

    fun streamUserChatMeta(userId: String, chatId: String): Flow<UserChatMeta?>

    fun streamChatMemberMeta(chatId: String): Flow<Map<String, Any>?>

    suspend fun updateLastOpenedAt(chatId: String, userId: String)

    fun sendAttachmentMessage(
        chatId: String,
        senderId: String,
        localUri: Uri,
        context: Context
    ): Flow<UploadStatus>
    
    sealed class UploadStatus {
        data class Progress(val percentage: Float): UploadStatus()
        object Completed: UploadStatus()
    }

    suspend fun editMessage(
        chatId: String,
        messageId: String,
        editorId: String,
        newText: String
    )

    suspend fun deleteForEveryone(
        chatId: String,
        messageId: String,
        requesterId: String
    )

    suspend fun deleteForMe(
        chatId: String,
        messageId: String,
        userId: String
    )

    fun newMessageId(chatId: String): String

    // ✅ NEW — used by ChatController.pushMyPreviewIfChanged(...)
    suspend fun updateUserPreview(
        ownerUserId: String,
        chatId: String,
        latest: Message?
    )

    suspend fun hideChat(userId: String, chatId: String)

    suspend fun clearChatForMe(chatId: String, userId: String)

    // ========== Home / List methods ==========

    fun listenMyChats(userId: String): Flow<List<ChatSummary>>

    fun listenMyUserChatMeta(userId: String): Flow<Map<String, UserChatMeta>>

    suspend fun setChatMutedUntil(userId: String, chatId: String, mutedUntilMs: Long?)
    suspend fun setArchived(userId: String, chatId: String, archived: Boolean)
    suspend fun promoteIncomingLastMessagesToDelivered(userId: String, maxPerRun: Int = 25)

    suspend fun muteChatForHours(userId: String, chatId: String, hours: Long)
    suspend fun muteChatForever(userId: String, chatId: String)
    suspend fun unmuteChat(userId: String, chatId: String)
}
