package com.example.cnnct.calls.view

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cnnct.homepage.view.BottomNavigationBar
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.calls.repository.CallRepository

class CallsActivity : ComponentActivity() {
    private lateinit var controller: CallsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = CallsController(this, CallRepository())
        setContent {
            Scaffold(
                bottomBar = {
                    BottomNavigationBar(currentScreen = "calls")
                }
            ) { paddingValues ->
                CallsScreen(controller = controller, modifier = Modifier.padding(paddingValues))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.clear()
    }
}
