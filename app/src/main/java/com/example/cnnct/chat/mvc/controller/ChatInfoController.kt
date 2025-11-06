package com.example.cnnct.chat.controller

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storageMetadata
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Data access + actions for chat info screens & top bar.
 *
 * Notes:
 * - Group doc fields used here: groupName, groupDescription, groupPhotoUrl, members[],
 *   adminIds[], mutedMemberIds[], updatedAt, createdAt
 * - For member meta (read/opened markers), write from your ChatController directly to:
 *   memberMeta.<uid>.lastOpenedAt / memberMeta.<uid>.lastReadMessageId
 */
class ChatInfoController(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    private val storage = FirebaseStorage.getInstance()

    fun me(): String =
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
            adminIds = (data["adminIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            mutedMemberIds = (data["mutedMemberIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        )
    }

    /** Throw if caller is not an admin for that group (client guard; server rules should also enforce). */
    private suspend fun requireAdmin(chatId: String) {
        val g = getGroup(chatId)
        if (!g.adminIds.contains(me())) throw IllegalStateException("Admin required")
    }

    suspend fun leaveGroup(chatId: String) {
        // Leaving also removes any admin/muted state for me
        db.collection("chats").document(chatId)
            .update(
                "members", FieldValue.arrayRemove(me()),
                "adminIds", FieldValue.arrayRemove(me()),
                "mutedMemberIds", FieldValue.arrayRemove(me()),
                "updatedAt", FieldValue.serverTimestamp()
            ).await()
    }

    /** Admin-only: add members. */
    suspend fun addMembers(chatId: String, newMemberIds: List<String>) {
        if (newMemberIds.isEmpty()) return
        requireAdmin(chatId)
        db.collection("chats").document(chatId)
            .update(
                "members", FieldValue.arrayUnion(*newMemberIds.toTypedArray()),
                "updatedAt", FieldValue.serverTimestamp()
            ).await()
    }

    /**
     * Admin-only: remove members.
     * Also clears any roles (admin/muted) those users might have had.
     */
    suspend fun removeMembers(chatId: String, memberIds: List<String>) {
        if (memberIds.isEmpty()) return
        requireAdmin(chatId)
        db.collection("chats").document(chatId)
            .update(
                "members", FieldValue.arrayRemove(*memberIds.toTypedArray()),
                "adminIds", FieldValue.arrayRemove(*memberIds.toTypedArray()),
                "mutedMemberIds", FieldValue.arrayRemove(*memberIds.toTypedArray()),
                "updatedAt", FieldValue.serverTimestamp()
            ).await()
    }

    /** Admin-only: promote to admin. */
    suspend fun makeAdmin(chatId: String, uid: String) {
        requireAdmin(chatId)
        db.collection("chats").document(chatId)
            .update(
                "adminIds", FieldValue.arrayUnion(uid),
                "updatedAt", FieldValue.serverTimestamp()
            ).await()
    }

    /** Admin-only: revoke admin. */
    suspend fun revokeAdmin(chatId: String, uid: String) {
        requireAdmin(chatId)
        db.collection("chats").document(chatId)
            .update(
                "adminIds", FieldValue.arrayRemove(uid),
                "updatedAt", FieldValue.serverTimestamp()
            ).await()
    }

    /** Admin-only: mute (cannot send until unmuted). */
    suspend fun muteMember(chatId: String, uid: String) {
        requireAdmin(chatId)
        db.collection("chats").document(chatId)
            .update(
                "mutedMemberIds", FieldValue.arrayUnion(uid),
                "updatedAt", FieldValue.serverTimestamp()
            ).await()
    }

    /** Admin-only: unmute. */
    suspend fun unmuteMember(chatId: String, uid: String) {
        requireAdmin(chatId)
        db.collection("chats").document(chatId)
            .update(
                "mutedMemberIds", FieldValue.arrayRemove(uid),
                "updatedAt", FieldValue.serverTimestamp()
            ).await()
    }

    /** Admin-only: update group name and (optional) description. */
    suspend fun updateGroupNameAndDescription(
        chatId: String,
        name: String,
        description: String?
    ) {
        requireAdmin(chatId)
        require(name.isNotBlank()) { "Group name is required" }

        val payload = mutableMapOf<String, Any?>(
            "groupName" to name.trim(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (description != null) {
            payload["groupDescription"] = description.trim().ifBlank { null }
        }

        db.collection("chats").document(chatId)
            .set(payload, SetOptions.merge())
            .await()
    }

    /**
     * Admin-only: update group photo.
     * Uploads to Storage at /chat_uploads/{chatId}/icons/ and writes groupPhotoUrl on success.
     *
     * @return true if upload + write succeeded, false otherwise.
     */
    suspend fun updateGroupPhoto(chatId: String, imageUri: Uri): Boolean {
        return runCatching {
            requireAdmin(chatId)

            val fileName = "group_${System.currentTimeMillis()}.jpg"
            val path = "chat_uploads/$chatId/icons/$fileName"
            val ref = storage.reference.child(path)

            // Best-effort content type
            val contentType = when {
                imageUri.toString().endsWith(".png", ignoreCase = true) -> "image/png"
                imageUri.toString().endsWith(".webp", ignoreCase = true) -> "image/webp"
                else -> "image/jpeg"
            }
            val md = storageMetadata { this.contentType = contentType }

            // Upload
            ref.putFile(imageUri, md).await()
            val url = ref.downloadUrl.await().toString()

            // Save URL to the chat doc
            db.collection("chats").document(chatId)
                .set(
                    mapOf(
                        "groupPhotoUrl" to url,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()
        }.isSuccess
    }

    // --------- PER-USER CHAT META ----------
    suspend fun clearChatForMe(chatId: String) {
        db.collection("userChats").document(me())
            .collection("chats").document(chatId)
            .set(mapOf("clearedBefore" to Timestamp.now()), SetOptions.merge())
            .await()
    }

    // --------- PRIVATE CHAT helper (no queries; deterministic doc id) ----------
    private fun pairKeyOf(a: String, b: String): String {
        return if (a <= b) "${a}#${b}" else "${b}#${a}"
    }

    /**
     * Avoids a query by using a deterministic id:
     * chatId = "priv_${pairKey}" where pairKey = sorted(me, peer) joined by '#'.
     *
     * 1) Try GET on that doc id.
     * 2) If missing, create it with the two members.
     */
    suspend fun getOrCreatePrivateChatWith(peerId: String): String {
        val my = me()
        require(peerId.isNotBlank()) { "peerId is blank" }
        require(peerId != my) { "peerId must be different from me" }

        val key = pairKeyOf(my, peerId)
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

    // --------- Recent contacts & search for add-members ----------
    suspend fun getRecentPrivateChatPeers(limit: Int = 30): List<UserProfile> {
        val my = me()
        val priv = db.collection("chats")
            .whereArrayContains("members", my)
            .whereEqualTo("type", "private")
            .limit(50)
            .get().await()

        val peers = priv.documents
            .mapNotNull { d ->
                val members = (d.get("members") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                members.firstOrNull { it != my }
            }
            .distinct()
            .take(limit)

        return getUsers(peers)
    }

    /**
     * Global user search.
     * Name prefix search (displayName), plus optional phoneNumber prefix if q contains digits.
     */
    suspend fun searchUsers(query: String, limit: Int = 20): List<UserProfile> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val results = mutableMapOf<String, UserProfile>()

        val nameSnap = db.collection("users")
            .whereGreaterThanOrEqualTo("displayName", q)
            .whereLessThanOrEqualTo("displayName", q + '\uf8ff')
            .limit(limit.toLong())
            .get().await()
        nameSnap.documents.forEach { d ->
            results[d.id] = UserProfile(
                uid = d.id,
                displayName = d.getString("displayName") ?: "",
                about = d.getString("about"),
                phoneNumber = d.getString("phoneNumber"),
                photoUrl = d.getString("photoUrl")
            )
        }

        val digits = q.filter { it.isDigit() }
        if (digits.length >= 2) {
            val phoneSnap = db.collection("users")
                .whereGreaterThanOrEqualTo("phoneNumber", digits)
                .whereLessThanOrEqualTo("phoneNumber", digits + '\uf8ff')
                .limit(limit.toLong())
                .get().await()
            phoneSnap.documents.forEach { d ->
                results[d.id] = UserProfile(
                    uid = d.id,
                    displayName = d.getString("displayName") ?: "",
                    about = d.getString("about"),
                    phoneNumber = d.getString("phoneNumber"),
                    photoUrl = d.getString("photoUrl")
                )
            }
        }

        results.remove(me())
        return results.values.take(limit).toList()
    }
}

/**
 * Navigation helpers for chat feature.
 *
 * Hardened against duplicate opens:
 *  - GLOBAL throttle (blocks any second navigation for a short window)
 *  - Per-destination in-flight lock + key debounce
 *  - SINGLE_TOP + CLEAR_TOP + REORDER_TO_FRONT flags
 */
object ChatNav {

    // ---- global throttle (blocks ANY nav while true) ----
    private val globalLock = AtomicBoolean(false)
    @Volatile private var globalAt: Long = 0L

    // ---- key-based debounce state ----
    @Volatile private var lastNavAt: Long = 0L
    @Volatile private var lastNavKey: String = ""

    private val main = Handler(Looper.getMainLooper())
    private val inFlight = ConcurrentHashMap<String, AtomicBoolean>() // key -> lock

    private fun tryAcquire(
        key: String,
        globalWindowMs: Long = 700,
        debounceMs: Long = 700,
        holdMs: Long = 800
    ): Boolean {
        val now = SystemClock.uptimeMillis()

        // 1) GLOBAL throttle first (covers two different keys firing together)
        if (globalLock.get()) {
            return false
        }
        globalLock.set(true)
        globalAt = now
        main.postDelayed({ globalLock.set(false) }, globalWindowMs)

        // 2) Key-based debounce (avoid same key spamming)
        val passDebounce = key != lastNavKey || (now - lastNavAt) > debounceMs
        if (!passDebounce) return false
        lastNavKey = key
        lastNavAt = now

        // 3) In-flight lock per destination (prevents two callers in same frame)
        val lock = inFlight.getOrPut(key) { AtomicBoolean(false) }
        if (!lock.compareAndSet(false, true)) return false
        main.postDelayed({ lock.set(false) }, holdMs)

        return true
    }

    private fun Intent.asSingleTop(): Intent = this.apply {
        addFlags(
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
    }

    @JvmStatic
    fun openPeerProfile(context: Context, uid: String) {
        val key = "peer:$uid"
        if (!tryAcquire(key)) return

        val intent = Intent(context, PersonInfoActivity::class.java)
            .putExtra("uid", uid)
            .asSingleTop()

        context.startActivity(intent)
    }

    @JvmStatic
    fun openGroupInfo(context: Context, chatId: String) {
        val key = "group:$chatId"
        if (!tryAcquire(key)) return

        val intent = Intent(context, GroupInfoActivity::class.java)
            .putExtra("chatId", chatId)
            .asSingleTop()

        context.startActivity(intent)
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
