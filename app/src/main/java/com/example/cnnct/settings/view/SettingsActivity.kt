package com.example.cnnct.settings.view

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.material3.Scaffold
import com.example.cnnct.homepage.view.BottomNavigationBar
import com.example.cnnct.notifications.NotifPrefs
import com.example.cnnct.notifications.SettingsCache
import com.example.cnnct.notifications.view.NotificationSettingsActivity
import com.example.cnnct.settings.controller.SettingsController
import com.example.cnnct.settings.controller.SettingsDataController
import com.example.cnnct.settings.model.UserSettingsRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    private val controller = SettingsController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repo = UserSettingsRepository(
            auth = FirebaseAuth.getInstance(),
            db = FirebaseFirestore.getInstance()
        )
        val dataController = SettingsDataController(repo)

        // Keep local cache in sync (so PushService can read instantly)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataController.observe().collect { settings ->
                    SettingsCache.save(
                        this@SettingsActivity,
                        NotifPrefs(
                            notificationsEnabled = settings.notificationsEnabled,
                            chatNotificationsEnabled = settings.chatNotificationsEnabled,
                            callNotificationsEnabled = settings.callNotificationsEnabled
                        )
                    )
                }
            }
        }

        setContent {
            Scaffold(
                bottomBar = { BottomNavigationBar(currentScreen = "settings") }
            ) { paddingValues ->
                SettingsScreen(
                    contentPadding = paddingValues,
                    onBackClick = { finish() },
                    onNavigate = { option ->
                        when (controller.handleNavigation(option)) {
                            "account" -> startActivity(Intent(this, AccountActivity::class.java))
                            "privacy" -> { /* TODO */ }
                            "themes" -> { /* TODO */ }
                            "notifications" -> startActivity(Intent(this, NotificationSettingsActivity::class.java))
                            "blocked" -> { /* TODO */ }
                        }
                    }
                )
            }
        }
    }
}
