package com.abdallah.cnnct.notifications.model

data class NotificationSettings(
    val notificationsEnabled: Boolean = true,   // Global (messages + calls)
    val chatNotificationsEnabled: Boolean = true,
    val callNotificationsEnabled: Boolean = true
)
