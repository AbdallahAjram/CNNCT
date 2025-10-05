package com.example.cnnct.homepage.view

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cnnct.common.view.UserAvatar
import com.example.cnnct.homepage.model.ChatSummary
import java.text.SimpleDateFormat
import java.util.*

private enum class Presence { Online, Offline, Blocked }

@Composable
fun ChatListItem(
    chatSummary: ChatSummary,
    currentUserId: String,
    userMap: Map<String, String>,
    onClick: () -> Unit,
    onlineMap: Map<String, Long?> = emptyMap(),
    blockedUserIds: Set<String> = emptySet(),
    presenceWindowMs: Long = 2 * 60 * 1000L,
    photoUrl: String? = null
) {
    val chatName = when (chatSummary.type) {
        "group" -> {
            Log.d("ChatListItem", "Group chat → groupName=${chatSummary.groupName}")
            chatSummary.groupName ?: "Group"
        }
        "private" -> {
            val otherUserId = chatSummary.members.firstOrNull { it != currentUserId }
            val name = userMap[otherUserId] ?: "Unknown"
            Log.d("ChatListItem", "Private chat with $otherUserId → name=$name")
            name
        }
        else -> {
            Log.d("ChatListItem", "Unknown chat type: ${chatSummary.type}")
            "Chat"
        }
    }
    Log.d("ChatListItem", "Resolved chatName → $chatName")

    fun presenceFor(uid: String?): Presence {
        if (uid == null) return Presence.Offline
        if (blockedUserIds.contains(uid)) return Presence.Blocked
        val last = onlineMap[uid]
        val now = System.currentTimeMillis()
        return if (last != null && now - last <= presenceWindowMs) Presence.Online else Presence.Offline
    }

    val presence = when (chatSummary.type) {
        "private" -> presenceFor(chatSummary.members.firstOrNull { it != currentUserId })
        else      -> Presence.Offline
    }

    val statusColor = when (presence) {
        Presence.Blocked -> Color(0xFFFF3B30)
        Presence.Online  -> Color(0xFF34C759)
        Presence.Offline -> Color(0xFF9CA3AF)
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                UserAvatar(
                    photoUrl = photoUrl,
                    size = 48.dp,
                    contentDescription = "Avatar"
                )
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                        .background(statusColor, CircleShape)
                        .align(Alignment.BottomEnd)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chatName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    chatSummary.lastMessageTimestamp?.let { ts ->
                        Text(
                            text = formatTimestamp(ts.toDate()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hasText = chatSummary.lastMessageText.isNotBlank()
                    val rawText = if (hasText) chatSummary.lastMessageText else "No messages yet"

                    val senderLabel: String? =
                        if (chatSummary.type == "group" && !chatSummary.lastMessageSenderId.isNullOrBlank()) {
                            if (chatSummary.lastMessageSenderId == currentUserId) "You"
                            else userMap[chatSummary.lastMessageSenderId] ?: "Someone"
                        } else null

                    val finalText = if (senderLabel != null) "$senderLabel: $rawText" else rawText

                    Text(
                        text = finalText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )

                    // ticks only if you sent and there's message text
                    if (hasText && chatSummary.lastMessageSenderId == currentUserId) {
                        val effectiveStatus = chatSummary.lastMessageStatus
                            ?: if (chatSummary.lastMessageIsRead) "read" else "delivered"

                        val (ticks, tickColor) = when (effectiveStatus) {
                            "read"      -> "✓✓" to Color(0xFF34B7F1)
                            "delivered" -> "✓✓" to MaterialTheme.colorScheme.onSurfaceVariant
                            "sent"      -> "✓"  to MaterialTheme.colorScheme.onSurfaceVariant
                            else        -> null  to Color.Transparent
                        }

                        if (ticks != null) {
                            Text(
                                text = ticks,
                                color = tickColor,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(date: Date): String {
    val now = Date()
    val diff = now.time - date.time
    val oneDayMillis = 24 * 60 * 60 * 1000

    val format = when {
        diff < oneDayMillis      -> SimpleDateFormat("h:mm a", Locale.getDefault())
        diff < 7 * oneDayMillis  -> SimpleDateFormat("EEE", Locale.getDefault())
        else                     -> SimpleDateFormat("MMM d", Locale.getDefault())
    }
    return format.format(date)
}
