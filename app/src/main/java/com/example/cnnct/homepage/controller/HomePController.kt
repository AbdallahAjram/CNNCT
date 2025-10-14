package com.example.cnnct.homepage.controller

import android.util.Log
import com.example.cnnct.homepage.model.ChatSummary
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.GregorianCalendar

object HomePController {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // ---------------- Real-time: listen to /chats + user-clears ----------------
    /**
     * Emits your chats, but with per-chat "clear for me" applied:
     * if userChats/{me}/chats/{chatId}.clearedBefore >= lastMessageTimestamp,
     * we blank the preview (text/timestamp/sender/status).
     */
    fun listenMyChats(
        onResult: (List<ChatSummary>) -> Unit
    ): ListenerRegistration? {
        val uid = auth.currentUser?.uid ?: return null

        // We combine two listeners:
        //  - chats where I'm a member
        //  - my per-chat meta (clearedBefore)
        var chats: List<ChatSummary> = emptyList()
        var clearedMap: Map<String, Timestamp> = emptyMap()

        fun emit() {
            val masked = chats.map { applyClearMask(it, clearedMap[it.id]) }
            onResult(sortChats(masked))
        }

        val chatsReg = db.collection("chats")
            .whereArrayContains("members", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("HomePController", "listenMyChats(chats) error", err)
                    onResult(emptyList()); return@addSnapshotListener
                }
                chats = snap?.documents?.mapNotNull { toChatSummary(it, uid) } ?: emptyList()
                emit()
            }

        val clearsReg = db.collection("userChats").document(uid)
            .collection("chats")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w("HomePController", "listenMyChats(clears) error", err)
                    return@addSnapshotListener
                }
                val map = mutableMapOf<String, Timestamp>()
                snap?.documents?.forEach { d ->
                    (d.getTimestamp("clearedBefore"))?.let { map[d.id] = it }
                }
                clearedMap = map
                emit()
            }

        // Return a handle that removes both
        return object : ListenerRegistration {
            override fun remove() {
                try { chatsReg.remove() } catch (_: Exception) {}
                try { clearsReg.remove() } catch (_: Exception) {}
            }
        }
    }

    fun getMyChats(onResult: (List<ChatSummary>) -> Unit) {
        val uid = auth.currentUser?.uid ?: return onResult(emptyList())

        // Fetch /chats and /userChats in parallel, then combine
        val chatsTask = db.collection("chats")
            .whereArrayContains("members", uid)
            .get()

        val clearsTask = db.collection("userChats").document(uid)
            .collection("chats").get()

        Tasks.whenAllSuccess<QuerySnapshot>(listOf(chatsTask, clearsTask))
            .addOnSuccessListener { results ->
                val chatsSnap = results[0]
                val clearsSnap = results[1]

                val chats = chatsSnap.documents.mapNotNull { toChatSummary(it, uid) }

                val clearedMap = clearsSnap.documents
                    .mapNotNull { d -> d.getTimestamp("clearedBefore")?.let { d.id to it } }
                    .toMap()

                val masked = chats.map { applyClearMask(it, clearedMap[it.id]) }
                onResult(sortChats(masked))
            }
            .addOnFailureListener { e ->
                Log.e("HomePController", "getMyChats failed", e)
                onResult(emptyList())
            }
    }

    fun getUserChats(onChatsFetched: (List<ChatSummary>) -> Unit) = getMyChats(onChatsFetched)

    private fun sortChats(list: List<ChatSummary>): List<ChatSummary> {
        return list.sortedByDescending {
            it.lastMessageTimestamp?.toDate()?.time
                ?: it.updatedAt?.toDate()?.time
                ?: it.createdAt?.toDate()?.time
                ?: Long.MIN_VALUE
        }
    }

    /**
     * If the user's clearedBefore is newer than (or equal to) the last message time,
     * we blank the preview fields so the Home UI shows "No messages yet".
     */
    private fun applyClearMask(s: ChatSummary, clearedBefore: Timestamp?): ChatSummary {
        if (clearedBefore == null) return s
        val lastMs = s.lastMessageTimestamp?.toDate()?.time ?: Long.MIN_VALUE
        val clearedMs = clearedBefore.toDate().time
        return if (lastMs <= clearedMs) {
            s.copy(
                lastMessageText = "",
                lastMessageTimestamp = null,
                lastMessageSenderId = null,
                lastMessageIsRead = false,
                lastMessageStatus = null
            )
        } else {
            s
        }
    }

    // NOTE: aware of memberMeta → iBlockedPeer / blockedByOther (for *me*)
    private fun toChatSummary(meta: DocumentSnapshot, me: String): ChatSummary? {
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

            val memberMeta = meta.get("memberMeta") as? Map<*, *>
            val myMeta = (memberMeta?.get(me) as? Map<*, *>)
            val iBlockedPeer = (myMeta?.get("iBlockedPeer") as? Boolean)
            val blockedByOther = (myMeta?.get("blockedByOther") as? Boolean)

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
                lastMessageStatus = status,
                iBlockedPeer = iBlockedPeer,
                blockedByOther = blockedByOther,
                groupPhotoUrl = meta.getString("groupPhotoUrl")
            )
        } catch (e: Exception) {
            Log.e("HomePController", "toChatSummary parse ${meta.id}", e); null
        }
    }

    // ---------------- Search: displayName OR phone ----------------
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
                    "updatedAt" to com.google.firebase.Timestamp.now(),
                    "lastMessageIsRead" to true
                ))
            }
        }
    }

    fun createOrOpenPrivate(
        me: String,
        other: String,
        onResult: (String?) -> Unit
    ) {
        val db = FirebaseFirestore.getInstance()
        val pairKey = makePairKey(me, other)
        val chatId = "priv_$pairKey"
        val ref = db.collection("chats").document(chatId)

        ref.get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) {
                    onResult(chatId)
                } else {
                    val data = mapOf(
                        "type" to "private",
                        "members" to listOf(me, other),
                        "pairKey" to pairKey,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    ref.set(data)
                        .addOnSuccessListener { onResult(chatId) }
                        .addOnFailureListener { e ->
                            Log.e("HomePController", "create chat failed", e)
                            onResult(null)
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e("HomePController", "get chat failed", e)
                onResult(null)
            }
    }

    // ---------------- Per-chat mute helpers ----------------
    suspend fun setChatMutedUntil(me: String, chatId: String, mutedUntilMs: Long?) {
        val ref = db.collection("userChats").document(me)
            .collection("chats").document(chatId)

        val data = if (mutedUntilMs == null) {
            mapOf("mutedUntil" to FieldValue.delete())
        } else {
            mapOf("mutedUntil" to com.google.firebase.Timestamp(mutedUntilMs / 1000, ((mutedUntilMs % 1000).toInt()) * 1_000_000))
        }

        ref.set(data, SetOptions.merge()).await()
    }

    suspend fun muteChatForHours(me: String, chatId: String, hours: Long) {
        val untilMs = System.currentTimeMillis() + hours * 60L * 60L * 1000L
        setChatMutedUntil(me, chatId, untilMs)
    }

    suspend fun muteChatForever(me: String, chatId: String) {
        val farFuture = GregorianCalendar(2100, 0, 1, 0, 0, 0).timeInMillis
        setChatMutedUntil(me, chatId, farFuture)
    }

    suspend fun unmuteChat(me: String, chatId: String) {
        setChatMutedUntil(me, chatId, null)
    }
}
