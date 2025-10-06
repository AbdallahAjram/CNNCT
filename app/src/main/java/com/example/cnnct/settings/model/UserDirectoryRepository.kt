package com.example.cnnct.settings.model

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Lightweight directory to read user fields (photoUrl, displayName) for UI.
 * You can place it in a shared module/package if you like.
 */
class UserDirectoryRepository(
    private val db: FirebaseFirestore
) {
    private fun userDoc(uid: String) = db.collection("users").document(uid)

    suspend fun getPhotoUrl(uid: String): String? =
        userDoc(uid).get().await().getString("photoUrl")

    fun observePhotoUrl(uid: String): Flow<String?> = callbackFlow {
        val reg = userDoc(uid).addSnapshotListener { snap, _ ->
            trySend(snap?.getString("photoUrl"))
        }
        awaitClose { reg.remove() }
    }

    suspend fun getDisplayName(uid: String): String? =
        userDoc(uid).get().await().getString("displayName")

    fun observeDisplayName(uid: String): Flow<String?> = callbackFlow {
        val reg = userDoc(uid).addSnapshotListener { snap, _ ->
            trySend(snap?.getString("displayName"))
        }
        awaitClose { reg.remove() }
    }
}
