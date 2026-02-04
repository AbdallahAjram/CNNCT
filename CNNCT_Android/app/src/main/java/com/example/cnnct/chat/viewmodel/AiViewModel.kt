package com.example.cnnct.chat.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cnnct.chat.core.ai.AiPromptBuilder
import com.example.cnnct.chat.core.ai.AiRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AiUiState(
    val isLoading: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val error: String? = null
)

/**
 * ViewModel for AI Smart Replies.
 */
class AiViewModel(
    private val repo: AiRepository = AiRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AiUiState())
    val uiState = _uiState.asStateFlow()

    fun fetchSuggestions(selectedMessages: List<String>) {
        Log.d("AI_VM", "fetchSuggestions() called with ${selectedMessages.size} messages")

        if (selectedMessages.isEmpty()) {
            _uiState.update { it.copy(error = "Please select at least one message.") }
            return
        }

        val prompt = AiPromptBuilder.buildPrompt(selectedMessages)

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }
                val replies = repo.getSuggestions(prompt)
                
                if (!replies.isNullOrEmpty()) {
                    _uiState.update { it.copy(suggestions = replies, isLoading = false) }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "No suggestions available.") }
                }
            } catch (e: Exception) {
                Log.e("AI_VM", "Error fetching suggestions", e)
                _uiState.update { it.copy(isLoading = false, error = "Error fetching suggestions.") }
            }
        }
    }

    fun clear() {
        _uiState.update { AiUiState() }
    }
}
