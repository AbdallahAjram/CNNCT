package com.example.cnnct.settings.model

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Source of truth: /users/{uid}
 */
class AccountRepository(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore
) {
    private val _profileFlow = MutableStateFlow<UserProfile?>(null)
    val profileFlow = _profileFlow.asStateFlow()

    private fun requireUid(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("No authenticated user")

    private fun userDoc() = db.collection("users").document(requireUid())

    suspend fun refreshProfile() {
        val profile = getProfile()
        _profileFlow.value = profile
    }

    suspend fun getProfile(): UserProfile {
        val uid = requireUid()
        val snap = userDoc().get().await()
        val displayName = snap.getString("displayName") ?: ""
        val phone = snap.getString("phoneNumber")
        val about = snap.getString("about")
        val photoUrl = snap.getString("photoUrl")

        return UserProfile(
            uid = uid,
            displayName = displayName,
            phone = phone,
            about = about,
            photoUrl = photoUrl
        )
    }

    suspend fun updateDisplayName(name: String) {
        userDoc().set(
            mapOf("displayName" to name),
            com.google.firebase.firestore.SetOptions.merge()
        ).await()

        val updates = com.google.firebase.auth.userProfileChangeRequest {
            displayName = name
        }
        auth.currentUser?.updateProfile(updates)?.await()
    }

    suspend fun updateAbout(about: String) {
        userDoc().set(
            mapOf("about" to about),
            com.google.firebase.firestore.SetOptions.merge()
        ).await()
    }
}
