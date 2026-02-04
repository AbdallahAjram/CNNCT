package com.example.cnnct.groups.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cnnct.chat.core.repository.UserRepository
import com.example.cnnct.groups.repository.GroupRepository
import com.example.cnnct.homepage.model.ChatSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GroupsUiState(
    val groups: List<ChatSummary> = emptyList(),
    val loading: Boolean = true,
    val userMap: Map<String, String> = emptyMap(),
    val error: String? = null,
    val currentUserId: String = ""
)

class GroupsViewModel(
    private val groupRepo: GroupRepository,
    private val userRepo: UserRepository
) : ViewModel() {

    private val _state = MutableStateFlow(GroupsUiState())
    val state = _state.asStateFlow()

    init {
        try {
            _state.value = _state.value.copy(currentUserId = groupRepo.me())
            viewModelScope.launch {
                groupRepo.observeUserGroups().collect { groups ->
                    updateGroupsAndFetchNames(groups)
                }
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message, loading = false)
        }
    }

    private fun updateGroupsAndFetchNames(groups: List<ChatSummary>) {
        val currentMap = _state.value.userMap.toMutableMap()
        val missingUids = mutableSetOf<String>()
        val myId = _state.value.currentUserId

        groups.forEach { g ->
            (g.members + (g.lastMessageSenderId ?: "")).filter { it.isNotBlank() && it != myId }.forEach { uid ->
                if (!currentMap.containsKey(uid)) {
                    missingUids.add(uid)
                }
            }
        }

        _state.value = _state.value.copy(
            groups = groups,
            loading = false
        )

        if (missingUids.isNotEmpty()) {
            viewModelScope.launch {
                try {
                    val fetched = userRepo.getUsers(missingUids.toList())
                    fetched.forEach { u ->
                        currentMap[u.uid] = u.displayName
                    }
                    _state.value = _state.value.copy(userMap = currentMap)
                } catch (e: Exception) {
                    // Log error but don't fail UI
                }
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupsViewModel(GroupRepository(), UserRepository())
            }
        }
    }
}
