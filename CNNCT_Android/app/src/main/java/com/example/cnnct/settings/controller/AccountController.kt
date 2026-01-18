package com.example.cnnct.settings.controller

import com.example.cnnct.settings.model.AccountRepository
import com.example.cnnct.settings.model.UserProfile
import kotlinx.coroutines.flow.Flow

class AccountController(
    private val repo: AccountRepository
) {
    // Expose profile as flow for reactive updates
    val profileFlow: Flow<UserProfile> = repo.profileFlow as Flow<UserProfile>

    suspend fun refreshProfile() = repo.refreshProfile()

    suspend fun updateDisplayName(name: String) {
        if (name.isBlank()) return
        repo.updateDisplayName(name)
        repo.refreshProfile()
    }

    suspend fun updateAbout(about: String) {
        repo.updateAbout(about)
        repo.refreshProfile()
    }
}
