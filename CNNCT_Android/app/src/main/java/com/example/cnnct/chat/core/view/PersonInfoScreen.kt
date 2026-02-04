package com.example.cnnct.chat.view

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.cnnct.R
import com.example.cnnct.chat.viewmodel.PersonInfoUiState
import com.example.cnnct.chat.viewmodel.PersonInfoViewModel
import com.example.cnnct.homepage.view.HomeActivity
import com.example.cnnct.calls.view.InCallActivity
import kotlinx.coroutines.launch

/* ------------------------ Screen (Stateful Wrapper) ------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonInfoScreen(
    uid: String,
    nav: NavController,
    vm: PersonInfoViewModel = viewModel(factory = PersonInfoViewModel.factory(uid))
) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = ctx as? Activity

    LaunchedEffect(vm) {
        vm.errorEvents.collect { msg: String ->
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    PersonInfoContent(
        state = state,
        onBack = { activity?.finish() },
        onCall = {
            scope.launch {
                try {
                    val repo = com.example.cnnct.calls.repository.CallRepository()
                    val callId = repo.createCall(uid, "call_${System.currentTimeMillis()}")
                    val intent = Intent(ctx, com.example.cnnct.calls.view.InCallActivity::class.java).apply {
                        putExtra("callId", callId)
                        putExtra("callerId", com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid)
                        putExtra("channelId", "call_${System.currentTimeMillis()}")
                    }
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    // ignore
                }
            }
        },
        onChat = {
            scope.launch {
                runCatching {
                    val chatId = vm.getOrCreatePrivateChatId()
                    Intent(ctx, HomeActivity::class.java)
                        .putExtra("chatId", chatId)
                }.onSuccess { intent ->
                    ctx.startActivity(intent)
                }.onFailure {
                    android.widget.Toast.makeText(ctx, "Failed to create chat", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        },
        onBlock = { vm.block() },
        onReport = { reason -> vm.reportUser(reason) }
    )
}

/* ------------------------ Content (Stateless) ------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonInfoContent(
    state: PersonInfoUiState,
    onBack: () -> Unit,
    onCall: () -> Unit,
    onChat: () -> Unit,
    onBlock: () -> Unit,
    onReport: (String) -> Unit
) {
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Contact info") },
                navigationIcon = {
                    BackButton(onBack)
                }
            )
        }
    ) { pad ->
        when {
            state.loading -> LoadingBox(Modifier.padding(pad))
            state.error != null -> ErrorBox(state.error!!, Modifier.padding(pad))
            else -> state.profile?.let { u ->
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(pad)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Avatar with fallback
                    if (u.photoUrl.isNullOrBlank()) {
                        Image(
                            painter = painterResource(R.drawable.defaultpp),
                            contentDescription = null,
                            modifier = Modifier.size(120.dp)
                        )
                    } else {
                        BigAvatar(u.photoUrl)
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(u.displayName, style = MaterialTheme.typography.headlineSmall)
                    u.about?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Call button
                        OutlinedButton(onClick = onCall) {
                            Icon(Icons.Rounded.Call, null); Spacer(Modifier.width(8.dp)); Text("Call")
                        }

                        // Chat button
                        FilledTonalButton(onClick = onChat) {
                            Icon(Icons.Rounded.Chat, null); Spacer(Modifier.width(8.dp)); Text("Chat")
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    InfoCard(title = "Phone", subtitle = u.phoneNumber ?: "—")

                    Spacer(Modifier.height(24.dp))
                    DangerCard(label = "Block user", onClick = onBlock)
                    
                    Spacer(Modifier.height(16.dp))
                    
                    var showReportDialog by remember { mutableStateOf(false) }
                    TextButton(onClick = { showReportDialog = true }) {
                        Text("Report user", color = MaterialTheme.colorScheme.error)
                    }
                    
                    if (showReportDialog) {
                        val reasons = listOf("Spam", "Harassment", "Offensive Content", "Other")
                        AlertDialog(
                            onDismissRequest = { showReportDialog = false },
                            title = { Text("Report User") },
                            text = {
                                Column {
                                    Text("Why are you reporting this user?")
                                    Spacer(Modifier.height(8.dp))
                                    reasons.forEach { reason ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    onReport(reason)
                                                    showReportDialog = false
                                                }
                                                .padding(vertical = 12.dp)
                                        ) {
                                            Text(reason, style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showReportDialog = false }) { Text("Cancel") }
                            }
                        )
                    }
                }
            }
        }
    }
}
