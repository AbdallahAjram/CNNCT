package com.example.cnnct.settings.model

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Source of truth: /users/{uid}
 * Fields used by Account page:
 *  - displayName: String
 *  - phone: String (read-only in UI)
 *  - about: String
 *  - photoUrl: String? (used later when we add upload)
 */
class AccountRepository(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore
) {
    private fun requireUid(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("No authenticated user")

    private fun userDoc() = db.collection("users").document(requireUid())

    suspend fun getProfile(): UserProfile {
        val uid = requireUid()
        val snap = userDoc().get().await()

        val displayName = snap.getString("displayName") ?: ""
        val phone = snap.getString("phoneNumber") // you said you added this at signup ✅
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

        // Optional: mirror to FirebaseAuth profile for consistency
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

    // For later:
    // suspend fun updatePhotoUrl(url: String) {
    //     userDoc().set(mapOf("photoUrl" to url), SetOptions.merge()).await()
    //     val updates = userProfileChangeRequest { photoUri = Uri.parse(url) }
    //     auth.currentUser?.updateProfile(updates)?.await()
    // }
}
