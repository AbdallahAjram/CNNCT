package com.example.cnnct.settings.model

data class UserProfile(
    val uid: String,
    val displayName: String = "",
    val phone: String? = null,
    val about: String? = null,
    val photoUrl: String? = null
) {
    fun initials(): String =
        displayName.trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "U" }
}