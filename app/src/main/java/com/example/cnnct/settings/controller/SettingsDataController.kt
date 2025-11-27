// controller/SettingsDataController.kt
package com.example.cnnct.settings.controller

import com.example.cnnct.settings.model.UserSettings
import com.example.cnnct.settings.model.UserSettingsRepository
import kotlinx.coroutines.flow.Flow

class SettingsDataController(
    private val repo: UserSettingsRepository
) {
    suspend fun getOnce(): UserSettings = repo.get()

    fun observe(): Flow<UserSettings> = repo.observe()

    suspend fun setReadReceipts(enabled: Boolean) = repo.setReadReceipts(enabled)

    suspend fun setNotificationsEnabled(enabled: Boolean) = repo.setNotificationsEnabled(enabled)

    suspend fun muteChat(chatId: String) = repo.muteChat(chatId)

    suspend fun unmuteChat(chatId: String) = repo.unmuteChat(chatId)

    suspend fun setMutedChats(ids: List<String>) = repo.setMutedChats(ids)
}
