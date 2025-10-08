package com.example.cnnct.chat.mvc.controller

import kotlinx.coroutines.tasks.await

object BlockController {
    private val db get() = com.google.firebase.firestore.FirebaseFirestore.getInstance()
    private val auth get() = com.google.firebase.auth.FirebaseAuth.getInstance()

    fun blockUser(peerId: String, onDone: (Boolean) -> Unit) {
        val me = auth.currentUser?.uid ?: return onDone(false)
        val ref = db.collection("users").document(me)
            .collection("blocks").document(peerId)
        ref.set(
            mapOf(
                "blocked" to true,
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
        ).addOnSuccessListener { onDone(true) }
            .addOnFailureListener { onDone(false) }
    }

    fun unblockUser(peerId: String, onDone: (Boolean) -> Unit) {
        val me = auth.currentUser?.uid ?: return onDone(false)
        val ref = db.collection("users").document(me)
            .collection("blocks").document(peerId)
        ref.delete().addOnSuccessListener { onDone(true) }
            .addOnFailureListener { onDone(false) }
    }

    /** Live set of ids I have blocked */
    fun listenMyBlocked(onChange: (Set<String>) -> Unit): com.google.firebase.firestore.ListenerRegistration? {
        val me = auth.currentUser?.uid ?: return null
        return db.collection("users").document(me)
            .collection("blocks")
            .addSnapshotListener { snap, _ ->
                val ids = snap?.documents?.map { it.id }?.toSet() ?: emptySet()
                onChange(ids)
            }
    }

    /** Check if peer has blocked me (for banners) — one-shot */
    suspend fun isBlockedByPeer(peerId: String): Boolean {
        val me = auth.currentUser?.uid ?: return false
        val doc = db.collection("users").document(peerId)
            .collection("blocks").document(me).get().await()
        return doc.exists()
    }
}
