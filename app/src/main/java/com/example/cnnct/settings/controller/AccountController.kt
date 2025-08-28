package com.example.cnnct.settings.controller

import com.example.cnnct.settings.model.AccountRepository
import com.example.cnnct.settings.model.UserProfile

class AccountController(
    private val repo: AccountRepository
) {
    suspend fun getProfile(): UserProfile = repo.getProfile()

    suspend fun updateDisplayName(name: String) {
        if (name.isBlank()) return
        repo.updateDisplayName(name)
    }

    suspend fun updateAbout(about: String) {
        // You can add length validation here if you want
        repo.updateAbout(about)
    }
}
