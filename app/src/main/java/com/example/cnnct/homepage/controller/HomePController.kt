package com.example.cnnct.homepage.controller

import android.util.Log
import com.example.cnnct.homepage.model.ChatSummary
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.tasks.await

/**
 * Controller for Home / Chats screen.
 * - REALTIME from /chats where members contains me (no userChats).
 * - Search users by displayName OR phone (Lebanese local format, digits only e.g. "03123456").
 * - Create-or-get private chat via deterministic pairKey.
 * - Status promotions (sent -> delivered) and mark last message read.
 */
object HomePController {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // ---------------- Real-time: listen directly to /chats ----------------
    fun listenMyChats(
        onResult: (List<ChatSummary>) -> Unit
    ): ListenerRegistration? {
        val uid = auth.currentUser?.uid ?: return null
        return db.collection("chats")
            .whereArrayContains("members", uid)
            // No orderBy to avoid index requirements; sort client-side
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("HomePController", "listenMyChats error", err)
                    onResult(emptyList())
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { toChatSummary(it) } ?: emptyList()
                onResult(sortChats(list))
            }
    }

    // ---------------- One-shot preload (splash) ----------------
    fun getMyChats(onResult: (List<ChatSummary>) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onResult(emptyList())
        db.collection("chats")
            .whereArrayContains("members", uid)
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.mapNotNull { toChatSummary(it) }
                onResult(sortChats(list))
            }
            .addOnFailureListener { e ->
                Log.e("HomePController", "getMyChats failed", e)
                onResult(emptyList())
            }
    }

    // Backward-compatible alias for your splash
    fun getUserChats(onChatsFetched: (List<ChatSummary>) -> Unit) = getMyChats(onChatsFetched)

    private fun sortChats(list: List<ChatSummary>): List<ChatSummary> {
        return list.sortedByDescending {
            it.lastMessageTimestamp?.toDate()?.time
                ?: it.updatedAt?.toDate()?.time
                ?: it.createdAt?.toDate()?.time
                ?: Long.MIN_VALUE
        }
    }

    private fun toChatSummary(meta: DocumentSnapshot): ChatSummary? {
        return try {
            val chatId = meta.id
            val groupName = meta.getString("groupName")
            val text = meta.getString("lastMessageText") ?: ""
            val ts = meta.getTimestamp("lastMessageTimestamp")
            val senderId = meta.getString("lastMessageSenderId")
            val members = meta.get("members") as? List<String> ?: emptyList()
            val type = meta.getString("type") ?: "private"

            val statusRaw = meta.getString("lastMessageStatus")
            val isReadLegacy = meta.getBoolean("lastMessageIsRead") ?: false
            val status = statusRaw ?: if (isReadLegacy) "read" else null

            ChatSummary(
                id = chatId,
                groupName = groupName,
                lastMessageText = text,
                lastMessageTimestamp = ts,
                lastMessageSenderId = senderId,
                members = members,
                lastMessageIsRead = (status == "read"),
                type = type,
                createdAt = meta.getTimestamp("createdAt"),
                updatedAt = meta.getTimestamp("updatedAt"),
                lastMessageStatus = status
            )
        } catch (e: Exception) {
            Log.e("HomePController", "toChatSummary parse ${meta.id}", e); null
        }
    }

    // ---------------- Search: displayName OR (Lebanese) phone ----------------
    fun searchUsersByNameOrPhone(
        query: String,
        limit: Long = 20,
        onResult: (List<Triple<String, String, String?>>) -> Unit
    ) {
        val qRaw = query.trim()
        if (qRaw.isBlank()) { onResult(emptyList()); return }

        val qDigits = qRaw.filter { it.isDigit() }

        val tasks = mutableListOf<com.google.android.gms.tasks.Task<QuerySnapshot>>()

        val displayNameTask = db.collection("users")
            .whereGreaterThanOrEqualTo("displayName", qRaw)
            .whereLessThanOrEqualTo("displayName", qRaw + '\uf8ff')
            .limit(limit)
            .get()
        tasks += displayNameTask

        if (qDigits.length >= 2) {
            val phoneTask = db.collection("users")
                .whereGreaterThanOrEqualTo("phoneNumber", qDigits)
                .whereLessThanOrEqualTo("phoneNumber", qDigits + '\uf8ff')
                .limit(limit)
                .get()
            tasks += phoneTask
        }

        Tasks.whenAllSuccess<QuerySnapshot>(tasks)
            .addOnSuccessListener { resultSets ->
                val map = LinkedHashMap<String, Triple<String, String, String?>>()
                resultSets.forEach { qs ->
                    qs.documents.forEach { d ->
                        val uid = d.id
                        val name = d.getString("displayName") ?: return@forEach
                        val phone = d.getString("phoneNumber")
                        map[uid] = Triple(uid, name, phone)
                    }
                }
                val me = auth.currentUser?.uid
                val out = map.values
                    .filter { it.first != me }
                    .take(limit.toInt())
                onResult(out)
            }
            .addOnFailureListener { e ->
                Log.e("HomePController", "searchUsersByNameOrPhone failed", e)
                onResult(emptyList())
            }
    }

    // ---------------- Create or get existing private chat ----------------
    fun createOrGetPrivateChat(otherUserId: String, onResult: (String?) -> Unit) {
        val me = auth.currentUser?.uid ?: return onResult(null)
        if (otherUserId == me) return onResult(null)

        val pairKey = makePairKey(me, otherUserId)

        // (1) PairKey fast path
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

                // (2) Fallback: my private chats that contain other
                db.collection("chats")
                    .whereArrayContains("members", me)
                    .whereEqualTo("type", "private")
                    .limit(50)
                    .get()
                    .addOnSuccessListener { s2 ->
                        val existing = s2.documents.firstOrNull { d ->
                            (d.get("members") as? List<*>)?.contains(otherUserId) == true
                        }
                        if (existing != null) {
                            if (existing.get("pairKey") == null) {
                                existing.reference.update("pairKey", pairKey)
                                    .addOnFailureListener { e ->
                                        Log.w("HomePController", "pairKey backfill failed for ${existing.id}", e)
                                    }
                            }
                            onResult(existing.id)
                        } else {
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
                                    Log.e("HomePController", "create chat failed", e)
                                    onResult(null)
                                }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("HomePController", "fallback query failed", e)
                        onResult(null)
                    }
            }
            .addOnFailureListener { e ->
                Log.e("HomePController", "pairKey query failed", e)
                onResult(null)
            }
    }

    private fun makePairKey(a: String, b: String): String {
        val (x, y) = listOf(a, b).sorted()
        return "$x#$y"
    }

    // ---------------- Status promotion & read ----------------
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
        val ref = db.collection("chats").document(chatId)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val lastSender = snap.getString("lastMessageSenderId")
            if (!lastSender.isNullOrBlank() && lastSender != me) {
                tx.update(ref, mapOf(
                    "lastMessageStatus" to "read",
                    "lastMessageIsRead" to true,
                    "updatedAt" to com.google.firebase.Timestamp.now()
                ))
            }
        }
    }
}
