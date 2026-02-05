// file: com/example/cnnct/homepage/model/ChatSummary.kt
package com.abdallah.cnnct.homepage.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.IgnoreExtraProperties

@Keep
@IgnoreExtraProperties
data class ChatSummary(
    var id: String = "",                 // will be filled from doc.id

    var groupName: String? = null,
    var lastMessageText: String = "",
    var lastMessageTimestamp: Timestamp? = null,
    var lastMessageSenderId: String? = null,
    var members: List<String> = emptyList(),
    var lastMessageIsRead: Boolean = false,
    var type: String = "private",

    var createdAt: Timestamp? = null,
    var updatedAt: Timestamp? = null,
    var lastMessageStatus: String? = null,

    // optional flags you had
    var iBlockedPeer: Boolean? = null,
    var blockedByOther: Boolean? = null,

    // (optional, future-proof)
    var groupPhotoUrl: String? = null,

    // 🔵 Computed locally (not persisted): badge count for unread on Home row.
    // With the current data model, this will effectively be 0 or 1.
    var unreadCount: Int = 0
)
