package com.example.cnnct.settings.controller

class SettingsController {
    fun handleNavigation(option: String): String =
        when (option) {
            "Account" -> "account"
            "Privacy" -> "privacy"
            "Archived Chats" -> "archived"
            "Notifications" -> "notifications"
            "Blocked Accounts" -> "blocked"
            else -> ""
        }
}
