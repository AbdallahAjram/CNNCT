package com.example.cnnct.chat.controller

import android.content.Context
import android.content.Intent
import androidx.navigation.NavController
import com.example.cnnct.chat.model.GroupInfo
import com.example.cnnct.chat.model.UserProfile
import com.example.cnnct.chat.view.GroupInfoActivity
import com.example.cnnct.chat.view.PersonInfoActivity
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Data access + actions for chat info screens & top bar.
 */
class ChatInfoController(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    private fun me(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")

    // --------- USERS ----------
    suspend fun getUser(uid: String): UserProfile {
        val d = db.collection("users").document(uid).get().await()
        val data = d.data.orEmpty()
        return UserProfile(
            uid = uid,
            displayName = data["displayName"] as? String ?: "",
            about = data["about"] as? String,
            phoneNumber = data["phoneNumber"] as? String,
            photoUrl = data["photoUrl"] as? String
        )
    }

    suspend fun getUsers(uids: List<String>): List<UserProfile> {
        if (uids.isEmpty()) return emptyList()
        val chunks = uids.distinct().chunked(10) // Firestore whereIn limit
        val out = mutableListOf<UserProfile>()
        for (chunk in chunks) {
            val snap = db.collection("users")
                .whereIn(FieldPath.documentId(), chunk)
                .get().await()
            out += snap.documents.map { d ->
                val data = d.data.orEmpty()
                UserProfile(
                    uid = d.id,
                    displayName = data["displayName"] as? String ?: "",
                    about = data["about"] as? String,
                    phoneNumber = data["phoneNumber"] as? String,
                    photoUrl = data["photoUrl"] as? String
                )
            }
        }
        return out
    }

    suspend fun blockPeer(peerId: String) {
        db.collection("users").document(me())
            .collection("blocks").document(peerId)
            .set(mapOf("blocked" to true, "createdAt" to Timestamp.now()))
            .await()
    }

    // --------- GROUPS ----------
    suspend fun getGroup(chatId: String): GroupInfo {
        val d = db.collection("chats").document(chatId).get().await()
        val data = d.data ?: error("Group not found")
        return GroupInfo(
            chatId = chatId,
            groupName = data["groupName"] as? String ?: "",
            groupDescription = data["groupDescription"] as? String,
            groupPhotoUrl = data["groupPhotoUrl"] as? String,
            members = (data["members"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            adminIds = (data["adminIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        )
    }

    suspend fun leaveGroup(chatId: String) {
        db.collection("chats").document(chatId)
            .update(
                "members", FieldValue.arrayRemove(me()),
                "updatedAt", FieldValue.serverTimestamp()
            ).await()
    }

    // --------- PER-USER CHAT META ----------
    suspend fun clearChatForMe(chatId: String) {
        db.collection("userChats").document(me())
            .collection("chats").document(chatId)
            .set(mapOf("clearedBefore" to Timestamp.now()), SetOptions.merge())
            .await()
    }

    // --------- PRIVATE CHAT helper ----------
    private fun pairKeyOf(a: String, b: String): String {
        return if (a <= b) "${a}#${b}" else "${b}#${a}"
    }

    suspend fun getOrCreatePrivateChatWith(peerId: String): String {
        val my = me()
        val key = pairKeyOf(my, peerId)

        val found = db.collection("chats")
            .whereEqualTo("type", "private")
            .whereEqualTo("pairKey", key)
            .limit(1)
            .get()
            .await()

        if (!found.isEmpty) return found.documents.first().id

        val newDoc = db.collection("chats").document()
        val payload = mapOf(
            "type" to "private",
            "members" to listOf(my, peerId),
            "pairKey" to key,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        newDoc.set(payload).await()
        return newDoc.id
    }
}

/**
 * Navigation helpers for chat feature.
 *
 * We launch Activities via Intents to avoid NavHost requirements.
 */
object ChatNav {

    @JvmStatic
    fun openPeerProfile(context: Context, uid: String) {
        context.startActivity(
            Intent(context, PersonInfoActivity::class.java).putExtra("uid", uid)
        )
    }

    @JvmStatic
    fun openGroupInfo(context: Context, chatId: String) {
        context.startActivity(
            Intent(context, GroupInfoActivity::class.java).putExtra("chatId", chatId)
        )
    }
}

/* ------------------ EXTENSIONS (outside ChatNav) ------------------ */
fun NavController.openPeerProfile(uid: String) {
    ChatNav.openPeerProfile(this.context, uid)
}

fun NavController.openGroupInfo(chatId: String) {
    ChatNav.openGroupInfo(this.context, chatId)
}

/**
 * Bridge so the top bar can call into your current Chat ViewModel without refactors.
 */
interface ChatViewModelBridge {
    fun toggleSearchMode(enabled: Boolean)
    suspend fun clearChatForMe(chatId: String)
    suspend fun blockPeer(peerId: String)
    suspend fun leaveGroup(chatId: String)
}
