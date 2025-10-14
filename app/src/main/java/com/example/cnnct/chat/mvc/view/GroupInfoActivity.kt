package com.example.cnnct.chat.view

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.navigation.compose.rememberNavController

class GroupInfoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chatId = intent.getStringExtra("chatId") ?: return
        setContent {
            Surface(color = MaterialTheme.colorScheme.background) {
                val nav = rememberNavController()
                GroupInfoScreen(chatId = chatId, nav = nav)
            }
        }
    }
}
