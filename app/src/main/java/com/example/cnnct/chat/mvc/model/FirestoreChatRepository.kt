package com.cnnct.chat.mvc.model

import android.content.ContentResolver
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
                    "pairKey" to pairKey,              // ✅ include pairKey to satisfy rules
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
        // Guard: if I blocked the peer, no-op (smoother UX than letting rules 403)
        // Note: we can only check *my* block list client-side.
        val chatSnap = chats().document(chatId).get().await()
        val members = (chatSnap.get("members") as? List<String>).orEmpty()
        val peerId = members.firstOrNull { it != senderId }
        if (peerId != null && iBlockedPeer(senderId, peerId)) {
            // silently ignore or throw? We choose to throw so UI can show a banner/toast if desired
            throw IllegalStateException("You blocked this user")
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

            val summaryText = when {
                draft.type == MessageType.text -> draft.text.orEmpty()
                (draft.contentType ?: "").startsWith("image/") -> "📷 Photo"
                (draft.contentType ?: "").startsWith("video/") -> "🎬 Video"
                draft.type.name.equals("location", ignoreCase = true) -> "📍 Location"
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
            .orderBy("createdAtClient")
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
        userChatMeta(userId, chatId).set(
            mapOf(
                "lastOpenedAt" to FieldValue.serverTimestamp(),
                "lastReadMessageId" to messageId
            ),
            SetOptions.merge()
        ).await()

        chats().document(chatId).set(
            mapOf("memberMeta" to mapOf(
                userId to mapOf(
                    "lastOpenedAt" to FieldValue.serverTimestamp(),
                    "lastReadMessageId" to messageId
                )
            )),
            SetOptions.merge()
        ).await()
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

    // ======= Preview helper for controller =======
    override suspend fun updateUserPreview(ownerUserId: String, chatId: String, latest: Message?) {
        val ts = latest?.createdAt ?: latest?.createdAtClient
        val previewText: String? = when {
            latest == null -> null
            latest.type == MessageType.text -> latest.text?.take(500)
            latest.type == MessageType.image -> "Photo"
            latest.type == MessageType.video -> "Video"
            latest.type == MessageType.file -> latest.text ?: "File"
            latest.type.name.equals("location", ignoreCase = true) -> "Location"
            else -> latest.text ?: latest.type.name.lowercase().replaceFirstChar { it.uppercase() }
        }

        userChatMeta(ownerUserId, chatId).set(
            mapOf(
                "lastMessageId" to latest?.id,
                "lastMessageText" to previewText,
                "lastMessageType" to latest?.type?.name,
                "lastMessageSenderId" to latest?.senderId,
                "lastMessageTimestamp" to ts,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
    }

    // ========== attachments ==========

    override suspend fun sendAttachmentMessage(
        chatId: String,
        senderId: String,
        localUri: Uri,
        contentResolver: ContentResolver
    ) {
        // We’ll reuse iBlockedPeer() check inside sendMessage (called at the end)
        val info = resolveUriInfo(contentResolver, localUri)
        val folder = when {
            info.contentType.startsWith("image/") -> "images"
            info.contentType.startsWith("video/") -> "videos"
            else -> "files"
        }
        val cleanName = sanitizeFileName(info.fileName.ifBlank { "file" })
        val path = "chat_uploads/$chatId/$folder/${System.currentTimeMillis()}_$cleanName"

        val ref = storage.reference.child(path)
        val meta = storageMetadata { contentType = info.contentType.ifBlank { "application/octet-stream" } }

        ref.putFile(localUri, meta).await()
        val downloadUrl = ref.downloadUrl.await().toString()

        val mType = if (info.contentType.startsWith("image/")) MessageType.image else MessageType.file
        val draft = MessageDraft(
            type = mType,
            text = cleanName,                 // show name as caption
            mediaUrl = downloadUrl,
            contentType = info.contentType,
            fileName = cleanName,
            sizeBytes = info.sizeBytes
        )
        sendMessage(chatId, senderId, draft)
    }

    data class UriInfo(
        val fileName: String,
        val sizeBytes: Long?,
        val contentType: String
    )

    private fun resolveUriInfo(cr: ContentResolver, uri: Uri): UriInfo {
        var name = "file"
        var size: Long? = null
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c: Cursor ->
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (nameIdx != -1) name = c.getString(nameIdx) ?: name
                    if (sizeIdx != -1) size = if (!c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
                }
            }
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
            mapOf("hiddenFor" to FieldValue.arrayUnion(userId))
        ).await()
    }

    override fun newMessageId(chatId: String): String = messages(chatId).document().id
}
