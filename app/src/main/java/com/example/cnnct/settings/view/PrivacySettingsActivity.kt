package com.example.cnnct.settings.view

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class PrivacySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PrivacySettingsScreen(onBack = { finish() })
        }
    }
}
