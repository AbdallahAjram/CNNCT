package com.cnnct.chat.mvc.model

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun ensurePrivateChat(userA: String, userB: String): String
    suspend fun sendMessage(chatId: String, senderId: String, draft: MessageDraft)
    fun streamMessages(chatId: String, pageSize: Int = 30): Flow<List<Message>>
    suspend fun markOpened(chatId: String, userId: String)
    suspend fun markRead(chatId: String, userId: String, messageId: String?)
    fun streamUserChatMeta(userId: String, chatId: String): Flow<UserChatMeta?>
    fun streamChatMemberMeta(chatId: String): Flow<Map<String, Any>?>
    suspend fun updateLastOpenedAt(chatId: String, userId: String)

    // edit/delete
    suspend fun editMessage(chatId: String, messageId: String, editorId: String, newText: String)
    suspend fun deleteForEveryone(chatId: String, messageId: String, requesterId: String)
    suspend fun deleteForMe(chatId: String, messageId: String, userId: String)
    fun newMessageId(chatId: String): String

    // attachments
    suspend fun sendAttachmentMessage(
        chatId: String,
        senderId: String,
        localUri: Uri,
        contentResolver: ContentResolver
    )
}
