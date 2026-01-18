package com.example.cnnct.chat.mvc.ai

import android.util.Log

object AiPromptBuilder {

    fun buildPrompt(selectedMessages: List<String>): String {
        val contextText = selectedMessages.joinToString("\n") { "- $it" }
        val prompt = """
            You are a helpful chat assistant in a messaging app.
            Based on this short conversation:
            $contextText

            Suggest 3 short, natural, human-like replies (each under 20 words)
            that match the tone and language of the messages.
            Reply **only** in valid JSON array format:
            ["reply1", "reply2", "reply3"]
        """.trimIndent()
        Log.d("AI_PromptBuilder", "Prompt built (${selectedMessages.size} messages):\n$prompt")
        return prompt
    }
}
