package com.example.cnnct.chat.view

import android.content.Intent
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
import com.cnnct.chat.mvc.controller.ChatController
import com.cnnct.chat.mvc.model.FirestoreChatRepository
import com.cnnct.chat.mvc.view.ChatScreen
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.homepage.controller.HomePController
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.getField
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// bring these in so header actions compile
import com.example.cnnct.chat.view.PersonInfoActivity
import com.example.cnnct.chat.view.GroupInfoActivity

class ChatActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val chatId = intent.getStringExtra("chatId") ?: return
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

        val repo = FirestoreChatRepository(Firebase.firestore)
        val controller = ChatController(repo, currentUserId)

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

            // Keep a live listener to the peer's user doc to drive the private header
            var peerHeaderReg: ListenerRegistration? by remember { mutableStateOf<ListenerRegistration?>(null) }

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

            // ---- live chat document listener (keeps header + members fresh, AND feeds mute state if you still use it)
            val mutedByAdmin by produceState(initialValue = false, key1 = chatId, key2 = currentUserId) {
                val reg = db.collection("chats").document(chatId)
                    .addSnapshotListener { snap, _ ->
                        val data = snap?.data.orEmpty()

                        val type = (data["type"] as? String) ?: "private"
                        chatType = type

                        val members = (data["members"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                        memberIds = members

                        if (type == "private") {
                            val other = members.firstOrNull { it != currentUserId }
                            otherUserId = other

                            // stop previous peer header listener if any
                            peerHeaderReg?.remove()
                            peerHeaderReg = null

                            if (other != null) {
                                // Live header from the peer user doc
                                peerHeaderReg = db.collection("users").document(other)
                                    .addSnapshotListener { uSnap, _ ->
                                        val dn = uSnap?.getString("displayName") ?: "Unknown"
                                        val photo = uSnap?.getString("photoUrl")
                                        nameMap = mapOf(other to dn)
                                        photoMap = mapOf(other to photo)
                                        headerTitle = dn
                                        headerSubtitle = null
                                        headerPhotoUrl = photo
                                    }
                            } else {
                                // fallback
                                headerTitle = "Chat"
                                headerSubtitle = null
                                headerPhotoUrl = null
                            }
                        } else {
                            // group header
                            headerTitle = (data["groupName"] as? String) ?: "Group"
                            headerSubtitle = "${members.size} members"
                            headerPhotoUrl = (data["groupPhotoUrl"] as? String)
                        }

                        // If you’ve removed group mutes entirely, this will always be false
                        val mutedList = (data["mutedMemberIds"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                        value = (type == "group") && (currentUserId in mutedList)
                    }
                awaitDispose {
                    reg.remove()
                    peerHeaderReg?.remove()
                    peerHeaderReg = null
                }
            }

            // Load peer-specific state for private chat (block mirror + controller flags)
            LaunchedEffect(otherUserId) {
                val other = otherUserId ?: return@LaunchedEffect
                controller.setPeerUser(other)

                // realtime block state (me → blocks collection)
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
            }

            // Hydrate names/photos for group members (best effort, chunked)
            LaunchedEffect(chatType, memberIds) {
                if (chatType != "group" || memberIds.isEmpty()) return@LaunchedEffect
                val accNames = mutableMapOf<String, String>()
                val accPhotos = mutableMapOf<String, String?>()
                for (chunk in memberIds.chunked(10)) {
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
            }

            // --- helpers: write mirrored block flags into chat.memberMeta
            suspend fun updateChatBlockFlags(
                chatId: String,
                me: String,
                peer: String,
                blocked: Boolean
            ) {
                runCatching {
                    Firebase.firestore.collection("chats").document(chatId)
                        .set(
                            mapOf(
                                "memberMeta" to mapOf(
                                    me to mapOf("iBlockedPeer" to blocked),
                                    peer to mapOf("blockedByOther" to blocked)
                                ),
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
                        .await()
                }.onFailure {
                    // ignore UI-wise (or log)
                }
            }

            fun blockPeerFromTopBar(chatId: String, me: String, peerId: String) {
                db.collection("users").document(me)
                    .collection("blocks").document(peerId)
                    .set(mapOf("blocked" to true, "createdAt" to FieldValue.serverTimestamp()))
                    .addOnSuccessListener {
                        scope.launch { updateChatBlockFlags(chatId, me, peerId, true) }
                        Toast.makeText(this@ChatActivity, "Blocked", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this@ChatActivity, "Block failed", Toast.LENGTH_SHORT).show()
                    }
            }

            // Member meta (stream) for read receipts + "blockedByOther"
            val memberMeta by produceState<Map<String, Any>?>(initialValue = null, key1 = chatId) {
                repo.streamChatMemberMeta(chatId).collect { value = it }
            }

            val blockedByOther: Boolean by remember(memberMeta, currentUserId) {
                mutableStateOf(
                    ((memberMeta?.get(currentUserId) as? Map<*, *>)?.get("blockedByOther") as? Boolean) == true
                )
            }

            // Presence map
            val membersOnlineMap by produceState<Map<String, Long?>>(initialValue = emptyMap(), key1 = memberIds) {
                if (memberIds.isEmpty()) { value = emptyMap(); return@produceState }
                val listeners = mutableListOf<ListenerRegistration>()
                val acc = mutableMapOf<String, Long?>()
                memberIds.forEach { uid ->
                    val reg = db.collection("users").document(uid)
                        .addSnapshotListener { snap, _ ->
                            val ms = snap?.getTimestamp("lastOnlineAt")?.toDate()?.time
                            acc[uid] = ms
                            value = acc.toMap()
                        }
                    listeners.add(reg)
                }
                awaitDispose { listeners.forEach { it.remove() } }
            }

            // 1-1 extras for ticks (read/open markers)
            val otherMetaFromChat by produceState<Map<String, Any>?>(initialValue = null, key1 = chatId) {
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

                        // If you’ve removed mutes in UI, this will be false there anyway
                        mutedByAdmin = mutedByAdmin,

                        iBlockedPeer = iBlockedOther,
                        blockedByOther = blockedByOther,
                        clearedBeforeMs = clearedBeforeMs,
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
                        onHeaderClick = {
                            if (chatType == "private" && otherUserId != null) {
                                startActivity(
                                    Intent(ctx, PersonInfoActivity::class.java).putExtra("uid", otherUserId)
                                )
                            } else if (chatType == "group") {
                                startActivity(
                                    Intent(ctx, GroupInfoActivity::class.java).putExtra("chatId", chatId)
                                )
                            }
                        },
                        onSearch = {
                            Toast.makeText(ctx, "Search (placeholder)", Toast.LENGTH_SHORT).show()
                        },
                        onClearChat = {
                            scope.launch {
                                runCatching { com.example.cnnct.chat.controller.ChatInfoController().clearChatForMe(chatId) }
                                    .onSuccess { Toast.makeText(ctx, "Chat cleared", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(ctx, "Failed to clear chat", Toast.LENGTH_SHORT).show() }
                            }
                        },
                        onBlockPeer = {
                            val peer = otherUserId ?: return@ChatScreen
                            blockPeerFromTopBar(chatId, currentUserId, peer)
                        },
                        onLeaveGroup = {
                            // Real leave implementation
                            scope.launch {
                                try {
                                    db.runTransaction { txn ->
                                        val chatRef = db.collection("chats").document(chatId)
                                        val snap = txn.get(chatRef)

                                        val members = (snap.get("members") as? List<*>)?.filterIsInstance<String>()?.toMutableList()
                                            ?: mutableListOf()
                                        val admins  = (snap.get("adminIds") as? List<*>)?.filterIsInstance<String>()?.toMutableList()
                                            ?: mutableListOf()

                                        // Remove me
                                        members.remove(currentUserId)
                                        admins.remove(currentUserId)

                                        val updates = mutableMapOf<String, Any?>(
                                            "members" to members,
                                            "adminIds" to admins,
                                            "updatedAt" to FieldValue.serverTimestamp()
                                        )

                                        // If no admins remain but members still exist, promote the first member
                                        if (admins.isEmpty() && members.isNotEmpty()) {
                                            updates["adminIds"] = listOf(members.first())
                                        }

                                        txn.update(chatRef, updates as Map<String, Any?>)

                                        // Optional: mark in userChats that I left (keeps history if you want)
                                        val myLinkRef = db.collection("userChats").document(currentUserId)
                                            .collection("chats").document(chatId)
                                        txn.set(
                                            myLinkRef,
                                            mapOf("leftAt" to FieldValue.serverTimestamp()),
                                            SetOptions.merge()
                                        )

                                        null
                                    }.await()

                                    Toast.makeText(this@ChatActivity, "You left the group", Toast.LENGTH_SHORT).show()
                                    finish()
                                } catch (e: Exception) {
                                    Toast.makeText(this@ChatActivity, "Failed to leave: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
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
