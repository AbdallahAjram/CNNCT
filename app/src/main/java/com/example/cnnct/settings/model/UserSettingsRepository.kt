package com.example.cnnct.settings.model

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Repository for reading/writing user settings under: /users/{uid}
 * Fields:
 *  - readReceipts: Boolean
 *  - notificationsEnabled: Boolean
 *  - mutedChats: List<String>
 */
class UserSettingsRepository(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore
) {

    private fun requireUid(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("No authenticated user")

    private fun userDoc() = db.collection("users").document(requireUid())

    private val defaultSettings = UserSettings(
        readReceipts = true,
        notificationsEnabled = true,
        mutedChats = emptyList()
    )

    /** One-shot fetch. Creates defaults if the doc doesn't exist. */
    suspend fun get(): UserSettings {
        val snap = userDoc().get().await()
        if (!snap.exists()) {
            // Create doc with defaults once
            userDoc().set(defaultSettings.toMap()).await()
            return defaultSettings
        }
        return snap.toUserSettings(defaultSettings)
    }

    /** Real-time subscription to settings changes. Emits defaults if the doc doesn't exist yet. */
    fun observe(): Flow<UserSettings> = callbackFlow {
        val reg = userDoc().addSnapshotListener { snap, err ->
            if (err != null) {
                close(err)
                return@addSnapshotListener
            }
            val settings = if (snap != null && snap.exists()) {
                snap.toUserSettings(defaultSettings)
            } else {
                defaultSettings
            }
            trySend(settings)
        }
        awaitClose { reg.remove() }
    }

    /** Bulk update with a partial map. */
    suspend fun update(partial: Map<String, Any?>) {
        // Merge to avoid overwriting other fields
        userDoc().set(partial, com.google.firebase.firestore.SetOptions.merge()).await()
    }

    suspend fun setReadReceipts(enabled: Boolean) {
        update(mapOf("readReceipts" to enabled))
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        update(mapOf("notificationsEnabled" to enabled))
    }

    suspend fun muteChat(chatId: String) {
        userDoc().update("mutedChats", FieldValue.arrayUnion(chatId)).await()
    }

    suspend fun unmuteChat(chatId: String) {
        userDoc().update("mutedChats", FieldValue.arrayRemove(chatId)).await()
    }

    suspend fun setMutedChats(chatIds: List<String>) {
        update(mapOf("mutedChats" to chatIds))
    }

    // region Helpers

    private fun UserSettings.toMap(): Map<String, Any> = mapOf(
        "readReceipts" to readReceipts,
        "notificationsEnabled" to notificationsEnabled,
        "mutedChats" to mutedChats
    )

    private fun com.google.firebase.firestore.DocumentSnapshot.toUserSettings(
        defaults: UserSettings
    ): UserSettings {
        val readReceipts = getBoolean("readReceipts") ?: defaults.readReceipts
        val notificationsEnabled = getBoolean("notificationsEnabled") ?: defaults.notificationsEnabled
        val mutedChats = (get("mutedChats") as? List<*>)?.filterIsInstance<String>() ?: defaults.mutedChats
        return UserSettings(
            readReceipts = readReceipts,
            notificationsEnabled = notificationsEnabled,
            mutedChats = mutedChats
        )
    }
    // endregion
}
