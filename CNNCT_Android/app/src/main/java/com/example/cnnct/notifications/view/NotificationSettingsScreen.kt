package com.example.cnnct.notifications.view

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cnnct.notifications.NotifPrefs
import com.example.cnnct.notifications.SettingsCache
import com.example.cnnct.notifications.controller.NotificationSettingsViewModel
import com.example.cnnct.notifications.model.NotificationSettings


/**
 * Host composable that connects VM → UI. Keeps the pure UI stateless & previewable.
 */
@Composable
fun NotificationSettingsScreenHost(vm: NotificationSettingsViewModel) {
    val state by vm.state.collectAsState()

    // ✅ Keep local cache in sync immediately when toggles change
    val ctx = LocalContext.current
    LaunchedEffect(state) {
        SettingsCache.save(
            ctx,
            NotifPrefs(
                notificationsEnabled = state.notificationsEnabled,
                chatNotificationsEnabled = state.chatNotificationsEnabled,
                callNotificationsEnabled = state.callNotificationsEnabled
            )
        )
    }

    NotificationSettingsScreen(
        state = state,
        onToggleGlobal = vm::setGlobal,
        onToggleChats = vm::setChats,
        onToggleCalls = vm::setCalls
    )
}
private val FooterGray = Color(0xFF6B7280)
/**
 * Stateless UI. Easy to preview & test.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    state: NotificationSettings,
    onToggleGlobal: (Boolean) -> Unit,
    onToggleChats: (Boolean) -> Unit,
    onToggleCalls: (Boolean) -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Notification Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        // finish the current activity (go back)
                        (context as? ComponentActivity)?.finish()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            SectionHeader("Global")
            SettingRow(
                icon = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                title = "Enable notifications",
                subtitle = "Master switch for messages and calls",
                checked = state.notificationsEnabled,
                enabled = true,
                onCheckedChange = onToggleGlobal
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            val childrenEnabled = state.notificationsEnabled

            SectionHeader("Channels")
            SettingRow(
                icon = { Icon(Icons.Outlined.Chat, contentDescription = null) },
                title = "Chat notifications",
                subtitle = "Show notifications for messages",
                checked = state.chatNotificationsEnabled,
                enabled = childrenEnabled,
                onCheckedChange = onToggleChats
            )

            SettingRow(
                icon = { Icon(Icons.Outlined.Call, contentDescription = null) },
                title = "Call notifications",
                subtitle = "Show incoming call alerts",
                checked = state.callNotificationsEnabled,
                enabled = childrenEnabled,
                onCheckedChange = onToggleCalls
            )

            Spacer(Modifier.height(24.dp))

            FooterNote(
                text = if (!childrenEnabled)
                    "Turn on the master switch to manage chat/call notifications."
                else
                    "You can mute individual chats from the chat info screen."
            )

            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("CNNCT© 2025", color = FooterGray, fontSize = 12.sp)
            }

        }
    }
}
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingRow(
    icon: @Composable (() -> Unit)?,
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val alpha = if (enabled) 1f else 0.5f

    ListItem(
        leadingContent = { if (icon != null) icon() },
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(alpha)
            )
        },
        supportingContent = {
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(alpha)
                )
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .alpha(alpha)
    )
}

@Composable
private fun FooterNote(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NotificationSettingsPreview_On() {
    NotificationSettingsScreen(
        state = NotificationSettings(
            notificationsEnabled = true,
            chatNotificationsEnabled = true,
            callNotificationsEnabled = false
        ),
        onToggleGlobal = {},
        onToggleChats = {},
        onToggleCalls = {}
    )
}

@Preview(showBackground = true)
@Composable
private fun NotificationSettingsPreview_Off() {
    NotificationSettingsScreen(
        state = NotificationSettings(
            notificationsEnabled = false,
            chatNotificationsEnabled = true,
            callNotificationsEnabled = true
        ),
        onToggleGlobal = {},
        onToggleChats = {},
        onToggleCalls = {}
    )
}
