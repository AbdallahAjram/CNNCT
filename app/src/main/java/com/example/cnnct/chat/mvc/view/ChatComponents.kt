package com.cnnct.chat.mvc.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.cnnct.R

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
                Icon(Icons.Filled.ArrowBack, contentDescription = "Close selection")
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
    onBack: () -> Unit,
    onCallClick: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarWithStatus(size = 36.dp, presence = presence)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onCallClick) {
                Icon(Icons.Filled.Call, contentDescription = "Voice call")
            }
        }
    )
}

/* ===== Small components ===== */

@Composable
fun AvatarWithStatus(
    size: Dp,
    presence: Presence
) {
    val dotColor = when (presence) {
        Presence.Blocked -> Color(0xFFFF3B30)
        Presence.Online  -> Color(0xFF34C759)
        Presence.Offline -> Color(0xFF9CA3AF)
    }
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.BottomEnd
    ) {
        Image(
            painter = painterResource(id = R.drawable.defaultpp),
            contentDescription = "Avatar",
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
        )
        Box(
            modifier = Modifier
                .size((size.value * 0.38f).dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
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
    var text by remember { mutableStateOf("") }
    val canSend = text.isNotBlank()
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
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (canSend) {
                        onSend(text.trim())
                        text = ""
                        keyboard?.hide()
                    }
                }
            )
        )

        IconButton(
            enabled = canSend,
            onClick = {
                onSend(text.trim())
                text = ""
                keyboard?.hide()
            },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Filled.Send, contentDescription = "Send")
        }
    }
}
