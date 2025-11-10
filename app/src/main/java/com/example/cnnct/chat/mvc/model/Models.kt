package com.cnnct.chat.mvc.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.GeoPoint

enum class MessageType { text, image, video, file, location }

data class Message(
    val id: String = "",
    val senderId: String = "",
    val type: MessageType = MessageType.text,
    val text: String? = null,
    val mediaUrl: String? = null,
    val location: GeoPoint? = null,

    // Attachment metadata
    val contentType: String? = null,
    val fileName: String? = null,
    val sizeBytes: Long? = null,

    // Timestamps/meta
    val createdAt: Timestamp? = null,
    val createdAtClient: Timestamp? = null,
    val editedAt: Timestamp? = null,
    val deleted: Boolean = false,

    // Delete/edit UX
    val hiddenFor: List<String>? = null,  // per-user delete-for-me
    val deletedBy: String? = null,
    val deletedAt: Timestamp? = null
)

data class ChatInfo(
    val id: String = "",
    val type: String = "private", // "private" | "group"
    val members: List<String> = emptyList(),
    val groupName: String? = null,
    val lastMessageText: String = "",
    val lastMessageTimestamp: Timestamp? = null,
    val lastMessageSenderId: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

data class UserChatMeta(
    val lastReadMessageId: String? = null,
    val lastOpenedAt: Timestamp? = null,
    val pinned: Boolean = false,
    val mutedUntil: Timestamp? = null
)

data class MessageDraft(
    val type: MessageType = MessageType.text,
    val text: String? = null,             // message text OR filename/caption
    val mediaUrl: String? = null,         // set for attachments after upload
    val location: GeoPoint? = null,

    // attachment-only
    val contentType: String? = null,
    val fileName: String? = null,
    val sizeBytes: Long? = null
)