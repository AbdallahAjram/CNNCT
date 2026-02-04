package com.example.cnnct.groups.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cnnct.chat.model.GroupInfo
import com.example.cnnct.settings.model.UserProfile
import com.example.cnnct.chat.core.repository.UserRepository
import com.example.cnnct.groups.repository.GroupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GroupInfoUiState(
    val loading: Boolean = true,
    val group: GroupInfo? = null,
    val members: List<UserProfile> = emptyList(),
    val error: String? = null,
    val me: String = ""
)

class GroupInfoViewModel(
    private val chatId: String,
    private val groupRepo: GroupRepository,
    private val userRepo: UserRepository
) : ViewModel() {

    private val _state = MutableStateFlow(GroupInfoUiState())
    val state = _state.asStateFlow()

    init {
        refreshAll()
    }

    private fun refreshAll() = viewModelScope.launch {
        try {
            val me = groupRepo.me()
            val g = groupRepo.getGroup(chatId)
            val members = userRepo.getUsers(g.members)
            _state.value = GroupInfoUiState(
                loading = false,
                group = g,
                members = members,
                me = me
            )
        } catch (e: Exception) {
            _state.value = GroupInfoUiState(loading = false, error = e.message)
        }
    }

    fun leaveGroup(onLeft: () -> Unit) = viewModelScope.launch {
        runCatching { groupRepo.leaveGroup(chatId) }
            .onSuccess { onLeft() }
    }

    fun removeMember(uid: String) = viewModelScope.launch {
        runCatching { groupRepo.removeMembers(chatId, listOf(uid)) }
            .onSuccess { refreshAll() }
    }

    suspend fun addMembers(uids: List<String>) {
        runCatching { groupRepo.addMembers(chatId, uids) }
            .onSuccess { refreshAll() }
    }

    suspend fun loadRecentContacts(): List<UserProfile> {
        val currentMembers = _state.value.group?.members?.toSet().orEmpty()
        return runCatching { userRepo.getRecentPrivateChatPeers() }
            .getOrDefault(emptyList())
            .filterNot { it.uid in currentMembers }
    }

    suspend fun searchUsers(query: String, limit: Int): List<UserProfile> {
        val currentMembers = _state.value.group?.members?.toSet().orEmpty()
        return runCatching { userRepo.searchUsers(query, limit) }
            .getOrDefault(emptyList())
            .filterNot { it.uid in currentMembers }
    }

    fun makeAdmin(uid: String) = viewModelScope.launch {
        runCatching { groupRepo.makeAdmin(chatId, uid) }.onSuccess { refreshAll() }
    }

    fun revokeAdmin(uid: String) = viewModelScope.launch {
        runCatching { groupRepo.revokeAdmin(chatId, uid) }.onSuccess { refreshAll() }
    }

    fun updateGroupNameAndDescription(name: String, description: String?) = viewModelScope.launch {
        runCatching { groupRepo.updateGroupNameAndDescription(chatId, name, description) }
            .onSuccess { refreshAll() }
    }

    suspend fun updateGroupPhoto(imageUri: Uri): Boolean {
        return groupRepo.updateGroupPhoto(chatId, imageUri).also { if (it) refreshAll() }
    }

    companion object {
        fun factory(chatId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupInfoViewModel(
                    chatId,
                    GroupRepository(),
                    UserRepository()
                )
            }
        }
    }
}
