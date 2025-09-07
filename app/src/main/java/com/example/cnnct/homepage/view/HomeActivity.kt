package com.example.cnnct.homepage.view

import android.app.DownloadManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.cnnct.R
import com.example.cnnct.auth.controller.LoginActivity
import com.example.cnnct.chat.view.ChatActivity
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.calls.view.IncomingCallScreen
import com.example.cnnct.homepage.controller.HomePController
import com.example.cnnct.homepage.model.ChatSummary
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import androidx.compose.ui.ExperimentalComposeUiApi
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope

class HomeActivity : ComponentActivity() {

    private lateinit var callsController: CallsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callsController = CallsController(this)
        callsController.startIncomingWatcher()

        setContent {
            HomeScreen(callsController = callsController, onLogout = {
                FirebaseAuth.getInstance().signOut()
                Log.d("HomeActivity", "User logged out")
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callsController.stopIncomingWatcher()
        callsController.clear()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun BottomNavigationBar(currentScreen: String = "chats") {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    NavigationBar {
        NavigationBarItem(
            icon = { Icon(Icons.Default.Chat, contentDescription = "Chats") },
            label = { Text("Chats") },
            selected = currentScreen == "chats",
            onClick = {
                if (currentScreen != "chats") {
                    context.startActivity(Intent(context, HomeActivity::class.java))
                }
            }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Group, contentDescription = "Groups") },
            label = { Text("Groups") },
            selected = currentScreen == "groups",
            onClick = {
                if (currentScreen != "groups") {
                    context.startActivity(Intent(context, com.example.cnnct.groups.view.GroupActivity::class.java))
                }
            }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Call, contentDescription = "Calls") },
            label = { Text("Calls") },
            selected = currentScreen == "calls",
            onClick = {
                if (currentScreen != "calls") {
                    context.startActivity(Intent(context, com.example.cnnct.calls.view.CallsActivity::class.java))
                }
            }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            selected = currentScreen == "settings",
            onClick = {
                if (currentScreen != "settings") {
                    context.startActivity(Intent(context, com.example.cnnct.settings.view.SettingsActivity::class.java))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(callsController: CallsController, onLogout: () -> Unit) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    var chatSummaries by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    var userMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var userPhotoMap by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf("") }

    var onlineMap by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var presenceRegs by remember { mutableStateOf<List<ListenerRegistration>>(emptyList()) }

    var showNewChatSheet by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var menuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val incomingCall by callsController.incomingCall.collectAsState()

    // Diagnostic: toast and log when an incoming ringing call for me is detected.
    LaunchedEffect(incomingCall) {
        incomingCall?.let { call ->
            val myUid = FirebaseAuth.getInstance().currentUser?.uid
            if (call.status == "ringing" && call.calleeId == myUid) {
                // Show a toast so you can verify the watcher is seeing the call doc
                Toast.makeText(context, "Incoming call from ${call.callerId}", Toast.LENGTH_LONG).show()
                Log.d("HomeScreen", "Incoming call detected: ${call.callId} from ${call.callerId}")

                // OPTIONAL: if you later create an IncomingCallActivity, you can launch it here:
                // val i = Intent(context, IncomingCallActivity::class.java).apply {
                //     putExtra("callId", call.callId)
                //     putExtra("callerId", call.callerId)
                //     flags = Intent.FLAG_ACTIVITY_NEW_TASK
                // }
                // context.startActivity(i)
            }
        }
    }

    // Show incoming call overlay (existing UI)
    incomingCall?.let { call ->
        // Only show overlay if the call is ringing and intended for me (safe guard)
        val myUid = FirebaseAuth.getInstance().currentUser?.uid
        if (call.status == "ringing" && call.calleeId == myUid) {
            IncomingCallScreen(
                callerId = call.callerId,
                onAccept = { callsController.acceptCall(call.callId) },
                onReject = { callsController.rejectCall(call.callId) }
            )
        }
    }

    // ==== Real-time chats ====
    DisposableEffect(currentUserId) {
        if (currentUserId.isBlank()) return@DisposableEffect onDispose {}
        val reg: ListenerRegistration = db.collection("userChats")
            .document(currentUserId)
            .collection("chats")
            .orderBy("lastMessageTimestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("HomeScreen", "listen previews error", err)
                    chatSummaries = emptyList()
                    return@addSnapshotListener
                }

                val previewDocs = snap?.documents ?: emptyList()
                val previewById = previewDocs.associateBy { it.id }

                scope.launch {
                    try {
                        val chatsSnap = withContext(Dispatchers.IO) {
                            db.collection("chats")
                                .whereArrayContains("members", currentUserId)
                                .get()
                                .await()
                        }

                        val merged = chatsSnap.documents.mapNotNull { meta ->
                            try {
                                val chatId = meta.id
                                val preview = previewById[chatId]
                                val groupName = meta.getString("groupName")
                                val members = (meta.get("members") as? List<String>) ?: emptyList()
                                val type = meta.getString("type") ?: "private"
                                val text = preview?.getString("lastMessageText")
                                    ?: meta.getString("lastMessageText")
                                    ?: ""
                                val ts = preview?.getTimestamp("lastMessageTimestamp")
                                    ?: meta.getTimestamp("lastMessageTimestamp")
                                val senderId = preview?.getString("lastMessageSenderId")
                                    ?: meta.getString("lastMessageSenderId")
                                val statusRaw = preview?.getString("lastMessageStatus")
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
                                Log.e("HomeScreen", "merge chat ${meta.id}", e)
                                null
                            }
                        }

                        chatSummaries = merged.sortedByDescending {
                            it.lastMessageTimestamp?.toDate()?.time
                                ?: it.updatedAt?.toDate()?.time
                                ?: Long.MIN_VALUE
                        }
                    } catch (e: Exception) {
                        Log.e("HomeScreen", "merge previews + chats failed", e)
                        chatSummaries = emptyList()
                    }
                }
            }
        onDispose { reg.remove() }
    }

    // Real-time user info & presence
    LaunchedEffect(chatSummaries) {
        val targets = chatSummaries
            .flatMap { it.members + (it.lastMessageSenderId ?: "") }
            .filter { it.isNotBlank() && it != currentUserId }
            .distinct()

        // Names & photos
        val nameMap = mutableMapOf<String, String>()
        val photoMap = mutableMapOf<String, String?>()
        for (chunk in targets.chunked(10)) {
            try {
                val snap = db.collection("users")
                    .whereIn(FieldPath.documentId(), chunk)
                    .get()
                    .await()
                snap.documents.forEach { doc ->
                    nameMap[doc.id] = doc.getString("displayName") ?: "Unknown"
                    photoMap[doc.id] = doc.getString("photoUrl")
                }
            } catch (e: Exception) {
                Log.e("HomeScreen", "userMap/photoMap chunk failed", e)
            }
        }
        userMap = nameMap
        userPhotoMap = photoMap

        // Presence
        presenceRegs.forEach { it.remove() }
        presenceRegs = emptyList()
        val newOnline = onlineMap.toMutableMap()
        val regs = mutableListOf<ListenerRegistration>()
        for (chunk in targets.chunked(10)) {
            if (chunk.isEmpty()) continue
            val reg = db.collection("users")
                .whereIn(FieldPath.documentId(), chunk)
                .addSnapshotListener { snap, err ->
                    if (err != null || snap == null) return@addSnapshotListener
                    val m = newOnline.toMutableMap()
                    for (doc in snap.documents) {
                        val ts = doc.getTimestamp("lastOnlineAt")?.toDate()?.time
                        m[doc.id] = ts
                    }
                    onlineMap = m
                }
            regs += reg
        }
        presenceRegs = regs
    }

    // Delivery polling
    val pollSeconds = remember { 25 }
    LaunchedEffect(pollSeconds, currentUserId) {
        if (currentUserId.isBlank()) return@LaunchedEffect
        while (true) {
            try {
                HomePController.promoteIncomingLastMessagesToDelivered(currentUserId, maxPerRun = 25)
            } catch (e: Exception) {
                Log.e("HomeScreen", "deliver-poll pass failed", e)
            }
            delay(pollSeconds * 1000L)
        }
    }

    // UI Scaffold
    Scaffold(
        topBar = { HomeTopBar(focusRequester, keyboardController, context, menuExpanded, onLogout) },
        bottomBar = { BottomNavigationBar(currentScreen = "chats") },
        floatingActionButton = { FloatingActionButton(onClick = { /* Show New Chat Sheet */ }) { Icon(Icons.Default.Add, null) } }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            ChatListView(chatSummaries, searchQuery, userMap, userPhotoMap, onlineMap, currentUserId, context)
            // IncomingCallScreen is already shown above reactively
        }
    }
}

// Additional Composables for cleaner HomeScreen
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeTopBar(
    focusRequester: FocusRequester,
    keyboardController: SoftwareKeyboardController?, // <<< use SoftwareKeyboardController? not the Local*
    context: android.content.Context,
    menuExpanded: Boolean,
    onLogout: () -> Unit
) {
    TopAppBar(
        title = { Image(painterResource(R.drawable.logo2), null, Modifier.height(40.dp).offset(y = 4.dp)) },
        actions = {
            IconButton(onClick = { focusRequester.requestFocus(); keyboardController?.show() }) { Icon(Icons.Default.Search, null) }
            Box {
                var expanded by remember { mutableStateOf(menuExpanded) }
                IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Settings") }, onClick = {
                        expanded = false
                        context.startActivity(Intent(context, com.example.cnnct.settings.view.SettingsActivity::class.java))
                    })
                    DropdownMenuItem(text = { Text("Logout") }, onClick = { expanded = false; onLogout() })
                }
            }
        }
    )
}

@Composable
fun ChatListView(
    chatSummaries: List<ChatSummary>,
    searchQuery: String,
    userMap: Map<String, String>,
    userPhotoMap: Map<String, String?>,
    onlineMap: Map<String, Long?>,
    currentUserId: String,
    context: android.content.Context
) {
    val filtered = chatSummaries.filter { chat ->
        if (searchQuery.isBlank()) true else {
            val name = when (chat.type) {
                "group" -> chat.groupName ?: ""
                "private" -> {
                    val other = chat.members.firstOrNull { it != currentUserId }
                    userMap[other] ?: ""
                }
                else -> ""
            }
            name.contains(searchQuery, true)
        }
    }

    if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No chats found") }
    else LazyColumn {
        items(filtered, key = { it.id }) { chat ->
            val photoUrlForRow = when (chat.type) {
                "private" -> {
                    val other = chat.members.firstOrNull { it != currentUserId }
                    userPhotoMap[other]
                }
                "group" -> null
                else -> null
            }
            ChatListItem(chatSummary = chat, currentUserId = currentUserId, userMap = userMap, onClick = {
                val intent = Intent(context, ChatActivity::class.java).putExtra("chatId", chat.id)
                context.startActivity(intent)
            }, onlineMap = onlineMap, blockedUserIds = emptySet(), photoUrl = photoUrlForRow)
        }
    }
}
