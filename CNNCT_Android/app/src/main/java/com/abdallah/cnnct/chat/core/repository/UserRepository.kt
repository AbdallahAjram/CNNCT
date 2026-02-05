package com.abdallah.cnnct.chat.core.repository


import com.abdallah.cnnct.settings.model.UserProfile
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.tasks.await

class UserRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    fun me(): String = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")

    suspend fun getUser(uid: String): UserProfile {
        val d = db.collection("users").document(uid).get().await()
        return d.toObject(UserProfile::class.java) ?: UserProfile(uid, "Unknown")
    }

    suspend fun getUsers(uids: List<String>): List<UserProfile> {
        if (uids.isEmpty()) return emptyList()
        val chunks = uids.distinct().chunked(10)
        val out = mutableListOf<UserProfile>()
        for (chunk in chunks) {
            val snap = db.collection("users")
                .whereIn(FieldPath.documentId(), chunk)
                .get().await()
            out.addAll(snap.toObjects(UserProfile::class.java))
        }
        return out
    }

    suspend fun blockPeer(peerId: String) {
        db.collection("users").document(me())
            .collection("blocks").document(peerId)
            .set(mapOf("blocked" to true, "createdAt" to Timestamp.now()))
            .await()
    }

    suspend fun unblockPeer(peerId: String) {
        db.collection("users").document(me())
            .collection("blocks").document(peerId)
            .delete()
            .await()
    }

    fun listenBlockedPeers(): kotlinx.coroutines.flow.Flow<Set<String>> = kotlinx.coroutines.flow.callbackFlow {
        val uid = try { me() } catch(e: Exception) { "" }
        if (uid.isBlank()) {
            trySend(emptySet())
            close()
            return@callbackFlow
        }
        val reg = db.collection("users").document(uid).collection("blocks")
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    trySend(emptySet())
                    return@addSnapshotListener
                }
                val set = snap?.documents?.filter { it.getBoolean("blocked") == true }?.map { it.id }?.toSet() ?: emptySet()
                trySend(set)
            }
        awaitClose { reg.remove() }
    }

    suspend fun searchUsers(query: String, limit: Int = 20): List<UserProfile> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val results = mutableMapOf<String, UserProfile>()
        
        // Name search
        val nameSnap = db.collection("users")
            .whereGreaterThanOrEqualTo("displayName", q)
            .whereLessThanOrEqualTo("displayName", q + '\uf8ff')
            .limit(limit.toLong())
            .get().await()
        results.putAll(nameSnap.toObjects(UserProfile::class.java).associateBy { it.uid })

        // Phone search (if digits present)
        val digits = q.filter { it.isDigit() }
        if (digits.length >= 2) {
            val phoneSnap = db.collection("users")
                .whereGreaterThanOrEqualTo("phoneNumber", digits)
                .whereLessThanOrEqualTo("phoneNumber", digits + '\uf8ff')
                .limit(limit.toLong())
                .get().await()
            results.putAll(phoneSnap.toObjects(UserProfile::class.java).associateBy { it.uid })
        }

        results.remove(me())
        return results.values.take(limit).toList()
    }

    suspend fun getRecentPrivateChatPeers(limit: Int = 30): List<UserProfile> {
        val my = me()
        val priv = db.collection("chats")
            .whereArrayContains("members", my)
            .whereEqualTo("type", "private")
            .limit(50)
            .get().await()

        val peerIds = priv.documents.mapNotNull { d ->
            val members = d.get("members") as? List<String> ?: emptyList()
            members.firstOrNull { it != my }
        }.distinct().take(limit)

        return getUsers(peerIds)
    }

    suspend fun getOrCreatePrivateChatWith(peerId: String): String {
        val my = me()
        require(peerId.isNotBlank() && peerId != my) { "Invalid peerId" }

        val key = if (my <= peerId) "$my#$peerId" else "$peerId#$my"
        val chatId = "priv_$key"
        val ref = db.collection("chats").document(chatId)

        val snap = ref.get().await()
        if (snap.exists()) return chatId

        val payload = mapOf(
            "type" to "private",
            "members" to listOf(my, peerId),
            "pairKey" to key,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        ref.set(payload).await()
        return chatId
    }
    suspend fun deleteUserData() {
        val uid = me()
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
    suspend fun reportUser(reporterId: String, reportedId: String, reason: String) {
        val report = mapOf(
            "reporterId" to reporterId,
            "reportedId" to reportedId,
            "reason" to reason,
            "createdAt" to FieldValue.serverTimestamp()
        )
        db.collection("reports").add(report).await()
    }
}
