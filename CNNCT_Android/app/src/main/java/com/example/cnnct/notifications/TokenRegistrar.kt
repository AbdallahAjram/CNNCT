// app/src/main/java/com/example/cnnct/notifications/TokenRegistrar.kt
package com.example.cnnct.notifications

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object TokenRegistrar {
    suspend fun upsertToken(context: Context, token: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val deviceId = DeviceId.get(context)
        val docRef = FirebaseFirestore.getInstance().collection("users").document(user.uid)
        val payload = mapOf(
            "fcmTokens.$deviceId.token" to token,
            "fcmTokens.$deviceId.platform" to "android",
            "fcmTokens.$deviceId.updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.update(payload).await()
        } catch (e: Exception) {
            // If user doc doesn't exist yet, create it with this map
            try {
                docRef.set(mapOf("fcmTokens" to mapOf(deviceId to mapOf(
                    "token" to token,
                    "platform" to "android",
                    "updatedAt" to FieldValue.serverTimestamp()
                ))), com.google.firebase.firestore.SetOptions.merge()).await()
            } catch (e2: Exception) {
                Log.e("TokenRegistrar", "Failed to save token: ${e2.message}")
            }
        }
    }

    suspend fun removeToken(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val deviceId = DeviceId.get(context)
        val docRef = FirebaseFirestore.getInstance().collection("users").document(user.uid)
        try {
            docRef.update(mapOf("fcmTokens.$deviceId" to FieldValue.delete())).await()
        } catch (_: Exception) { /* ignore */ }
    }
}
