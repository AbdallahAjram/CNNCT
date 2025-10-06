package com.example.cnnct.messaging

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

object FcmTokenManager {
    private val db = Firebase.firestore
    private val auth = FirebaseAuth.getInstance()

    fun registerToken(token: String) {
        val uid = auth.currentUser?.uid ?: return
        val doc = db.collection("fcmTokens").document(uid).collection("tokens").document(token)
        val data = mapOf("token" to token, "createdAt" to FieldValue.serverTimestamp())
        doc.set(data)
    }

    fun unregisterToken(token: String) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("fcmTokens").document(uid).collection("tokens").document(token).delete()
    }
}
