package com.abdallah.cnnct.homepage.view

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.abdallah.cnnct.auth.view.LoginActivity
import com.abdallah.cnnct.calls.view.IncomingCallActivity
import com.abdallah.cnnct.calls.view.CallsScreen
import com.abdallah.cnnct.groups.view.GroupScreen
import com.abdallah.cnnct.settings.view.SettingsScreen
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import android.app.Activity
import com.abdallah.cnnct.calls.view.IncomingCallScreen

@Composable
fun MainPagerScreen(
    navController: NavController,
    callsViewModel: com.abdallah.cnnct.calls.viewmodel.CallsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = com.abdallah.cnnct.calls.viewmodel.CallsViewModel.Factory)
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 4 pages: Chats, Groups, Calls, Settings
    val pagerState = rememberPagerState(pageCount = { 4 })
    
    // Incoming Call Logic (Moved from HomeScreen)
    val callsUiState by callsViewModel.uiState.collectAsState()
    val incomingCall = callsUiState.incomingCall

    LaunchedEffect(incomingCall) {
        incomingCall?.let { call ->
            val myUid = FirebaseAuth.getInstance().currentUser?.uid
            val isRecent = call.createdAt?.let { ts ->
                val age = System.currentTimeMillis() - ts.toDate().time
                age <= 30_000
            } ?: false

            if (call.status == "ringing" && call.calleeId == myUid && isRecent) {
                val i = Intent(context, IncomingCallActivity::class.java).apply {
                    putExtra("callId", call.callId)
                    putExtra("callerId", call.callerId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(i)
            }
        }
    }

    // Overlay for in-app ringing
    incomingCall?.let { call ->
        val myUid = FirebaseAuth.getInstance().currentUser?.uid
        val isRecent = call.createdAt?.let { ts ->
            val age = System.currentTimeMillis() - ts.toDate().time
            age <= 30_000
        } ?: false

        if (call.status == "ringing" && call.calleeId == myUid && isRecent) {
             IncomingCallScreen(
                callerId = call.callerId,
                onAccept = { callsViewModel.acceptCall(call.callId) },
                onReject = { callsViewModel.rejectCall(call.callId) }
            )
        }
    }


    // Handle Back Press - Single Click with Spam Protection
    var isFinishing by remember { mutableStateOf(false) }
    
    BackHandler {
        if (isFinishing) return@BackHandler // 🛑 Ignore all clicks after the first exit trigger

        if (pagerState.currentPage != 0) {
            scope.launch { pagerState.scrollToPage(0) }
        } else {
            // Latch and Minimize (move to background instead of destroying)
            isFinishing = true
            (context as? Activity)?.moveTaskToBack(true)
            
            // Optional: reset latch after a second in case they come back? 
            // Actually, if we move to back, the activity pauses.
            // If they return, isFinishing is still true?
            // Yes, remember survives.
            // So we should reset isFinishing to false when/if the composable (or activity) resumes?
            // Or just launch a coroutine to reset it after delay?
            scope.launch {
                kotlinx.coroutines.delay(2000)
                isFinishing = false
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val current = pagerState.currentPage
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Chat, "Chats") },
                    label = { Text("Chats") },
                    selected = current == 0,
                    onClick = { scope.launch { pagerState.scrollToPage(0) } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Group, "Groups") },
                    label = { Text("Groups") },
                    selected = current == 1,
                    onClick = { scope.launch { pagerState.scrollToPage(1) } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Call, "Calls") },
                    label = { Text("Calls") },
                    selected = current == 2,
                    onClick = { scope.launch { pagerState.scrollToPage(2) } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, "Settings") },
                    label = { Text("Settings") },
                    selected = current == 3,
                    onClick = { scope.launch { pagerState.scrollToPage(3) } }
                )
            }
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> ChatListScreen(
                        onChatClick = { chatId ->
                            val encoded = android.net.Uri.encode(chatId)
                            navController.navigate("chat/$encoded")
                        },
                        onLogout = {
                            FirebaseAuth.getInstance().signOut()
                            context.startActivity(Intent(context, LoginActivity::class.java))
                        }
                    )
                    1 -> GroupScreen(
                        onChatClick = { chatId ->
                            val encoded = android.net.Uri.encode(chatId)
                            navController.navigate("chat/$encoded")
                        }
                    )
                    2 -> CallsScreen(viewModel = callsViewModel)
                    3 -> SettingsScreen(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp), // or 0.dp
                        onBackClick = { /* No-op or switch tab? Pager usually doesn't back out */ },
                        onNavigate = { dest ->
                           when(dest) {
                               "Account" -> context.startActivity(Intent(context, com.abdallah.cnnct.settings.view.AccountActivity::class.java))
                               "Privacy" -> context.startActivity(Intent(context, com.abdallah.cnnct.settings.view.PrivacySettingsActivity::class.java))
                               "Notifications" -> context.startActivity(Intent(context, com.abdallah.cnnct.notifications.view.NotificationSettingsActivity::class.java))
                               "Archived Chats" -> context.startActivity(Intent(context, com.abdallah.cnnct.settings.view.ArchiveSettingsActivity::class.java))
                               "Blocked Accounts" -> context.startActivity(Intent(context, com.abdallah.cnnct.settings.view.BlockedSettingsActivity::class.java))
                               else -> {}
                           }
                        }
                    ) 
                }
            }
        }
    }
}
