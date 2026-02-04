package com.example.cnnct.chat.core.ai

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

class AiRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("europe-west1")
) {

    suspend fun getSuggestions(prompt: String): List<String>? {
        return try {
            Log.d("AI_Repository", "Calling Firebase Function generateSmartReplies...")
            val result = functions
                .getHttpsCallable("generateSmartReplies")
                .call(mapOf("prompt" to prompt))
                .await()
                .data as? Map<*, *>

            Log.d("AI_Repository", "Raw Firebase result: $result")

            @Suppress("UNCHECKED_CAST")
            val replies = (result?.get("replies") as? List<*>)?.mapNotNull { it as? String }
            Log.d("AI_Repository", "Parsed replies: $replies")
            replies
        } catch (e: Exception) {
            Log.e("AI_Repository", "Error calling Firebase Function", e)
            null
        }
    }
}
