package com.abdallah.cnnct.homepage.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cnnct.chat.mvc.model.ChatRepository
import com.abdallah.cnnct.chat.core.repository.UserRepository
import com.abdallah.cnnct.homepage.model.ChatSummary
import com.abdallah.cnnct.settings.model.UserProfile
import com.abdallah.cnnct.common.state.ComponentState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeUiState(
    val chatListState: ComponentState<Pair<List<ChatSummary>, List<ChatSummary>>> = ComponentState.Loading,
    val searchState: ComponentState<List<UserProfile>> = ComponentState.Success(emptyList()),
    val presenceState: ComponentState<Map<String, Long?>> = ComponentState.Success(emptyMap()),
    val isSearching: Boolean = false,
    val currentUserProfileUrl: String? = null
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
            observeMyProfile()
            observeChats()
            observePresence()
            startDeliveryPolling()
            viewModelScope.launch { userRepo.ensureSearchName() }
        } else {
            _uiState.update { it.copy(chatListState = ComponentState.Error("Not signed in", "Auth Error", "Retry")) }
        }
    }

    private fun observeMyProfile() {
        viewModelScope.launch {
            userRepo.listenMyProfile().collect { profile ->
                val authPhoto = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.photoUrl?.toString()
                val resolvedUrl = profile?.photoUrl ?: authPhoto
                _uiState.update { it.copy(currentUserProfileUrl = resolvedUrl) }
            }
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
                maskedChat.isPinned = meta?.pinned == true
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
            }.sortedWith(
                compareByDescending<ChatSummary> { it.isPinned }
                    .thenByDescending { it.lastMessageTimestamp?.toDate()?.time ?: it.createdAt?.toDate()?.time ?: 0L }
            )

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
            _uiState.update { it.copy(chatListState = ComponentState.Success(Pair(home, archived))) }
        }.flowOn(kotlinx.coroutines.Dispatchers.Default).catch { e ->
            _uiState.update { it.copy(chatListState = ComponentState.Error("Unable to load chats", e.message ?: "Connection lost", "Retry")) }
        }.launchIn(viewModelScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePresence() {
        _uiState.map { it.chatListState }
            .distinctUntilChanged()
            .flatMapLatest { state ->
                if (state is ComponentState.Success) {
                    val allChats = state.data.first + state.data.second
                    val targets = allChats
                        .flatMap { it.members + (it.lastMessageSenderId ?: "") }
                        .filter { it.isNotBlank() && it != currentUserId }
                        .distinct()
                    
                    userRepo.listenPresence(targets)
                        .map { ComponentState.Success(it) as ComponentState<Map<String, Long?>> }
                        .catch { e -> emit(ComponentState.Error("Presence offline", e.message ?: "Network error", "Retry")) }
                } else {
                    flowOf(ComponentState.Success(emptyMap()))
                }
            }
            .onEach { presence ->
                _uiState.update { it.copy(presenceState = presence) }
            }
            .launchIn(viewModelScope)
    }

    // ========== Actions ==========

    fun searchUsers(query: String) {
        if (query.isBlank()) {
            _uiState.update { it.copy(searchState = ComponentState.Success(emptyList()), isSearching = false) }
            return
        }
        _uiState.update { it.copy(isSearching = true, searchState = ComponentState.Loading) }
        viewModelScope.launch {
            try {
                val results = userRepo.searchUsers(query)
                _uiState.update { it.copy(searchState = ComponentState.Success(results), isSearching = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(searchState = ComponentState.Error("Search failed", e.message ?: "Network issue", "Retry"), isSearching = false) }
            }
        }
    }
    
    fun clearSearchResults() {
         _uiState.update { it.copy(searchState = ComponentState.Success(emptyList()), isSearching = false) }
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

    fun pinChat(chatId: String) = viewModelScope.launch {
        chatRepo.setPinned(currentUserId, chatId, true)
    }

    fun unpinChat(chatId: String) = viewModelScope.launch {
        chatRepo.setPinned(currentUserId, chatId, false)
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
                val userRepo = com.abdallah.cnnct.chat.core.repository.UserRepository(db)
                return HomeViewModel(repo, userRepo) as T
            }
        }
    }
}
