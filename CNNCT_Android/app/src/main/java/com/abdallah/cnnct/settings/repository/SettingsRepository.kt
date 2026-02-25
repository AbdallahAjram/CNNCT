package com.abdallah.cnnct.settings.repository

import android.net.Uri
import com.abdallah.cnnct.settings.model.UserProfile
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
        android.util.Log.d("ProfileDebug", "refreshProfile: Fetching for uid=$uid")
        val snap = db.collection("users").document(uid).get(com.google.firebase.firestore.Source.SERVER).await()
        val data = snap.toObject(UserProfile::class.java)
        if (data != null) {
            android.util.Log.d("ProfileDebug", "refreshProfile: Data fetched successfully: $data")
            _profileFlow.value = data

        } else {
            android.util.Log.d("ProfileDebug", "refreshProfile: Parsing manual fields")
            // Fallback if not using toObject or custom fields
            val newData = UserProfile(
                uid = uid,
                displayName = snap.getString("displayName") ?: "",
                phoneNumber = snap.getString("phoneNumber"),
                about = snap.getString("about"),
                photoUrl = snap.getString("photoUrl")
            )
            _profileFlow.value = newData
            android.util.Log.d("ProfileDebug", "refreshProfile: New data emitted manually: $newData")
        }
    }

    suspend fun updateDisplayName(name: String) {
        val uid = requireUid()
        android.util.Log.d("ProfileDebug", "updateDisplayName: Updating to '$name' for uid=$uid")
        db.collection("users").document(uid).set(
            mapOf(
                "displayName" to name,
                "searchName" to name.trim().lowercase()
            ),
            SetOptions.merge()
        ).await()
        android.util.Log.d("ProfileDebug", "updateDisplayName: Firestore update complete for name='$name'")

        val updates = com.google.firebase.auth.userProfileChangeRequest {
            displayName = name
        }
        auth.currentUser?.updateProfile(updates)?.await()
    }

    suspend fun updateAbout(about: String) {
        val uid = requireUid()
        android.util.Log.d("ProfileDebug", "updateAbout: Updating to '$about' for uid=$uid")
        db.collection("users").document(uid).set(
            mapOf("about" to about),
            SetOptions.merge()
        ).await()
        android.util.Log.d("ProfileDebug", "updateAbout: Firestore update complete for about='$about'")
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
    suspend fun deleteUserData() {
        val uid = requireUid()
        // Best-effort client-side wipes of known collections
        
        // 1. users/{uid}
        db.collection("users").document(uid).delete().await()
        
        // 2. userChats/{uid} - Subcollection requires manual delete of docs
        try {
            val userChats = db.collection("userChats").document(uid).collection("chats").get().await()
            for (doc in userChats) {
                doc.reference.delete()
            }
            db.collection("userChats").document(uid).delete().await()
        } catch (e: Exception) { /* ignore */ }
        
        // 3. userCalls/{uid}
        try {
             db.collection("userCalls").document(uid).collection("calls").get().await().forEach { it.reference.delete() }
             db.collection("userCalls").document(uid).delete().await()
        } catch (e: Exception) { /* ignore */ }
    }
}
