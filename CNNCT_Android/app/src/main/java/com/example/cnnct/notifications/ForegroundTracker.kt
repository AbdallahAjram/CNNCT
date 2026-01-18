// app/src/main/java/com/example/cnnct/notifications/ForegroundTracker.kt
package com.example.cnnct.notifications

import java.util.concurrent.atomic.AtomicReference

object ForegroundTracker {
    private val currentChat = AtomicReference<String?>(null)

    fun setCurrentChat(chatId: String?) {
        currentChat.set(chatId)
    }

    fun getCurrentChat(): String? = currentChat.get()
}
