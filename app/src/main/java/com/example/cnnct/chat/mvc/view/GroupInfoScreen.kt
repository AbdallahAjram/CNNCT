package com.example.cnnct.chat.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.cnnct.chat.controller.ChatInfoController
import com.example.cnnct.chat.controller.ChatNav.openPeerProfile
import com.example.cnnct.chat.controller.openPeerProfile
import com.example.cnnct.chat.model.GroupInfo
import com.example.cnnct.chat.model.UserProfile
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    chatId: String,
    nav: NavController,
    vm: GroupInfoViewModel = viewModel(factory = GroupInfoViewModel.factory(chatId))
) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Group info") },
                navigationIcon = { BackButton { nav.popBackStack() } }
            )
        }
    ) { pad ->
        when {
            state.loading -> LoadingBox()
            state.error != null -> ErrorBox(state.error!!)
            else -> {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(pad)
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BigAvatar(state.group?.groupPhotoUrl)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(state.group?.groupName ?: "", style = MaterialTheme.typography.headlineSmall)
                            state.group?.groupDescription?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text("Members", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    state.members.forEach { u ->
                        ListItem(
                            headlineContent = { Text(u.displayName) },
                            supportingContent = { Text(u.phoneNumber ?: "") },
                            leadingContent = { SmallAvatar(u.photoUrl) },
                            modifier = Modifier.clickable { nav.openPeerProfile(u.uid) }
                        )
                        Divider()
                    }

                    Spacer(Modifier.height(24.dp))
                    DangerCard(label = "Leave group") { vm.leaveGroup { nav.popBackStack() } }
                }
            }
        }
    }
}

class GroupInfoViewModel(
    private val chatId: String,
    private val ctrl: ChatInfoController
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val group: GroupInfo? = null,
        val members: List<UserProfile> = emptyList(),
        val error: String? = null
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { ctrl.getGroup(chatId) }
                .onSuccess { g ->
                    val members = runCatching { ctrl.getUsers(g.members) }.getOrDefault(emptyList())
                    _state.value = UiState(loading = false, group = g, members = members)
                }
                .onFailure { _state.value = UiState(loading = false, error = it.message) }
        }
    }

    fun leaveGroup(onLeft: () -> Unit) = viewModelScope.launch {
        runCatching { ctrl.leaveGroup(chatId) }
            .onSuccess { onLeft() }
            .onFailure { /* TODO: snackbar/toast */ }
    }

    companion object {
        fun factory(chatId: String) = viewModelFactory {
            initializer { GroupInfoViewModel(chatId, ChatInfoController()) }
        }
    }
}
