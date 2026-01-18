package com.example.cnnct.chat.view

import android.app.Activity
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.cnnct.R
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.chat.controller.ChatInfoController
import com.example.cnnct.chat.model.UserProfile
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Contact info") },
                navigationIcon = {
                    BackButton {
                        // NavController here has no back stack because this is a separate Activity.
                        // Finish the Activity instead of popBackStack().
                        activity?.finish()
                    }
                }
            )
        }
    ) { pad ->
        when {
            state.loading -> LoadingBox()
            state.error != null -> ErrorBox(state.error!!)
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
                        OutlinedButton(onClick = {
                            val calls = CallsController(ctx)
                            calls.startCall(
                                uid,
                                onCreated = { /* handled inside controller */ },
                                onError = { /* could show a toast/snackbar */ }
                            )
                        }) {
                            Icon(Icons.Rounded.Call, null); Spacer(Modifier.width(8.dp)); Text("Call")
                        }

                        // Chat button: robust and null-safe
                        FilledTonalButton(onClick = {
                            scope.launch {
                                runCatching {
                                    val chatId = vm.getOrCreatePrivateChatId()
                                    Intent(ctx, ChatActivity::class.java)
                                        .putExtra("chatId", chatId)
                                }.onSuccess { intent ->
                                    ctx.startActivity(intent)
                                }.onFailure {
                                    // TODO toast/snackbar if you want; but won’t crash the app
                                }
                            }
                        }) {
                            Icon(Icons.Rounded.Chat, null); Spacer(Modifier.width(8.dp)); Text("Chat")
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    InfoCard(title = "Phone", subtitle = u.phoneNumber ?: "—")

                    Spacer(Modifier.height(24.dp))
                    DangerCard(label = "Block user") { vm.block() }
                }
            }
        }
    }
}

/** Lightweight VM kept inside chat feature folder */
class PersonInfoViewModel(
    private val uid: String,
    private val ctrl: ChatInfoController
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val profile: UserProfile? = null,
        val error: String? = null
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { ctrl.getUser(uid) }
                .onSuccess { _state.value = UiState(loading = false, profile = it) }
                .onFailure { _state.value = UiState(loading = false, error = it.message) }
        }
    }

    fun block() = viewModelScope.launch {
        runCatching { ctrl.blockPeer(uid) }
            .onFailure { /* TODO snackbar/toast */ }
    }

    /** Used by the "Chat" button: find or create a private chat with [uid]. */
    suspend fun getOrCreatePrivateChatId(): String {
        return ctrl.getOrCreatePrivateChatWith(uid)
    }

    companion object {
        fun factory(uid: String) = viewModelFactory {
            initializer { PersonInfoViewModel(uid, ChatInfoController()) }
        }
    }
}
