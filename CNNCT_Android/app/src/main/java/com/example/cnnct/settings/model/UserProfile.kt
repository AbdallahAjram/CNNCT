package com.example.cnnct.settings.model

import com.google.firebase.firestore.DocumentId

data class UserProfile(
    @DocumentId
    val uid: String = "",
    val displayName: String = "",
    val phoneNumber: String? = null,
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