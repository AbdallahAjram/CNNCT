package com.abdallah.cnnct.homepage.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abdallah.cnnct.common.view.UserAvatar
import com.abdallah.cnnct.homepage.model.ChatSummary
import java.text.SimpleDateFormat
import java.util.*
import com.abdallah.cnnct.R

import com.abdallah.cnnct.ui.theme.ReadReceiptBlue

private enum class Presence { Online, Offline, Blocked }

@Composable
fun ChatListItem(
    chatSummary: ChatSummary,
    currentUserId: String,
    userMap: Map<String, String>,
    onClick: (() -> Unit)? = null,          // nullable so parent can own gestures
    onlineMap: Map<String, Long?> = emptyMap(),
    blockedUserIds: Set<String> = emptySet(),
    presenceWindowMs: Long = 2 * 60 * 1000L,
    photoUrl: String? = null,
    // selection UI
    selectionMode: Boolean = false,
    selected: Boolean = false,
    // NEW
    muted: Boolean = false,
    pinned: Boolean = false
) {
    val chatName = when (chatSummary.type) {
        "group" -> {
            chatSummary.groupName ?: "Group"
        }
        "private" -> {
            val otherUserId = chatSummary.members.firstOrNull { it != currentUserId }
            userMap[otherUserId] ?: "Unknown"
        }
        else -> "Chat"
    }

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

    val customColors = com.abdallah.cnnct.ui.theme.LocalCustomColors.current
    val statusColor = when (presence) {
        Presence.Blocked -> customColors.presenceBlocked
        Presence.Online  -> customColors.presenceOnline
        Presence.Offline -> customColors.presenceOffline
    }

    // ---- Selection visuals
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary
        else if (selectionMode) MaterialTheme.colorScheme.outline
        else Color.Transparent

    val borderWidth = when {
        selected -> 2.dp
        selectionMode -> 1.dp
        else -> 0.dp
    }

    val elevation = if (selected) 6.dp else 2.dp

    @Composable
    fun BoxScope.SelectionBadge() {
        if (!selectionMode) return
        val isSel = selected
        val bg = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
        val fg = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline

        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                )
                .background(bg)
                .align(Alignment.TopEnd)
        ) {
            if (isSel) {
                Text(
                    text = "✓",
                    color = fg,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }

    // 🔵 Unread logic: use the count we computed in Repository
    val isUnread = chatSummary.unreadCount > 0

    @Composable
    fun RowContent() {
        Box(Modifier.fillMaxWidth()) {
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
                    // ✅ Use different fallbacks for group vs private
                    val fallbackRes = if (chatSummary.type == "group") {
                        R.drawable.defaultgpp
                    } else {
                        R.drawable.defaultpp
                    }

                    UserAvatar(
                        photoUrl = photoUrl,
                        size = 48.dp,
                        contentDescription = "Avatar",
                        fallbackRes = fallbackRes
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

                        // tiny bell-off if muted
                        if (muted) {
                            Icon(
                                imageVector = Icons.Filled.NotificationsOff,
                                contentDescription = "Muted",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 6.dp, end = 4.dp)
                                    .size(16.dp)
                            )
                        }
                        
                        // tiny pin if pinned
                        if (pinned) {
                            Icon(
                                imageVector = Icons.Filled.PushPin,
                                contentDescription = "Pinned",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 2.dp, end = 4.dp)
                                    .size(16.dp)
                            )
                        }

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

                        if (hasText && chatSummary.lastMessageSenderId == currentUserId) {
                            val effectiveStatus = chatSummary.lastMessageStatus
                                ?: if (chatSummary.lastMessageIsRead) "read" else "delivered"

                            val (iconVector, iconColor) = when (effectiveStatus) {
                                "read"      -> Icons.Default.DoneAll to ReadReceiptBlue
                                "delivered" -> Icons.Default.DoneAll to MaterialTheme.colorScheme.onSurfaceVariant
                                "sent"      -> Icons.Default.Done to MaterialTheme.colorScheme.onSurfaceVariant
                                else        -> null to Color.Transparent
                            }

                            if (iconVector != null) {
                                Icon(
                                    imageVector = iconVector,
                                    contentDescription = effectiveStatus,
                                    tint = iconColor,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .size(20.dp)
                                )
                            }
                        }

                        // 🔵 Unread badge (0/1 with current data model)
                        if (isUnread) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "1",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
            SelectionBadge()
        }
    }

    val animatedBorderColor by androidx.compose.animation.animateColorAsState(targetValue = borderColor, animationSpec = androidx.compose.animation.core.tween(200))
    val animatedBorderWidth by androidx.compose.animation.core.animateDpAsState(targetValue = borderWidth, animationSpec = androidx.compose.animation.core.tween(200))
    val animatedElevation by androidx.compose.animation.core.animateDpAsState(targetValue = elevation, animationSpec = androidx.compose.animation.core.tween(200))

    val cardModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
        .then(
            if (animatedBorderWidth > 0.dp)
                Modifier.border(animatedBorderWidth, animatedBorderColor, MaterialTheme.shapes.medium)
            else Modifier
        )

    val content = @Composable {
        if (onClick == null) {
            Card(
                modifier = cardModifier,
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = animatedElevation)
            ) { RowContent() }
        } else {
            Card(
                onClick = onClick,
                modifier = cardModifier,
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = animatedElevation)
            ) { RowContent() }
        }
    }

    content()
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
