package com.cnnct.chat.mvc.model

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.ktx.storage
import com.google.firebase.storage.ktx.storageMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Locale
import com.example.cnnct.homepage.model.ChatSummary

class FirestoreChatRepository(
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage = Firebase.storage
) : ChatRepository {

    private fun chats() = db.collection("chats")
    private fun messages(chatId: String) = chats().document(chatId).collection("messages")
    private fun userChatMeta(userId: String, chatId: String) =
        db.collection("userChats").document(userId).collection("chats").document(chatId)
    private fun blocks(userId: String, peerId: String) =
        db.collection("users").document(userId).collection("blocks").document(peerId)

    // -------- Helpers --------

    private fun makePairKey(a: String, b: String): String {
        val (x, y) = listOf(a, b).sorted()
        return "$x#$y"
    }

    // Client-side check we *can* do: have I blocked them?
    private suspend fun iBlockedPeer(me: String, other: String): Boolean {
        return blocks(me, other).get().await().exists()
    }

    // -------- Public API --------

    override suspend fun ensurePrivateChat(userA: String, userB: String): String {
        val pairKey = makePairKey(userA, userB)
        // Align with your HomePController doc-id scheme if you use it elsewhere:
        val chatId = "priv_$pairKey"
        val chatRef = chats().document(chatId)

        val snap = chatRef.get().await()
        if (!snap.exists()) {
            val now = FieldValue.serverTimestamp()
            chatRef.set(
                mapOf(
                    "type" to "private",
                    "members" to listOf(userA, userB),
                    "pairKey" to pairKey,              // include pairKey to satisfy rules
                    "createdAt" to now,
                    "updatedAt" to now,
                    "lastMessageText" to "",
                    "lastMessageTimestamp" to null,
                    "lastMessageSenderId" to null,
                    "lastMessageId" to null
                ),
                SetOptions.merge()
            ).await()
        }
        return chatId
    }

    override suspend fun sendMessage(
        chatId: String,
        senderId: String,
        draft: MessageDraft
    ) {
        // 🔐 Only check "I blocked peer" for PRIVATE chats
        val chatSnap = chats().document(chatId).get().await()
        val type = chatSnap.getString("type") ?: "private"
        if (type == "private") {
            val members = (chatSnap.get("members") as? List<String>).orEmpty()
            val peerId = members.firstOrNull { it != senderId }
            if (peerId != null && iBlockedPeer(senderId, peerId)) {
                throw IllegalStateException("You blocked this user")
            }
        }

        val chatRef = chats().document(chatId)
        val msgRef  = messages(chatId).document()

        db.runBatch { b ->
            b.set(msgRef, mapOf(
                "senderId"          to senderId,
                "type"              to draft.type.name,
                "text"              to draft.text,
                "mediaUrl"          to draft.mediaUrl,
                "location"          to draft.location,
                "contentType"       to draft.contentType,
                "fileName"          to draft.fileName,
                "sizeBytes"         to draft.sizeBytes,
                "createdAt"         to FieldValue.serverTimestamp(),
                "editedAt"          to null,
                "deleted"           to false,
                "hiddenFor"         to emptyList<String>(),
                "deletedBy"         to null,
                "deletedAt"         to null,
                "createdAtClient"   to Timestamp.now()
            ))

            // ✅ FIXED: Proper summary text for location messages
            val summaryText = when (draft.type) {
                MessageType.text -> draft.text.orEmpty()
                MessageType.location -> "📍 Location"
                MessageType.image -> "📷 Photo"
                MessageType.video -> "🎬 Video"
                else -> "📎 ${draft.fileName ?: "Attachment"}"
            }

            b.set(chatRef, mapOf(
                "lastMessageText"       to summaryText,
                "lastMessageTimestamp"  to FieldValue.serverTimestamp(),
                "lastMessageSenderId"   to senderId,
                "lastMessageStatus"     to "sent",
                "lastMessageIsRead"     to false,
                "lastMessageId"         to msgRef.id,
                "updatedAt"             to FieldValue.serverTimestamp()
            ), SetOptions.merge())
        }.await()
    }

    override fun streamMessages(chatId: String, pageSize: Int): Flow<List<Message>> = callbackFlow {
        val reg = messages(chatId)
            .orderBy("createdAt")
            .limitToLast(pageSize.toLong())
            .addSnapshotListener { snap, err ->
                if (err != null) { trySend(emptyList()); return@addSnapshotListener }
                val items = snap?.documents.orEmpty().map { d ->
                    Message(
                        id = d.id,
                        senderId = d.getString("senderId") ?: "",
                        type = runCatching { MessageType.valueOf(d.getString("type") ?: "text") }.getOrDefault(MessageType.text),
                        text = d.getString("text"),
                        mediaUrl = d.getString("mediaUrl"),
                        location = d.getGeoPoint("location"),
                        contentType = d.getString("contentType"),
                        fileName = d.getString("fileName"),
                        sizeBytes = d.getLong("sizeBytes"),
                        createdAt = d.getTimestamp("createdAt"),
                        createdAtClient = d.getTimestamp("createdAtClient"),
                        editedAt = d.getTimestamp("editedAt"),
                        deleted = d.getBoolean("deleted") ?: false,
                        hiddenFor = (d.get("hiddenFor") as? List<*>)?.filterIsInstance<String>(),
                        deletedBy = d.getString("deletedBy"),
                        deletedAt = d.getTimestamp("deletedAt")
                    )
                }
                trySend(items)
            }
        awaitClose { reg.remove() }
    }

    override suspend fun markOpened(chatId: String, userId: String) {
        userChatMeta(userId, chatId).set(
            mapOf("lastOpenedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge()
        ).await()

        chats().document(chatId).set(
            mapOf("memberMeta" to mapOf(
                userId to mapOf("lastOpenedAt" to FieldValue.serverTimestamp())
            )),
            SetOptions.merge()
        ).await()
    }

    override suspend fun markRead(chatId: String, userId: String, messageId: String?) {
        val chatRef = chats().document(chatId)
        val userChatRef = userChatMeta(userId, chatId)

        // 1. Transaction to safely update Global Chat Status if we are reading the latest message
        try {
            db.runTransaction { txn ->
                val chatSnap = txn.get(chatRef)
                if (!chatSnap.exists()) return@runTransaction

                val currentLastId = chatSnap.getString("lastMessageId")
                val senderId = chatSnap.getString("lastMessageSenderId")
                
                // If we are marking the LATEST message as read (or marking 'all' via null), update the global status
                // But only if:
                // 1. It's NOT already read? (Optimization)
                // 2. The READER (userId) is NOT the SENDER (senderId).
                //    (If I read my own message, it doesn't mean the peer read it!)
                if (userId != senderId && (messageId == null || messageId == currentLastId) && !currentLastId.isNullOrBlank()) {
                     txn.update(chatRef, mapOf(
                         "lastMessageStatus" to "read",
                         "lastMessageIsRead" to true,
                         "updatedAt" to FieldValue.serverTimestamp()
                     ))
                }

                // 2. Update Member Meta (Reader's status)
                txn.set(chatRef, mapOf("memberMeta" to mapOf(
                    userId to mapOf(
                        "lastOpenedAt" to FieldValue.serverTimestamp(),
                        "lastReadMessageId" to (messageId ?: currentLastId)
                    )
                )), SetOptions.merge())
                
                // 3. Update User's private meta
                 txn.set(userChatRef, mapOf(
                    "lastOpenedAt" to FieldValue.serverTimestamp(),
                    "lastReadMessageId" to (messageId ?: currentLastId)
                ), SetOptions.merge())
                
            }.await()
        } catch (e: Exception) {
            // Fallback: just try to update what we can if transaction fails (e.g. offline)
            // But for "read receipt" usually network is needed.
            // We'll silent fail or log?
             e.printStackTrace()
        }
    }

    override fun streamUserChatMeta(userId: String, chatId: String): Flow<UserChatMeta?> = callbackFlow {
        val reg = userChatMeta(userId, chatId).addSnapshotListener { snap, err ->
            if (err != null) { trySend(null); return@addSnapshotListener }
            val data = snap?.data
            trySend(
                if (data == null) null else UserChatMeta(
                    lastReadMessageId = data["lastReadMessageId"] as? String,
                    lastOpenedAt = data["lastOpenedAt"] as? Timestamp,
                    pinned = (data["pinned"] as? Boolean) ?: false,
                    mutedUntil = data["mutedUntil"] as? Timestamp
                )
            )
        }
        awaitClose { reg.remove() }
    }

    override fun streamChatMemberMeta(chatId: String): Flow<Map<String, Any>?> = callbackFlow {
        val reg = chats().document(chatId).addSnapshotListener { snap, err ->
            if (err != null) { trySend(null); return@addSnapshotListener }
            val mm = snap?.get("memberMeta") as? Map<String, Any>
            trySend(mm)
        }
        awaitClose { reg.remove() }
    }

    override suspend fun updateLastOpenedAt(chatId: String, userId: String) {
        chats().document(chatId).set(
            mapOf("memberMeta" to mapOf(userId to mapOf(
                "lastOpenedAt" to FieldValue.serverTimestamp()
            ))),
            SetOptions.merge()
        ).await()
    }

    override suspend fun updateUserPreview(ownerUserId: String, chatId: String, latest: Message?) {
        val ts = latest?.createdAt ?: latest?.createdAtClient
        // ✅ FIXED: Proper preview text for location messages
        val previewText: String? = when {
            latest == null -> null
            latest.type == MessageType.text -> latest.text?.take(500)
            latest.type == MessageType.image -> "Photo"
            latest.type == MessageType.video -> "Video"
            latest.type == MessageType.file -> latest.text ?: "File"
            latest.type == MessageType.location -> "Location"
            else -> latest.text ?: latest.type.name.lowercase().replaceFirstChar { it.uppercase() }
        }

        userChatMeta(ownerUserId, chatId).set(
            mapOf(
                "lastMessageId" to latest?.id,
                "lastMessageText" to previewText,
                "lastMessageType" to latest?.type?.name,
                "lastMessageSenderId" to latest?.senderId,
                "lastMessageTimestamp" to ts,
                "updatedAt" to FieldValue.serverTimestamp(),
                // 🛠️ FIX: Explicitly unhide/unarchive so the sender sees the chat reappearing
                "hidden" to false,
                "archived" to false
            ),
            SetOptions.merge()
        ).await()
    }

    // ========== attachments ==========

    override fun sendAttachmentMessage(
        chatId: String,
        senderId: String,
        localUri: Uri,
        context: Context
    ): Flow<ChatRepository.UploadStatus> = callbackFlow {
        // We'll reuse iBlockedPeer() check inside sendMessage (called at the end)
        val contentResolver = context.contentResolver
        val info = resolveUriInfo(contentResolver, localUri)
        val folder = when {
            info.contentType.startsWith("image/") -> "images"
            info.contentType.startsWith("video/") -> "videos"
            else -> "files"
        }
        val cleanName = sanitizeFileName(info.fileName.ifBlank { "file" })
        val path = "chat_uploads/$chatId/$folder/${System.currentTimeMillis()}_$cleanName"

        var thumbUrl: String? = null
        if (info.contentType.startsWith("video/")) {
            try {
                trySend(ChatRepository.UploadStatus.Progress(0.01f)) // Show starting
                val thumbFile = com.example.cnnct.chat.core.util.VideoThumbnailUtils.generateThumbnail(context, localUri)
                val thumbUri = Uri.fromFile(thumbFile)
                val thumbPath = "chat_uploads/$chatId/thumbs/${System.currentTimeMillis()}_thumb.jpg"
                val thumbRef = storage.reference.child(thumbPath)
                
                // Upload thumb (await)
                thumbRef.putFile(thumbUri).await()
                thumbUrl = thumbRef.downloadUrl.await().toString()
            } catch (e: Exception) {
                // Log but continue without thumbnail
                println("Failed to generate/upload thumbnail: ${e.message}")
            }
        }

        val ref = storage.reference.child(path)
        val meta = storageMetadata { contentType = info.contentType.ifBlank { "application/octet-stream" } }

        val uploadTask = ref.putFile(localUri, meta)
        
        uploadTask.addOnProgressListener { snap ->
            val p = if (snap.totalByteCount > 0) {
                (snap.bytesTransferred.toFloat() / snap.totalByteCount.toFloat())
            } else 0f
            trySend(ChatRepository.UploadStatus.Progress(p))
        }

        try {
            uploadTask.await()
            val downloadUrl = ref.downloadUrl.await().toString()

            val mType = when {
                info.contentType.startsWith("image/") -> MessageType.image
                info.contentType.startsWith("video/") -> MessageType.video
                else -> MessageType.file
            }
            
            val draft = MessageDraft(
                type = mType,
                text = cleanName, 
                mediaUrl = downloadUrl,
                thumbnailUrl = thumbUrl,
                contentType = info.contentType,
                fileName = cleanName,
                sizeBytes = info.sizeBytes
            )
            sendMessage(chatId, senderId, draft)
            trySend(ChatRepository.UploadStatus.Completed)
            close()
        } catch (e: Exception) {
            close(e)
        }
        
        awaitClose { 
            if (uploadTask.isInProgress) uploadTask.cancel() 
        }
    }

    data class UriInfo(
        val fileName: String,
        val sizeBytes: Long?,
        val contentType: String
    )

    private fun resolveUriInfo(cr: ContentResolver, uri: Uri): UriInfo {
        if (uri.scheme == "file") {
            val path = uri.path ?: return UriInfo("file", null, "application/octet-stream")
            val file = java.io.File(path)
            val name = file.name
            val size = file.length()
            val mime = guessMimeFromName(name)
            return UriInfo(name, size, mime)
        }

        var name = "file"
        var size: Long? = null
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c: Cursor ->
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (c.moveToFirst()) {
                        if (nameIdx != -1) name = c.getString(nameIdx) ?: name
                        if (sizeIdx != -1) size = if (!c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
                    }
                }
        } catch (_: Exception) {}
        
        val mime = cr.getType(uri) ?: guessMimeFromName(name)
        return UriInfo(fileName = name, sizeBytes = size, contentType = mime)
    }

    private fun guessMimeFromName(name: String): String {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".mp4") -> "video/mp4"
            n.endsWith(".mov") || n.endsWith(".m4v") -> "video/quicktime"
            n.endsWith(".pdf") -> "application/pdf"
            n.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            n.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> "application/octet-stream"
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[^\w\-. ]"""), "_")

    // ========== edit/delete ==========

    override suspend fun editMessage(
        chatId: String,
        messageId: String,
        editorId: String,
        newText: String
    ) {
        val chatRef = chats().document(chatId)
        val msgRef  = messages(chatId).document(messageId)
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return

        val chatSnap = chatRef.get().await()
        val lastId = chatSnap.getString("lastMessageId")

        db.runBatch { b ->
            b.update(msgRef, mapOf(
                "text" to trimmed,
                "editedAt" to FieldValue.serverTimestamp()
            ))
            if (lastId == messageId) {
                b.set(chatRef, mapOf(
                    "lastMessageText" to trimmed,
                    "updatedAt" to FieldValue.serverTimestamp()
                ), SetOptions.merge())
            }
        }.await()
    }

    override suspend fun deleteForEveryone(chatId: String, messageId: String, requesterId: String) {
        val chatRef = chats().document(chatId)
        val msgRef = messages(chatId).document(messageId)
        db.runTransaction { txn ->
            val chatSnap = txn.get(chatRef)
            val lastId = chatSnap.getString("lastMessageId") ?: ""
            txn.update(msgRef, mapOf(
                "deleted" to true,
                "deletedAt" to FieldValue.serverTimestamp(),
                "deletedBy" to requesterId,
                "text" to null
            ))
            if (messageId == lastId) {
                txn.set(chatRef, mapOf(
                    "lastMessageText" to "Message deleted",
                    "updatedAt" to FieldValue.serverTimestamp()
                ), SetOptions.merge())
            }
        }.await()
    }

    override suspend fun deleteForMe(chatId: String, messageId: String, userId: String) {
        messages(chatId).document(messageId).update(
            mapOf("hiddenFor" to com.google.firebase.firestore.FieldValue.arrayUnion(userId))
        ).await()
    }

    override fun newMessageId(chatId: String): String = messages(chatId).document().id

    override suspend fun hideChat(userId: String, chatId: String) {
        val now = Timestamp.now()
        userChatMeta(userId, chatId).set(
            mapOf(
                "hidden" to true,
                "clearedBefore" to now,
                "updatedAt" to now
            ),
            SetOptions.merge()
        ).await()
    }

    override suspend fun clearChatForMe(chatId: String, userId: String) {
        userChatMeta(userId, chatId).set(
            mapOf("clearedBefore" to FieldValue.serverTimestamp()),
            SetOptions.merge()
        ).await()
    }

    // ========== Home / List Impl ==========

    override fun listenMyChats(userId: String): Flow<List<ChatSummary>> = callbackFlow {
        val reg = chats()
            .whereArrayContains("members", userId)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { toChatSummary(it, userId) } ?: emptyList()
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    override fun listenMyUserChatMeta(userId: String): Flow<Map<String, UserChatMeta>> = callbackFlow {
        val reg = db.collection("userChats").document(userId).collection("chats")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    trySend(emptyMap())
                    return@addSnapshotListener
                }
                val map = snap?.documents?.associate { d ->
                    val data = d.data ?: emptyMap()
                    d.id to UserChatMeta(
                        lastReadMessageId = data["lastReadMessageId"] as? String,
                        lastOpenedAt = data["lastOpenedAt"] as? Timestamp,
                        pinned = (data["pinned"] as? Boolean) ?: false,
                        mutedUntil = data["mutedUntil"] as? Timestamp,
                        archived = (data["archived"] as? Boolean) ?: false,
                        hidden = (data["hidden"] as? Boolean) ?: false,
                        clearedBefore = data["clearedBefore"] as? Timestamp,
                        updatedAt = data["updatedAt"] as? Timestamp
                    )
                } ?: emptyMap()
                trySend(map)
            }
        awaitClose { reg.remove() }
    }

    override suspend fun setChatMutedUntil(userId: String, chatId: String, mutedUntilMs: Long?) {
        val data = if (mutedUntilMs == null) {
            mapOf("mutedUntil" to FieldValue.delete())
        } else {
            mapOf("mutedUntil" to Timestamp(mutedUntilMs / 1000, ((mutedUntilMs % 1000).toInt()) * 1_000_000))
        }
        userChatMeta(userId, chatId).set(data, SetOptions.merge()).await()
    }

    override suspend fun setArchived(userId: String, chatId: String, archived: Boolean) {
        val now = Timestamp.now()
        // Far future (Jan 1, 2100 UTC)
        val farFutureTs = Timestamp(java.util.Date(4102444800000L)) 

        val data = if (archived) {
            mapOf(
                "archived" to true,
                "archivedAt" to now,
                "updatedAt" to now,
                // auto-mute forever on archive
                "mutedUntil" to farFutureTs
            )
        } else {
            mapOf(
                "archived" to FieldValue.delete(),
                "archivedAt" to FieldValue.delete(),
                "updatedAt" to now
            )
        }
        userChatMeta(userId, chatId).set(data, SetOptions.merge()).await()
    }

    override suspend fun promoteIncomingLastMessagesToDelivered(userId: String, maxPerRun: Int) {
         if (userId.isBlank()) return
        val qSnap = chats()
            .whereArrayContains("members", userId)
            .whereEqualTo("lastMessageStatus", "sent")
            .limit(maxPerRun.toLong())
            .get()
            .await()

        if (qSnap.isEmpty) return

        val batch = db.batch()
        var updates = 0
        for (doc in qSnap.documents) {
            val sender = doc.getString("lastMessageSenderId")
            if (!sender.isNullOrBlank() && sender != userId) {
                batch.update(doc.reference, mapOf(
                    "lastMessageStatus" to "delivered",
                    "updatedAt" to Timestamp.now()
                ))
                updates++
            }
        }
        if (updates > 0) {
            batch.commit().await()
        }
    }

    override suspend fun muteChatForHours(userId: String, chatId: String, hours: Long) {
        val untilMs = System.currentTimeMillis() + hours * 60L * 60L * 1000L
        setChatMutedUntil(userId, chatId, untilMs)
    }

    override suspend fun muteChatForever(userId: String, chatId: String) {
        // 2100-01-01
        setChatMutedUntil(userId, chatId, 4102444800000L)
    }

    override suspend fun unmuteChat(userId: String, chatId: String) {
        setChatMutedUntil(userId, chatId, null)
    }

    private fun toChatSummary(meta: com.google.firebase.firestore.DocumentSnapshot, me: String): ChatSummary? {
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

            val lastMessageId = meta.getString("lastMessageId")

            val memberMeta = meta.get("memberMeta") as? Map<*, *>
            val myMeta = (memberMeta?.get(me) as? Map<*, *>)
            val iBlockedPeer = (myMeta?.get("iBlockedPeer") as? Boolean)
            val blockedByOther = (myMeta?.get("blockedByOther") as? Boolean)
            
            // Unread Logic (0 or 1)
            val myLastReadId = myMeta?.get("lastReadMessageId") as? String
            val unreadCount = if (senderId != me && !lastMessageId.isNullOrBlank() && lastMessageId != myLastReadId) 1 else 0

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
                groupPhotoUrl = meta.getString("groupPhotoUrl"),
                unreadCount = unreadCount
            )
        } catch (e: Exception) {
             null
        }
    }
}