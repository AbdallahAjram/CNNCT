package com.cnnct.chat.mvc.view

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import com.example.cnnct.common.view.UserAvatar

/* ===== Presence ===== */
enum class Presence { Online, Offline, Blocked }

/* ===== App Bars ===== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    canEdit: Boolean,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDeleteClick: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close selection")
            }
        },
        title = { Text("$count selected") },
        actions = {
            IconButton(onClick = onEdit, enabled = canEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDeleteClick) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    title: String,
    subtitle: String?,
    presence: Presence,
    photoUrl: String?,
    onBack: () -> Unit,
    onCallClick: () -> Unit,
    onHeaderClick: () -> Unit, // <-- FIX: regular lambda (not @Composable)

    // decide which actions to show
    chatType: String = "private",

    // menu callbacks
    onSearch: (() -> Unit)? = null,
    onClearChat: (() -> Unit)? = null,
    onBlockPeer: (() -> Unit)? = null,   // only for private
    onLeaveGroup: (() -> Unit)? = null   // only for group
) {
    var menuOpen by remember { mutableStateOf(false) }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        title = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onHeaderClick() }, // now safe
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarWithStatus(size = 42.dp, presence = presence, photoUrl = photoUrl)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        actions = {
            // Show call icon only on private chats
            if (chatType == "private") {
                IconButton(onClick = onCallClick) {
                    Icon(Icons.Rounded.Call, contentDescription = "Call")
                }
            }
            // 3-dots menu
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onSearch != null) {
                    DropdownMenuItem(text = { Text("Search") }, onClick = {
                        menuOpen = false; onSearch()
                    })
                }
                if (onClearChat != null) {
                    DropdownMenuItem(text = { Text("Clear chat") }, onClick = {
                        menuOpen = false; onClearChat()
                    })
                }
                if (chatType == "private" && onBlockPeer != null) {
                    DropdownMenuItem(text = { Text("Block") }, onClick = {
                        menuOpen = false; onBlockPeer()
                    })
                }
                if (chatType == "group" && onLeaveGroup != null) {
                    DropdownMenuItem(text = { Text("Leave group") }, onClick = {
                        menuOpen = false; onLeaveGroup()
                    })
                }
            }
        }
    )
}

@Composable
fun AvatarWithStatus(
    size: Dp,
    presence: Presence,
    photoUrl: String? = null
) {
    val dotColor = when (presence) {
        Presence.Blocked -> Color(0xFFFF3B30)  // red
        Presence.Online  -> Color(0xFF34C759)  // green
        Presence.Offline -> Color(0xFF9CA3AF)  // gray
    }

    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.BottomEnd
    ) {
        UserAvatar(
            photoUrl = photoUrl,
            size = size,
            contentDescription = "Avatar"
        )

        // Presence dot (with a tiny ring)
        Box(
            modifier = Modifier
                .size((size.value * 0.38f).dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface) // ring
                .padding(2.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}

@Composable
fun DayDivider(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 0.dp
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun Ticks(sent: Boolean, delivered: Boolean, read: Boolean) {
    val color = when {
        read -> Color(0xFF34B7F1)
        delivered -> Color.Gray
        sent -> Color.Gray
        else -> Color.Gray.copy(alpha = 0.4f)
    }
    val text = when {
        read -> "✓✓"
        delivered -> "✓✓"
        sent -> "✓"
        else -> "•"
    }
    Text(text, color = color, style = MaterialTheme.typography.labelSmall)
}

@Composable
fun MessageInput(
    onSend: (String) -> Unit,
    onAttach: (() -> Unit)? = null
) {
    val text = remember { mutableStateOf("") }
    val canSend = text.value.isNotBlank()
    val keyboard = LocalSoftwareKeyboardController.current

    Row(
        Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(
            onClick = { onAttach?.invoke() },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Filled.AttachFile, contentDescription = "Attach")
        }

        OutlinedTextField(
            value = text.value,
            onValueChange = { text.value = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (canSend) {
                        onSend(text.value.trim())
                        text.value = ""
                        keyboard?.hide()
                    }
                }
            )
        )

        IconButton(
            enabled = canSend,
            onClick = {
                onSend(text.value.trim())
                text.value = ""
                keyboard?.hide()
            },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Filled.Send, contentDescription = "Send")
        }
    }
}
