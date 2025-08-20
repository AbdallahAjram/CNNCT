package com.example.cnnct.homepage.controller

import android.util.Log
import com.example.cnnct.homepage.model.ChatSummary
import com.google.android.datatransport.runtime.logging.Logging.d
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.tasks.await

object HomePController {
    private val db = FirebaseFirestore.getInstance()
    private val currentUser = FirebaseAuth.getInstance().currentUser
    private val auth = FirebaseAuth.getInstance()

    fun getUserChats(onChatsFetched: (List<ChatSummary>) -> Unit) {
        val uid = currentUser?.uid ?: return

        db.collection("userChats")
            .document(uid)
            .collection("chats")
            .orderBy("lastMessageTimestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("ChatController", "Error fetching chats", err)
                    onChatsFetched(emptyList())
                    return@addSnapshotListener
                }

                if (snap == null || snap.isEmpty) {
                    Log.d("ChatController", "No chats found.")
                    onChatsFetched(emptyList())
                    return@addSnapshotListener
                }

                val previewDocs = snap.documents
                val chatIds = previewDocs.map { it.id }

                // join with /chats metadata in chunks of 10 (whereIn limit)
                val tasks = chatIds.chunked(10).map { ids ->
                    db.collection("chats")
                        .whereIn(FieldPath.documentId(), ids)
                        .get()
                }

                Tasks.whenAllSuccess<QuerySnapshot>(tasks)
                    .addOnSuccessListener { results ->
                        val metaById = mutableMapOf<String, DocumentSnapshot>()
                        results.forEach { qs -> qs.documents.forEach { d -> metaById[d.id] = d } }

                        val chatSummaries = previewDocs.mapNotNull { prev ->
                            val chatId = prev.id
                            val meta = metaById[chatId] ?: return@mapNotNull null

                            try {
                                val groupName = meta.getString("groupName")
                                val text = prev.getString("lastMessageText") ?: ""
                                val timestamp = prev.getTimestamp("lastMessageTimestamp")
                                val senderId = prev.getString("lastMessageSenderId")
                                val members = meta.get("members") as? List<String> ?: emptyList()
                                val type = meta.getString("type") ?: "private"

                                val statusRaw = prev.getString("lastMessageStatus")
                                    ?: meta.getString("lastMessageStatus")
                                val isReadLegacy = meta.getBoolean("lastMessageIsRead") ?: false
                                val status = statusRaw ?: if (isReadLegacy) "read" else null

                                ChatSummary(
                                    id = chatId,
                                    groupName = groupName,
                                    lastMessageText = text,
                                    lastMessageTimestamp = timestamp,
                                    lastMessageSenderId = senderId,
                                    members = members,
                                    lastMessageIsRead = (status == "read"),
                                    type = type,
                                    createdAt = meta.getTimestamp("createdAt"),
                                    updatedAt = meta.getTimestamp("updatedAt"),
                                    lastMessageStatus = status
                                )
                            } catch (e: Exception) {
                                Log.e("ChatController", "Error parsing chat document $chatId", e)
                                null
                            }
                        }

                        Log.d(
                            "ChatController",
                            "Fetched ${chatSummaries.size} chats; lastTexts=${chatSummaries.map { it.lastMessageText }}"
                        )
                        onChatsFetched(chatSummaries)
                    }
                    .addOnFailureListener { e ->
                        Log.e("ChatController", "Failed joining chat metadata", e)
                        onChatsFetched(emptyList())
                    }
            }
    }

    fun listenUserChats(onChatsFetched: (List<ChatSummary>) -> Unit): ListenerRegistration? {
        val uid = auth.currentUser?.uid ?: return null

        return db.collection("userChats")
            .document(uid)
            .collection("chats")
            .orderBy("lastMessageTimestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("ChatController", "listenUserChats error", err)
                    onChatsFetched(emptyList())
                    return@addSnapshotListener
                }
                if (snap == null || snap.isEmpty) {
                    Log.d("ChatController", "listenUserChats: empty")
                    onChatsFetched(emptyList())
                    return@addSnapshotListener
                }

                val previewDocs = snap.documents
                val chatIds = previewDocs.map { it.id }

                val tasks = chatIds.chunked(10).map { ids ->
                    db.collection("chats")
                        .whereIn(FieldPath.documentId(), ids)
                        .get()
                }

                Tasks.whenAllSuccess<QuerySnapshot>(tasks)
                    .addOnSuccessListener { results ->
                        val metaById = mutableMapOf<String, DocumentSnapshot>()
                        results.forEach { qs -> qs.documents.forEach { d -> metaById[d.id] = d } }

                        val list = previewDocs.mapNotNull { prev ->
                            val chatId = prev.id
                            val meta = metaById[chatId] ?: return@mapNotNull null

                            try {
                                val groupName = meta.getString("groupName")
                                val text = prev.getString("lastMessageText") ?: ""
                                val ts = prev.getTimestamp("lastMessageTimestamp")
                                val senderId = prev.getString("lastMessageSenderId")
                                val members = meta.get("members") as? List<String> ?: emptyList()
                                val type = meta.getString("type") ?: "private"

                                val statusRaw = prev.getString("lastMessageStatus")
                                    ?: meta.getString("lastMessageStatus")
                                val isReadLegacy = meta.getBoolean("lastMessageIsRead") ?: false
                                val status = statusRaw ?: if (isReadLegacy) "read" else null

                                ChatSummary(
                                    id = chatId,
                                    type = type,
                                    members = members,
                                    groupName = groupName,
                                    lastMessageText = text,
                                    lastMessageTimestamp = ts,
                                    lastMessageSenderId = senderId,
                                    createdAt = meta.getTimestamp("createdAt"),
                                    updatedAt = meta.getTimestamp("updatedAt"),
                                    lastMessageIsRead = (status == "read"),
                                    lastMessageStatus = status
                                )
                            } catch (e: Exception) {
                                Log.e("ChatController", "parse chat $chatId", e)
                                null
                            }
                        }

                        Log.d("ChatController", "listenUserChats: ${list.size} chats")
                        onChatsFetched(list)
                    }
                    .addOnFailureListener { e ->
                        Log.e("ChatController", "join chats meta failed", e)
                        onChatsFetched(emptyList())
                    }
            }
    }

    /**
     * Search users by displayName prefix (case-insensitive-ish).
     * NOTE: Needs a composite index on 'displayName' if you later add orderBy.
     */
    fun searchUsersByDisplayName(
        query: String,
        limit: Long = 20,
        onResult: (List<Pair<String, String>>) -> Unit
    ) {
        if (query.isBlank()) { onResult(emptyList()); return }
        val normalized = query.trim()

        // Simple prefix search using range
        db.collection("users")
            .whereGreaterThanOrEqualTo("displayName", normalized)
            .whereLessThanOrEqualTo("displayName", normalized + '\uf8ff')
            .limit(limit)
            .get()
            .addOnSuccessListener { snap ->
                val rows = snap.documents.mapNotNull { d ->
                    val uid = d.id
                    val name = d.getString("displayName")
                    if (!name.isNullOrBlank()) uid to name else null
                }
                onResult(rows)
            }
            .addOnFailureListener {
                Log.e("ChatController", "searchUsersByDisplayName failed", it)
                onResult(emptyList())
            }
    }

    /**
     * Create (or get existing) private chat between current user and otherUserId.
     * Uses a deterministic 'pairKey' = sorted(u1, u2).joinToString("#")
     */
    fun createOrGetPrivateChat(
        otherUserId: String,
        onResult: (String?) -> Unit
    ) {
        val me = auth.currentUser?.uid ?: return onResult(null)
        if (otherUserId == me) return onResult(null)

        val pairKey = makePairKey(me, otherUserId)

        // 1) Fast path: look up by deterministic pairKey (works for new/normalized chats)
        db.collection("chats")
            .whereEqualTo("type", "private")
            .whereEqualTo("pairKey", pairKey)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                if (!snap.isEmpty) {
                    onResult(snap.documents.first().id)
                    return@addOnSuccessListener
                }

                // 2) Fallback: look up my private chats, filter client-side for the peer (handles legacy chats without pairKey)
                db.collection("chats")
                    .whereArrayContains("members", me)
                    .whereEqualTo("type", "private") // if Firestore asks for an index, create it once.
                    .limit(50) // tune as needed
                    .get()
                    .addOnSuccessListener { s2 ->
                        val existing = s2.documents.firstOrNull { d ->
                            (d.get("members") as? List<*>)?.contains(otherUserId) == true
                        }

                        if (existing != null) {
                            // Backfill pairKey so next time the fast path resolves immediately
                            if (!existing.contains("pairKey")) {
                                existing.reference.update("pairKey", pairKey)
                                    .addOnFailureListener { e ->
                                        Log.w("ChatController", "pairKey backfill failed for ${existing.id}", e)
                                    }
                            }
                            onResult(existing.id)
                        } else {
                            // 3) Create new private chat
                            val data = hashMapOf(
                                "type" to "private",
                                "members" to listOf(me, otherUserId),
                                "pairKey" to pairKey,
                                "lastMessageText" to "",
                                "lastMessageTimestamp" to null,
                                "lastMessageSenderId" to null,
                                "lastMessageIsRead" to false
                            )
                            db.collection("chats")
                                .add(data)
                                .addOnSuccessListener { ref -> onResult(ref.id) }
                                .addOnFailureListener { e ->
                                    Log.e("ChatController", "create chat failed", e)
                                    onResult(null)
                                }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("ChatController", "fallback query failed", e)
                        onResult(null)
                    }
            }
            .addOnFailureListener { e ->
                Log.e("ChatController", "pairKey query failed", e)
                onResult(null)
            }
    }


    private fun makePairKey(a: String, b: String): String {
        val (x, y) = listOf(a, b).sorted()
        return "$x#$y"
    }

    /**
     * Recipient-side promotion: if the current user is a member and the chat's
     * last message is marked "sent" by someone else, mark it as "delivered".
     * Runs a single pass (no loop). Call this periodically while the app is open.
     */
    suspend fun promoteIncomingLastMessagesToDelivered(me: String, maxPerRun: Int = 25) {
        if (me.isBlank()) return
        val qSnap = db.collection("chats")
            .whereArrayContains("members", me)
            .whereEqualTo("lastMessageStatus", "sent")
            .limit(maxPerRun.toLong())
            .get()
            .await()

        if (qSnap.isEmpty) return

        val batch = db.batch()
        var updates = 0
        for (doc in qSnap.documents) {
            val sender = doc.getString("lastMessageSenderId")
            // Only promote if I am NOT the sender
            if (!sender.isNullOrBlank() && sender != me) {
                batch.update(doc.reference, mapOf(
                    "lastMessageStatus" to "delivered",
                    "updatedAt" to com.google.firebase.Timestamp.now()
                ))
                updates++
            }
        }
        if (updates > 0) {
            batch.commit().await()
            Log.d("HomePController", "promoted $updates chats to delivered")
        }
    }

    fun markLastMessageRead(chatId: String, me: String) {
        val db = FirebaseFirestore.getInstance()
        val ref = db.collection("chats").document(chatId)

        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val lastSender = snap.getString("lastMessageSenderId")
            // Only mark as read if I'm NOT the sender (i.e., I'm the recipient viewing it)
            if (!lastSender.isNullOrBlank() && lastSender != me) {
                tx.update(ref, mapOf(
                    "lastMessageStatus" to "read",
                    "lastMessageIsRead" to true,        // legacy compatibility
                    "updatedAt" to com.google.firebase.Timestamp.now()
                ))
            }
        }
    }
}
