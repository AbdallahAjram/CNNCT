package com.abdallah.cnnct.notifications.view

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.abdallah.cnnct.notifications.viewmodel.NotificationSettingsRepository
import com.abdallah.cnnct.notifications.viewmodel.NotificationSettingsViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class NotificationSettingsActivity : ComponentActivity() {

    private val vm: NotificationSettingsViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repo = NotificationSettingsRepository(
                    FirebaseFirestore.getInstance(),
                    FirebaseAuth.getInstance()
                )
                return NotificationSettingsViewModel(repo) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NotificationSettingsScreenHost(vm)
        }
    }
}
