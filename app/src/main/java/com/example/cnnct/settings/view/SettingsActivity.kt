// SettingsActivity.kt
package com.example.cnnct.settings.view

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cnnct.homepage.view.BottomNavigationBar
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import com.example.cnnct.homepage.view.BottomNavigationBar
import com.example.cnnct.settings.controller.SettingsController
import com.example.cnnct.settings.controller.SettingsDataController
import com.example.cnnct.settings.model.UserSettingsRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SettingsActivity : ComponentActivity() {
    private val controller = SettingsController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = UserSettingsRepository(
            auth = FirebaseAuth.getInstance(),
            db = FirebaseFirestore.getInstance()
        )
        val dataController = SettingsDataController(repo)
        setContent {
            // ⬇️ Keep the app-wide bottom nav as requested
            Scaffold(
                bottomBar = {
                    BottomNavigationBar(currentScreen = "settings")
                }
            ) { paddingValues ->
                SettingsScreen(
                    contentPadding = paddingValues,
                    onBackClick = { finish() },
                    onNavigate = { option ->
                        // Map the tapped row to a route/tag; wire this to your NavHost or Intents
                        when (controller.handleNavigation(option)) {
                            "account" -> {
                                val intent = Intent(this, AccountActivity::class.java)
                                startActivity(intent)
                            }
                            "privacy" -> {/* TODO: navigate to Privacy */}
                            "themes" -> {/* TODO: navigate to Themes */}
                            "notifications" -> {/* TODO: navigate to Notifications */}
                            "blocked" -> {/* TODO: navigate to Blocked Accounts */}
                        }
                    }
                )
            }
        }
    }
}