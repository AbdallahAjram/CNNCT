package com.abdallah.cnnct.settings.model

data class UserSettings(
    val readReceipts: Boolean = true,
    val notificationsEnabled: Boolean = true,     // global
    val chatNotificationsEnabled: Boolean = true, // messages
    val callNotificationsEnabled: Boolean = true, // calls
    val mutedChats: List<String> = emptyList()
)
