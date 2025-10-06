package com.example.cnnct.settings.controller

class SettingsController {
    fun handleNavigation(option: String): String =
        when (option) {
            "Account" -> "account"
            "Privacy" -> "privacy"
            "Themes" -> "themes"
            "Notifications" -> "notifications"
            "Blocked Accounts" -> "blocked"
            else -> ""
        }
}
