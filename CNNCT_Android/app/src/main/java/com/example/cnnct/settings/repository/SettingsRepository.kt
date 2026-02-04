package com.example.cnnct.settings.repository

import android.net.Uri
import com.example.cnnct.settings.model.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storageMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

class SettingsRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) {
    private val _profileFlow = MutableStateFlow<UserProfile?>(null)
    val profileFlow = _profileFlow.asStateFlow()

    private fun requireUid(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("No authenticated user")

    suspend fun refreshProfile() {
        val uid = requireUid()
        val snap = db.collection("users").document(uid).get().await()
        val data = snap.toObject(UserProfile::class.java)
        if (data != null) {
            _profileFlow.value = data
        } else {
            // Fallback if not using toObject or custom fields
            _profileFlow.value = UserProfile(
                uid = uid,
                displayName = snap.getString("displayName") ?: "",
                phoneNumber = snap.getString("phoneNumber"),
                about = snap.getString("about"),
                photoUrl = snap.getString("photoUrl")
            )
        }
    }

    suspend fun updateDisplayName(name: String) {
        val uid = requireUid()
        db.collection("users").document(uid).set(
            mapOf("displayName" to name),
            SetOptions.merge()
        ).await()

        val updates = com.google.firebase.auth.userProfileChangeRequest {
            displayName = name
        }
        auth.currentUser?.updateProfile(updates)?.await()
    }

    suspend fun updateAbout(about: String) {
        val uid = requireUid()
        db.collection("users").document(uid).set(
            mapOf("about" to about),
            SetOptions.merge()
        ).await()
    }

    suspend fun uploadAndSaveAvatar(uri: Uri) {
        val uid = requireUid()
        val ref = storage.reference.child("avatars/$uid/avatar.jpg")
        val md = storageMetadata { contentType = "image/jpeg" }
        
        ref.putFile(uri, md).await()
        val url = ref.downloadUrl.await().toString()
        
        db.collection("users").document(uid).update("photoUrl", url).await()
        
        val updates = com.google.firebase.auth.userProfileChangeRequest {
            photoUri = Uri.parse(url)
        }
        auth.currentUser?.updateProfile(updates)?.await()
    }
}
