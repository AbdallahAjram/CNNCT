package com.abdallah.cnnct.settings.view

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.abdallah.cnnct.notifications.view.NotificationSettingsActivity
import com.abdallah.cnnct.ui.theme.CNNCTTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CNNCTTheme {
                SettingsScreen(
                    contentPadding = PaddingValues(0.dp),
                    onBackClick = { finish() },
                    onNavigate = { dest ->
                        when (dest) {
                            "Account" -> startActivity(Intent(this, AccountActivity::class.java))
                            "Privacy" -> startActivity(Intent(this, PrivacySettingsActivity::class.java))
                            "Notifications" -> startActivity(Intent(this, NotificationSettingsActivity::class.java))
                            "Archived Chats" -> startActivity(Intent(this, ArchiveSettingsActivity::class.java))
                            "Blocked Accounts" -> startActivity(Intent(this, BlockedSettingsActivity::class.java))
                            else -> {}
                        }
                    }
                )
            }
        }
    }
}
