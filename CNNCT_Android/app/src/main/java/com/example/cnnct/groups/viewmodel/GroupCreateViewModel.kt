package com.example.cnnct.groups.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cnnct.chat.core.repository.UserRepository
import com.example.cnnct.groups.repository.GroupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SelectableUser(
    val uid: String,
    val name: String,
    val phone: String? = null,
    val selected: Boolean = false
)

data class CreateGroupUiState(
    val loadingPeers: Boolean = true,
    val searching: Boolean = false,
    val creating: Boolean = false,
    val candidates: List<SelectableUser> = emptyList(),
    val error: String? = null,
    val createdChatId: String? = null
)

class GroupCreateViewModel(
    private val userRepo: UserRepository,
    private val groupRepo: GroupRepository
) : ViewModel() {

    private val _state = MutableStateFlow(CreateGroupUiState())
    val state = _state.asStateFlow()

    init {
        loadInitialPeers()
    }

    private fun loadInitialPeers() = viewModelScope.launch {
        _state.value = _state.value.copy(loadingPeers = true)
        try {
            val peers = userRepo.getRecentPrivateChatPeers()
            val candidates = peers.map { 
                SelectableUser(it.uid, it.displayName, it.phoneNumber)
            }.sortedBy { it.name.lowercase() }
            
            _state.value = _state.value.copy(
                candidates = candidates, 
                loadingPeers = false
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                loadingPeers = false, 
                error = e.message
            )
        }
    }

    fun searchUsers(query: String) = viewModelScope.launch {
        val q = query.trim()
        if (q.length < 2) return@launch

        _state.value = _state.value.copy(searching = true)
        try {
            val results = userRepo.searchUsers(q)
            
            // Merge with existing candidates to preserve selection
            val currentCandidates = _state.value.candidates
            val existingMap = currentCandidates.associateBy { it.uid }
            
            val merged = LinkedHashMap<String, SelectableUser>()
            // Preserve current list order/selection
            currentCandidates.forEach { merged[it.uid] = it }
            
            // Add new results
            for (u in results) {
                val existing = existingMap[u.uid]
                merged[u.uid] = SelectableUser(
                    uid = u.uid,
                    name = u.displayName,
                    phone = u.phoneNumber,
                    selected = existing?.selected == true
                )
            }
            
            _state.value = _state.value.copy(
                searching = false,
                candidates = merged.values.toList().sortedBy { it.name.lowercase() }
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(searching = false)
        }
    }

    fun toggleSelection(uid: String) {
        val current = _state.value.candidates
        val updated = current.map { 
            if (it.uid == uid) it.copy(selected = !it.selected) else it 
        }
        _state.value = _state.value.copy(candidates = updated)
    }

    fun createGroup(name: String, description: String?, iconUri: Uri?) = viewModelScope.launch {
        val selectedIds = _state.value.candidates.filter { it.selected }.map { it.uid }
        if (name.isBlank()) return@launch

        _state.value = _state.value.copy(creating = true, error = null)
        try {
            val chatId = groupRepo.createGroup(name.trim(), description, selectedIds, iconUri)
            _state.value = _state.value.copy(creating = false, createdChatId = chatId)
        } catch (e: Exception) {
            _state.value = _state.value.copy(creating = false, error = e.message)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun resetCreatedChatId() {
        _state.value = _state.value.copy(createdChatId = null)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val userRepo = UserRepository()
                val groupRepo = GroupRepository()
                GroupCreateViewModel(userRepo, groupRepo)
            }
        }
    }
}
