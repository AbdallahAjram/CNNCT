// file: com/example/cnnct/homepage/model/ChatSummary.kt
package com.example.cnnct.homepage.model

import com.google.firebase.Timestamp

data class ChatSummary(
    val id: String,
    val groupName: String? = null,
    val lastMessageText: String = "",
    val lastMessageTimestamp: Timestamp? = null,
    val lastMessageSenderId: String? = null,
    val members: List<String> = emptyList(),
    val lastMessageIsRead: Boolean = false,
    val type: String = "private",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val lastMessageStatus: String? = null,
    val iBlockedPeer: Boolean? = null,
    val blockedByOther: Boolean? = null
)
