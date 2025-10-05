package com.example.cnnct.homepage.model

import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class ChatSummary(
    val id: String = "",
    val type: String = "private",            // "private" | "group"
    val members: List<String> = emptyList(),
    val groupName: String? = null,
    val lastMessageText: String = "",        // non-null → provide default
    val lastMessageTimestamp: com.google.firebase.Timestamp? = null,
    val lastMessageSenderId: String? = null,
    val createdAt: com.google.firebase.Timestamp? = null,
    val updatedAt: com.google.firebase.Timestamp? = null,
    val lastMessageIsRead: Boolean = false,   // legacy support
    val lastMessageStatus: String? = null
)
