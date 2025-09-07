package com.example.cnnct.chat.view

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.tasks.await
import com.cnnct.chat.mvc.model.FirestoreChatRepository
import com.cnnct.chat.mvc.controller.ChatController
import com.cnnct.chat.mvc.view.ChatScreen
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.homepage.controller.HomePController

class ChatActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val chatId = intent.getStringExtra("chatId") ?: return
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

        val repo = FirestoreChatRepository(Firebase.firestore)
        val controller = ChatController(repo, currentUserId)

        setContent {
            val context = LocalContext.current // ✅ use inside Compose
            val db = Firebase.firestore
            var chatType by remember { mutableStateOf("private") }
            var otherUserId by remember { mutableStateOf<String?>(null) }

            var nameMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            var photoMap by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
            var memberIds by remember { mutableStateOf<List<String>>(emptyList()) }

            var headerTitle by remember { mutableStateOf("Chat") }
            var headerSubtitle by remember { mutableStateOf<String?>(null) }
            var headerPhotoUrl by remember { mutableStateOf<String?>(null) }

            var blockedSet by remember { mutableStateOf<Set<String>>(emptySet()) }

            // Load chat meta
            LaunchedEffect(chatId) {
                val chatSnap = db.collection("chats").document(chatId).get().await()
                val type = chatSnap.getString("type") ?: "private"
                chatType = type

                val members = (chatSnap.get("members") as? List<String>).orEmpty()
                memberIds = members

                blockedSet = (chatSnap.get("blocked") as? List<String>)?.toSet() ?: emptySet()

                if (type == "private") {
                    otherUserId = members.firstOrNull { it != currentUserId }
                    otherUserId?.let { other ->
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
                    headerPhotoUrl = chatSnap.getString("photoUrl")
                }
            }

            // Member meta
            val memberMeta by produceState<Map<String, Any>?>(initialValue = null, chatId) {
                repo.streamChatMemberMeta(chatId).collect { value = it }
            }

            // Last online map
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
                blockedUserIds = blockedSet,
                onlineMap = membersOnlineMap,
                onBack = { finish() },
                onCallClick = {
                    val calleeId = otherUserId ?: return@ChatScreen
                    val callsController = CallsController(context)

                    callsController.startCall(
                        calleeId,
                        onCreated = {
                            Toast.makeText(context, "Calling...", Toast.LENGTH_SHORT).show()
                        },
                        onError = { error ->
                            Toast.makeText(context, "Failed to start call: ${error.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val chatId = intent.getStringExtra("chatId") ?: return
        val me = FirebaseAuth.getInstance().currentUser?.uid ?: return
        HomePController.markLastMessageRead(chatId, me)
    }
}
