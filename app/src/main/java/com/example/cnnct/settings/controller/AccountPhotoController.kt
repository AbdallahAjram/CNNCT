package com.example.cnnct.settings.controller

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

object AccountPhotoController {
    private val storage by lazy { FirebaseStorage.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    suspend fun uploadAndSaveAvatar(croppedImageUri: Uri): String {
        val uid = auth.currentUser?.uid ?: error("Not logged in")

        // Store under avatars/{uid}/avatar.jpg (matches rules above)
        val ref = storage.reference.child("avatars/$uid/avatar.jpg")

        // Upload
        ref.putFile(croppedImageUri).await()

        // Get URL
        val downloadUrl = ref.downloadUrl.await().toString()

        // Save on user doc
        db.collection("users").document(uid).update("photoUrl", downloadUrl).await()

        return downloadUrl
    }
}