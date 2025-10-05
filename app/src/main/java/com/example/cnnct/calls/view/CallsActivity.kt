package com.example.cnnct.calls.view

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.calls.model.UserCallLog
import com.example.cnnct.calls.repository.CallRepository
import com.example.cnnct.homepage.view.BottomNavigationBar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CallsActivity : ComponentActivity() {
    private lateinit var controller: CallsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = CallsController(this, CallRepository())

        setContent {
            Scaffold(
                bottomBar = { BottomNavigationBar(currentScreen = "calls") }
            ) { padding ->
                CallsScreen(
                    controller = controller,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.clear()
    }

}