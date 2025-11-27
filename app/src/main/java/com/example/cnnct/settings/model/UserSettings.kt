package com.example.cnnct.settings.model

/**
 * Per-user settings that we actually store in Firestore.
 * No "last seen". Keep read receipts + notifications toggle + muted chats.
 * App language and wallpaper are global (see AppConfig).
 */
data class UserSettings(
    val readReceipts: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val mutedChats: List<String> = emptyList()
)
