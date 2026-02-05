package com.abdallah.cnnct.groups.repository

import android.net.Uri
import com.abdallah.cnnct.chat.model.GroupInfo
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storageMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.tasks.await

class GroupRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) {
    fun me(): String = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")

    suspend fun getGroup(chatId: String): GroupInfo {
        val d = db.collection("chats").document(chatId).get().await()
        val data = d.data ?: throw IllegalStateException("Group not found")
        return GroupInfo(
            chatId = chatId,
            groupName = data["groupName"] as? String ?: "",
            groupDescription = data["groupDescription"] as? String,
            groupPhotoUrl = data["groupPhotoUrl"] as? String,
            members = (data["members"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            adminIds = (data["adminIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            mutedMemberIds = (data["mutedMemberIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        )
    }

    suspend fun createGroup(
        name: String,
        description: String?,
        memberIds: List<String>,
        localIconUri: Uri?
    ): String {
        val myId = me()
        require(name.isNotBlank()) { "Group name is required" }

        val members = (memberIds + myId).distinct()
        val ref = db.collection("chats").document()
        
        val base = mutableMapOf<String, Any?>(
            "type" to "group",
            "groupName" to name,
            "members" to members,
            "adminIds" to listOf(myId),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (!description.isNullOrBlank()) base["groupDescription"] = description
        
        ref.set(base).await()

        if (localIconUri != null) {
            uploadGroupIcon(ref.id, localIconUri)
        }
        return ref.id
    }

    private suspend fun uploadGroupIcon(chatId: String, uri: Uri) {
        val fileName = "group_${System.currentTimeMillis()}.jpg"
        val ref = storage.reference.child("chat_uploads/$chatId/icons/$fileName")
        val md = storageMetadata { contentType = "image/jpeg" } // Simplified content type intuition
        
        ref.putFile(uri, md).await()
        val url = ref.downloadUrl.await().toString()
        
        db.collection("chats").document(chatId).update(
            "groupPhotoUrl", url,
            "updatedAt", FieldValue.serverTimestamp()
        ).await()
    }

    suspend fun updateGroupPhoto(chatId: String, uri: Uri): Boolean {
        return try {
            uploadGroupIcon(chatId, uri)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun updateGroupNameAndDescription(chatId: String, name: String, description: String?) {
        val payload = mutableMapOf<String, Any?>(
            "groupName" to name.trim(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (description != null) {
            payload["groupDescription"] = description.trim().ifBlank { null }
        }
        db.collection("chats").document(chatId).set(payload, SetOptions.merge()).await()
    }

    suspend fun addMembers(chatId: String, uids: List<String>) {
        if (uids.isEmpty()) return
        db.collection("chats").document(chatId).update(
            "members", FieldValue.arrayUnion(*uids.toTypedArray()),
            "updatedAt", FieldValue.serverTimestamp()
        ).await()
    }

    suspend fun removeMembers(chatId: String, uids: List<String>) {
        if (uids.isEmpty()) return
        db.collection("chats").document(chatId).update(
            "members", FieldValue.arrayRemove(*uids.toTypedArray()),
            "adminIds", FieldValue.arrayRemove(*uids.toTypedArray()),
            "mutedMemberIds", FieldValue.arrayRemove(*uids.toTypedArray()),
            "updatedAt", FieldValue.serverTimestamp()
        ).await()
    }

    suspend fun leaveGroup(chatId: String) {
        removeMembers(chatId, listOf(me()))
    }

    suspend fun makeAdmin(chatId: String, uid: String) {
        db.collection("chats").document(chatId).update(
            "adminIds", FieldValue.arrayUnion(uid),
            "updatedAt", FieldValue.serverTimestamp()
        ).await()
    }

    suspend fun revokeAdmin(chatId: String, uid: String) {
        db.collection("chats").document(chatId).update(
            "adminIds", FieldValue.arrayRemove(uid),
            "updatedAt", FieldValue.serverTimestamp()
        ).await()
    }
    fun observeUserGroups(): kotlinx.coroutines.flow.Flow<List<com.abdallah.cnnct.homepage.model.ChatSummary>> = kotlinx.coroutines.flow.callbackFlow {
        val uid = me()
        val reg = db.collection("chats")
            .whereEqualTo("type", "group")
            .whereArrayContains("members", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                val groups = snap?.documents?.mapNotNull { doc ->
                    doc.toObject(com.abdallah.cnnct.homepage.model.ChatSummary::class.java)?.apply { id = doc.id }
                }?.sortedByDescending { it.lastMessageTimestamp?.toDate()?.time ?: 0L } ?: emptyList()
                
                trySend(groups)
            }
        awaitClose { reg.remove() }
    }
}
