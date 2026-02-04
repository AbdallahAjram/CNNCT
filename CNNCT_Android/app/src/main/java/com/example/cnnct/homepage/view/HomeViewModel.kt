package com.example.cnnct.homepage.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cnnct.chat.mvc.model.ChatRepository
import com.example.cnnct.chat.core.repository.UserRepository
import com.example.cnnct.homepage.model.ChatSummary
import com.example.cnnct.settings.model.UserProfile
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeUiState(
    val chats: List<ChatSummary> = emptyList(),
    val archivedChats: List<ChatSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    
    // Search
    val searchResults: List<UserProfile> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null
)

class HomeViewModel(
    private val chatRepo: ChatRepository,
    private val userRepo: UserRepository
) : ViewModel() {

    private val currentUserId: String = try { userRepo.me() } catch (e: Exception) { "" }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        if (currentUserId.isNotBlank()) {
            observeChats()
            startDeliveryPolling()
        } else {
            _uiState.update { it.copy(loading = false, error = "Not signed in") }
        }
    }

    private fun startDeliveryPolling() {
        viewModelScope.launch {
            while (true) {
                try {
                    chatRepo.promoteIncomingLastMessagesToDelivered(currentUserId)
                } catch (e: Exception) {
                    // ignore
                }
                kotlinx.coroutines.delay(25_000) // 25s
            }
        }
    }

    private fun observeChats() {
        val chatsFlow = chatRepo.listenMyChats(currentUserId)
        val metaFlow = chatRepo.listenMyUserChatMeta(currentUserId)

        combine(chatsFlow, metaFlow) { chats, metaMap ->
            val processed = chats.map { chat ->
                val meta = metaMap[chat.id]
                // 1. Cleared Mask
                val clearedBefore = meta?.clearedBefore
                val lastMs = chat.lastMessageTimestamp?.toDate()?.time ?: Long.MIN_VALUE
                val clearedMs = clearedBefore?.toDate()?.time ?: Long.MIN_VALUE

                val maskedChat = if (lastMs <= clearedMs) {
                    chat.copy(
                        lastMessageText = "",
                        lastMessageTimestamp = null,
                        lastMessageSenderId = null,
                        lastMessageIsRead = false,
                        lastMessageStatus = null
                    )
                } else {
                    chat
                }

                // Attach meta flags for filtering context if needed (though we filter below)
                maskedChat
            }
            
            // 2. Filter Lists
            val homeChats = processed.filter { chat ->
                val meta = metaMap[chat.id]
                val isHidden = meta?.hidden == true
                val isArchived = meta?.archived == true
                
                // Resilience: Unhide if new message arrived after hidden
                val chatTs = chat.lastMessageTimestamp?.toDate()?.time ?: 0L
                val metaTs = meta?.updatedAt?.toDate()?.time ?: 0L
                
                // If chat is newer than the meta-update (hide/archive action), show it!
                if (chatTs > metaTs) {
                    true 
                } else {
                    !isHidden && !isArchived
                }
            }.sortedByDescending { 
                it.lastMessageTimestamp?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: 0L 
            }

            val archivedChats = processed.filter { chat ->
                val meta = metaMap[chat.id]
                val isHidden = meta?.hidden == true
                val isArchived = meta?.archived == true
                !isHidden && isArchived
            }.sortedByDescending {
                it.lastMessageTimestamp?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: 0L
            }

            Pair(homeChats, archivedChats)
        }.onEach { (home, archived) ->
            _uiState.update { it.copy(chats = home, archivedChats = archived, loading = false, error = null) }
        }.flowOn(kotlinx.coroutines.Dispatchers.Default).catch { e ->
            _uiState.update { it.copy(error = e.message) }
        }.launchIn(viewModelScope)
    }

    // ========== Actions ==========

    fun searchUsers(query: String) {
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        _uiState.update { it.copy(isSearching = true, searchError = null) }
        viewModelScope.launch {
            try {
                val results = userRepo.searchUsers(query)
                _uiState.update { it.copy(searchResults = results, isSearching = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(searchError = e.message, isSearching = false) }
            }
        }
    }
    
    fun clearSearchResults() {
         _uiState.update { it.copy(searchResults = emptyList(), isSearching = false, searchError = null) }
    }

    fun muteChat(chatId: String) = viewModelScope.launch {
        chatRepo.muteChatForever(currentUserId, chatId)
    }

    fun muteChatFor(chatId: String, hours: Long) = viewModelScope.launch {
        chatRepo.muteChatForHours(currentUserId, chatId, hours)
    }

    fun unmuteChat(chatId: String) = viewModelScope.launch {
        chatRepo.unmuteChat(currentUserId, chatId)
    }

    fun archiveChat(chatId: String) = viewModelScope.launch {
        chatRepo.setArchived(currentUserId, chatId, true)
    }
    
    fun unarchiveChat(chatId: String) = viewModelScope.launch {
        chatRepo.setArchived(currentUserId, chatId, false)
    }

    fun deleteChatForMe(chatId: String) = viewModelScope.launch {
        chatRepo.hideChat(currentUserId, chatId)
    }
    
    // Actually, let's fix the repo action right now in code.
    // I'll manually implement it here via a new repo extension? No, strict layering.
    // I'll add `hideChat` to repository later. For now, I'll use `clearChatForMe`.
    
    fun createPrivateChat(otherUserId: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        try {
            val chatId = userRepo.getOrCreatePrivateChatWith(otherUserId)
            onResult(chatId)
        } catch (e: Exception) {
            onResult(null)
        }
    }
    
    fun markRead(chatId: String) = viewModelScope.launch {
        chatRepo.markRead(chatId, currentUserId, null) // msgId null means "all/latest" effectively for badge logic
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val repo = com.cnnct.chat.mvc.model.FirestoreChatRepository(db)
                val userRepo = com.example.cnnct.chat.core.repository.UserRepository(db)
                return HomeViewModel(repo, userRepo) as T
            }
        }
    }
}
