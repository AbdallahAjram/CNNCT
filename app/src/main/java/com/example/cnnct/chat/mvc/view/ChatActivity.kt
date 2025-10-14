package com.example.cnnct.chat.view

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.cnnct.chat.mvc.model.FirestoreChatRepository
import com.cnnct.chat.mvc.controller.ChatController
import com.cnnct.chat.mvc.view.ChatScreen
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.homepage.controller.HomePController
import com.example.cnnct.chat.controller.ChatInfoController

class ChatActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val chatId = intent.getStringExtra("chatId") ?: return
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

        val repo = FirestoreChatRepository(Firebase.firestore)
        val controller = ChatController(repo, currentUserId)
        val infoCtrl = ChatInfoController()

        setContent {
            val ctx = LocalContext.current
            val db = Firebase.firestore
            val scope = rememberCoroutineScope()

            var chatType by remember { mutableStateOf("private") }
            var otherUserId by remember { mutableStateOf<String?>(null) }

            var nameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            var photoMap by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
            var memberIds by remember { mutableStateOf<List<String>>(emptyList()) }

            var headerTitle by remember { mutableStateOf("Chat") }
            var headerSubtitle by remember { mutableStateOf<String?>(null) }
            var headerPhotoUrl by remember { mutableStateOf<String?>(null) }

            var iBlockedOther by remember { mutableStateOf(false) }
            var blockListener: ListenerRegistration? by remember { mutableStateOf<ListenerRegistration?>(null) }

            // --- per-user "cleared" timestamp (null = nothing cleared)
            val clearedBeforeMs by produceState<Long?>(initialValue = null, key1 = currentUserId, key2 = chatId) {
                if (currentUserId.isBlank()) { value = null; return@produceState }
                val reg = db.collection("userChats").document(currentUserId)
                    .collection("chats").document(chatId)
                    .addSnapshotListener { snap, _ ->
                        val ts = snap?.getTimestamp("clearedBefore")?.toDate()?.time
                        value = ts
                    }
                awaitDispose { reg.remove() }
            }

            // --- helpers copied from HomeScreen (block + mirror flags) ---
            suspend fun updateChatBlockFlags(chatId: String, me: String, peer: String, blocked: Boolean) {
                try {
                    db.collection("chats").document(chatId).set(
                        mapOf(
                            "memberMeta" to mapOf(
                                me to mapOf("iBlockedPeer" to blocked),
                                peer to mapOf("blockedByOther" to blocked)
                            ),
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
                } catch (_: Exception) { /* no-op UI-wise */ }
            }

            fun blockPeerFromTopBar(chatId: String, me: String, peerId: String) {
                db.collection("users").document(me)
                    .collection("blocks").document(peerId)
                    .set(mapOf("blocked" to true, "createdAt" to FieldValue.serverTimestamp()))
                    .addOnSuccessListener {
                        scope.launch { updateChatBlockFlags(chatId, me, peerId, true) }
                        Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(ctx, "Block failed", Toast.LENGTH_SHORT).show()
                    }
            }
            // --------------------------------------------------------------

            // Load chat meta
            LaunchedEffect(chatId) {
                controller.openChat(chatId)

                val chatSnap = db.collection("chats").document(chatId).get().await()
                val type = chatSnap.getString("type") ?: "private"
                chatType = type

                val members = (chatSnap.get("members") as? List<String>).orEmpty()
                memberIds = members

                if (type == "private") {
                    otherUserId = members.firstOrNull { it != currentUserId }
                    controller.setPeerUser(otherUserId)

                    otherUserId?.let { other ->
                        blockListener?.remove()
                        blockListener = db.collection("users")
                            .document(currentUserId)
                            .collection("blocks")
                            .document(other)
                            .addSnapshotListener { snap, _ ->
                                val blocked = snap?.exists() == true
                                iBlockedOther = blocked
                                controller.setIBlockedPeer(blocked)
                            }

                        val userDoc = db.collection("users").document(other).get().await()
                        val dn = userDoc.getString("displayName") ?: "Unknown"
                        val photo = userDoc.getString("photoUrl")
                        nameMap = mapOf(other to dn)
                        photoMap = mapOf(other to photo)
                        headerTitle = dn
                        headerSubtitle = null
                        headerPhotoUrl = photo
                    }
                } else {
                    val ids = members
                    val accNames = mutableMapOf<String, String>()
                    val accPhotos = mutableMapOf<String, String?>()
                    for (chunk in ids.chunked(10)) {
                        if (chunk.isEmpty()) continue
                        val usersSnap = db.collection("users")
                            .whereIn(FieldPath.documentId(), chunk)
                            .get().await()
                        for (doc in usersSnap.documents) {
                            accNames[doc.id] = doc.getString("displayName") ?: "Unknown"
                            accPhotos[doc.id] = doc.getString("photoUrl")
                        }
                    }
                    nameMap = accNames
                    photoMap = accPhotos
                    headerTitle = chatSnap.getString("groupName") ?: "Group"
                    headerSubtitle = "${members.size} members"
                    headerPhotoUrl = chatSnap.getString("groupPhotoUrl")
                }
            }

            // Member meta (stream)
            val memberMeta by produceState<Map<String, Any>?>(initialValue = null, chatId) {
                repo.streamChatMemberMeta(chatId).collect { value = it }
            }

            val blockedByOther: Boolean by remember(memberMeta, currentUserId) {
                mutableStateOf(
                    ((memberMeta?.get(currentUserId) as? Map<*, *>)?.get("blockedByOther") as? Boolean) == true
                )
            }

            // Presence map
            val membersOnlineMap by produceState<Map<String, Long?>>(initialValue = emptyMap(), memberIds) {
                if (memberIds.isEmpty()) { value = emptyMap(); return@produceState }
                val dbLocal = Firebase.firestore
                val listeners = mutableListOf<ListenerRegistration>()
                val acc = mutableMapOf<String, Long?>()
                memberIds.forEach { uid ->
                    val reg = dbLocal.collection("users").document(uid)
                        .addSnapshotListener { snap, _ ->
                            val ms = snap?.getTimestamp("lastOnlineAt")?.toDate()?.time
                            acc[uid] = ms
                            value = acc.toMap()
                        }
                    listeners.add(reg)
                }
                awaitDispose { listeners.forEach { it.remove() } }
            }

            // 1-1 extras for ticks
            val otherMetaFromChat by produceState<Map<String, Any>?>(initialValue = null, chatId) {
                repo.streamChatMemberMeta(chatId).collect { value = it }
            }
            val otherLastReadId = remember(otherMetaFromChat, otherUserId) {
                val u = otherUserId ?: return@remember null
                (otherMetaFromChat?.get(u) as? Map<*, *>)?.get("lastReadMessageId") as? String
            }
            val otherLastOpenedAtMs = remember(otherMetaFromChat, otherUserId) {
                val u = otherUserId ?: return@remember null
                val ts = (otherMetaFromChat?.get(u) as? Map<*, *>)?.get("lastOpenedAt")
                (ts as? com.google.firebase.Timestamp)?.toDate()?.time
            }

            Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                    ChatScreen(
                        chatId = chatId,
                        currentUserId = currentUserId,
                        controller = controller,
                        chatType = chatType,
                        title = headerTitle,
                        subtitle = headerSubtitle,
                        nameOf = { uid -> nameMap[uid] ?: uid },
                        userPhotoOf = { uid: String -> photoMap[uid] },
                        headerPhotoUrl = headerPhotoUrl,
                        otherUserId = otherUserId,
                        otherLastReadId = otherLastReadId,
                        otherLastOpenedAtMs = otherLastOpenedAtMs,
                        memberIds = memberIds,
                        memberMeta = memberMeta,

                        iBlockedPeer = iBlockedOther,
                        blockedByOther = blockedByOther,

                        clearedBeforeMs = clearedBeforeMs, // 👈 filter by per-user clear timestamp

                        blockedUserIds = if (iBlockedOther && otherUserId != null) setOf(otherUserId!!) else emptySet(),
                        onlineMap = membersOnlineMap,

                        onBack = { finish() },
                        onCallClick = {
                            val calleeId = otherUserId ?: return@ChatScreen
                            val callsController = CallsController(ctx)
                            callsController.startCall(
                                calleeId,
                                onCreated = { /* handled by CallsController */ },
                                onError = { error ->
                                    Toast.makeText(
                                        ctx,
                                        "Failed to start call: ${error.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                        },

                        // ---- header & menu actions passed to the TopBar ----
                        onHeaderClick = {
                            if (chatType == "private" && otherUserId != null) {
                                ctx.startActivity(
                                    android.content.Intent(ctx, PersonInfoActivity::class.java)
                                        .putExtra("uid", otherUserId)
                                )
                            } else if (chatType == "group") {
                                ctx.startActivity(
                                    android.content.Intent(ctx, GroupInfoActivity::class.java)
                                        .putExtra("chatId", chatId)
                                )
                            }
                        },
                        onSearch = {
                            Toast.makeText(ctx, "Search (placeholder)", Toast.LENGTH_SHORT).show()
                        },
                        onClearChat = {
                            // Write clearedBefore for ME only, UI will auto-filter by clearedBeforeMs
                            scope.launch {
                                runCatching { infoCtrl.clearChatForMe(chatId) }
                                    .onSuccess { Toast.makeText(ctx, "Chat cleared", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(ctx, "Failed to clear chat", Toast.LENGTH_SHORT).show() }
                            }
                        },
                        onBlockPeer = {
                            val peer = otherUserId ?: return@ChatScreen
                            blockPeerFromTopBar(chatId, currentUserId, peer)
                        },
                        onLeaveGroup = {
                            Toast.makeText(ctx, "Leave group (placeholder)", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val chatId = intent.getStringExtra("chatId") ?: return
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return
        HomePController.markLastMessageRead(chatId, me)
        com.example.cnnct.notifications.ForegroundTracker.setCurrentChat(chatId)

        val id = (chatId).hashCode()
        androidx.core.app.NotificationManagerCompat.from(this).cancel(id)
        com.example.cnnct.notifications.NotificationsStore.clearHistory(this, chatId)
    }

    override fun onPause() {
        super.onPause()
        com.example.cnnct.notifications.ForegroundTracker.setCurrentChat(null)
    }
}
