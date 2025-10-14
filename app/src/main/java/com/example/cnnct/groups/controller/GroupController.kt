package com.example.cnnct.groups.controller

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storageMetadata
import kotlinx.coroutines.tasks.await

data class SimpleUser(val uid: String, val displayName: String, val phone: String?)

object GroupController {
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    suspend fun fetchPrivateChatPeers(me: String): List<String> {
        if (me.isBlank()) return emptyList()
        val snap = db.collection("chats")
            .whereEqualTo("type", "private")
            .whereArrayContains("members", me)
            .get().await()

        return snap.documents.mapNotNull { d ->
            val members = d.get("members") as? List<String> ?: emptyList()
            members.firstOrNull { it != me }
        }.distinct()
    }

    suspend fun fetchUsersByIds(uids: List<String>): List<SimpleUser> {
        if (uids.isEmpty()) return emptyList()
        val out = mutableListOf<SimpleUser>()
        for (chunk in uids.distinct().chunked(10)) {
            val qs = db.collection("users")
                .whereIn(FieldPath.documentId(), chunk)
                .get().await()
            qs.documents.forEach { d ->
                out += SimpleUser(
                    uid = d.id,
                    displayName = d.getString("displayName") ?: "Unknown",
                    phone = d.getString("phoneNumber")
                )
            }
        }
        return out
    }

    suspend fun globalSearchUsers(query: String, limit: Long = 20): List<SimpleUser> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val byName = db.collection("users")
            .whereGreaterThanOrEqualTo("displayName", q)
            .whereLessThanOrEqualTo("displayName", q + '\uf8ff')
            .limit(limit)
            .get().await()

        val me = FirebaseAuth.getInstance().currentUser?.uid
        return byName.documents
            .map {
                SimpleUser(
                    uid = it.id,
                    displayName = it.getString("displayName") ?: "Unknown",
                    phone = it.getString("phoneNumber")
                )
            }
            .filter { it.uid != me }
    }

    /**
     * Creates a group chat and returns the chatId.
     * Steps:
     *  1) Create /chats/{chatId} with members + adminIds
     *  2) If localIconUri != null, upload to /chat_uploads/{chatId}/icons/... and set groupPhotoUrl
     *  3) Return chatId (throws on failure)
     */
    suspend fun createGroup(
        me: String,
        name: String,
        description: String?,
        memberIds: List<String>,
        localIconUri: Uri?
    ): String {
        require(me.isNotBlank()) { "Current user is empty" }
        require(name.isNotBlank()) { "Group name is required" }

        val members = (memberIds + me).distinct()

        // 1) Create chat doc first (so Storage rule sees uploader is a member)
        val ref = db.collection("chats").document()
        val base = mutableMapOf<String, Any?>(
            "type" to "group",
            "groupName" to name,
            "members" to members,
            "adminIds" to listOf(me),
            "lastMessageText" to "",
            "lastMessageTimestamp" to null,
            "lastMessageSenderId" to null,
            "lastMessageId" to null,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!description.isNullOrBlank()) {
            base["groupDescription"] = description
        }
        ref.set(base).await()

        // 2) Optional icon upload (members-only path, allowed by your Storage rules)
        if (localIconUri != null) {
            val fileName = "group_${System.currentTimeMillis()}.jpg"
            val path = "chat_uploads/${ref.id}/icons/$fileName"
            val iconRef = storage.reference.child(path)

            // be lenient; default to jpeg
            val contentType = when {
                localIconUri.toString().endsWith(".png", true) -> "image/png"
                localIconUri.toString().endsWith(".webp", true) -> "image/webp"
                else -> "image/jpeg"
            }
            val md = storageMetadata { this.contentType = contentType }

            iconRef.putFile(localIconUri, md).await()
            val photoUrl = iconRef.downloadUrl.await().toString()

            // Write groupPhotoUrl + updatedAt
            ref.set(
                mapOf(
                    "groupPhotoUrl" to photoUrl,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        }

        return ref.id
    }
}
