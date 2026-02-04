package com.example.cnnct.homepage.view

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.cnnct.chat.view.ChatRoute
import com.google.firebase.auth.FirebaseAuth
import androidx.compose.ui.platform.LocalContext

import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType

class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // callsController removed. CallsViewModel in MainPagerScreen handles monitoring.

        FirebaseAuth.getInstance().currentUser?.getIdToken(true)
            ?.addOnSuccessListener { token ->
                Log.d("TOKEN", token.token ?: "")
            }

        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

        setContent {
            // --- runtime permission handling on entry ---
            val permissions = remember {
                mutableStateListOf<String>().apply {
                    // RECORD_AUDIO moved to InCallActivity (JIT)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            val denied = remember { mutableStateListOf<String>() }
            var checked by rememberSaveable { mutableStateOf(false) }

            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                denied.clear()
                result.forEach { (perm, granted) ->
                    if (!granted) denied.add(perm)
                }
                checked = true
            }

            LaunchedEffect(Unit) {
                val stillNeeded = permissions.filter {
                    ContextCompat.checkSelfPermission(this@HomeActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (stillNeeded.isNotEmpty()) {
                    launcher.launch(stillNeeded.toTypedArray())
                } else {
                    checked = true
                }
            }

            // UI
            if (!checked) {
                // simple loading screen while permissions dialog runs
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                // Setup Navigation
                val navController = rememberNavController()
                
                // Deep Link Handler
                LaunchedEffect(Unit) {
                    val chatId = intent.getStringExtra("chatId")
                    if (chatId != null) {
                        val encoded = android.net.Uri.encode(chatId)
                        navController.navigate("chat/$encoded")
                    }
                    
                    // FCM Token Registration
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val token = task.result
                            com.example.cnnct.messaging.FcmTokenManager.registerToken(token)
                        }
                    }
                }
                
                NavHost(navController = navController, startDestination = "home") {
                    
                    // Route: Main Pager (Chats, Groups, Calls, Settings)
                    composable("home") {
                        MainPagerScreen(navController = navController)
                    }
                    
                    // Route: Chat Detail
                    composable(
                        route = "chat/{chatId}",
                        arguments = listOf(navArgument("chatId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
                        
                        // Manual VM Factory
                        val context = LocalContext.current
                        val db = remember { com.google.firebase.firestore.FirebaseFirestore.getInstance() }
                        val repo = remember { com.cnnct.chat.mvc.model.FirestoreChatRepository(db) }
                        
                        val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                                return com.cnnct.chat.mvc.controller.ChatViewModel(repo, currentUserId) as T
                            }
                        }
                        
                        val viewModel: com.cnnct.chat.mvc.controller.ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
                        
                        ChatRoute(
                            chatId = chatId,
                            currentUserId = currentUserId,
                            viewModel = viewModel,
                            navController = navController
                        )
                    }
                }

                if (denied.isNotEmpty()) {
                    // optional gentle reminder snackbar/toast
                    LaunchedEffect(denied.joinToString()) {
                        Log.w("Permissions", "Denied: ${denied.joinToString()}")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Calls logic handled by ViewModel cleanup
    }
}
