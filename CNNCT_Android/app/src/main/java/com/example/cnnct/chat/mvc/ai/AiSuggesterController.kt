package com.example.cnnct.chat.mvc.ai

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * ViewModel controlling AI suggestion state and lifecycle.
 */
class AiSuggesterController(
    private val repo: AiRepository = AiRepository()
) : ViewModel() {

    val isLoading = mutableStateOf(false)
    val suggestions = mutableStateOf<List<String>>(emptyList())
    val error = mutableStateOf<String?>(null)

    fun fetchSuggestions(selectedMessages: List<String>) {
        Log.d("AI_Suggester", "fetchSuggestions() called with ${selectedMessages.size} messages")

        if (selectedMessages.isEmpty()) {
            Log.w("AI_Suggester", "selectedMessages list is EMPTY. Aborting.")
            error.value = "Please select at least one message."
            return
        }

        // Log message contents
        selectedMessages.forEachIndexed { index, msg ->
            Log.d("AI_Suggester", "Message[$index]: \"$msg\"")
        }

        val prompt = AiPromptBuilder.buildPrompt(selectedMessages)
        Log.d("AI_Suggester", "Built prompt:\n$prompt")

        viewModelScope.launch {
            try {
                isLoading.value = true
                Log.d("AI_Suggester", "Sending prompt to Firebase Function...")
                val replies = repo.getSuggestions(prompt)
                Log.d("AI_Suggester", "Firebase Function returned: $replies")
                if (replies != null && replies.isNotEmpty()) {
                    suggestions.value = replies
                    error.value = null
                } else {
                    error.value = "No suggestions available."
                }
            } catch (e: Exception) {
                Log.e("AI_Suggester", "Error fetching suggestions", e)
                error.value = "Error fetching suggestions."
            } finally {
                isLoading.value = false
                Log.d("AI_Suggester", "AI fetch done. isLoading=false")
            }
        }
    }

    fun clear() {
        Log.d("AI_Suggester", "Clearing state (suggestions + errors)")
        suggestions.value = emptyList()
        error.value = null
    }
}
