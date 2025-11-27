package com.example.cnnct.homepage.view

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cnnct.R
import com.example.cnnct.calls.IncomingCallActivity
import com.example.cnnct.calls.controller.CallsController
import com.example.cnnct.calls.view.IncomingCallScreen
import com.example.cnnct.chat.view.ChatActivity
import com.example.cnnct.homepage.controller.HomePController
import com.example.cnnct.homepage.model.ChatSummary
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.example.cnnct.calls.view.CallsActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(callsController: CallsController, onLogout: () -> Unit) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    var chatSummaries by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    var userMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var userPhotoMap by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var userPhoneMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var searchQuery by remember { mutableStateOf("") }
    var showUserPicker by remember { mutableStateOf(false) }
    var userSearchResults by remember { mutableStateOf<List<Triple<String, String, String?>>>(emptyList()) }

    var onlineMap by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var presenceRegs by remember { mutableStateOf<List<ListenerRegistration>>(emptyList()) }

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val incomingCall by callsController.incomingCall.collectAsState()

    // ====== Incoming call surfacing (inline + activity) ======
    LaunchedEffect(incomingCall) {
        incomingCall?.let { call ->
            val myUid = FirebaseAuth.getInstance().currentUser?.uid
            val isRecent = call.createdAt?.let { ts ->
                val age = System.currentTimeMillis() - ts.toDate().time
                age <= 30_000
            } ?: false

            if (call.status == "ringing" && call.calleeId == myUid && isRecent) {
                val i = Intent(context, IncomingCallActivity::class.java).apply {
                    putExtra("callId", call.callId)
                    putExtra("callerId", call.callerId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(i)
            }
        }
    }

    incomingCall?.let { call ->
        val myUid = FirebaseAuth.getInstance().currentUser?.uid
        val isRecent = call.createdAt?.let { ts ->
            val age = System.currentTimeMillis() - ts.toDate().time
            age <= 30_000
        } ?: false

        if (call.status == "ringing" && call.calleeId == myUid && isRecent) {
            IncomingCallScreen(
                callerId = call.callerId,
                onAccept = { callsController.acceptCall(call.callId) },
                onReject = { callsController.rejectCall(call.callId) }
            )
        }
    }

    // ====== Real-time: listen directly to /chats ======
    DisposableEffect(currentUserId) {
        if (currentUserId.isBlank()) return@DisposableEffect onDispose {}
        val reg = HomePController.listenMyChats { joined ->
            chatSummaries = joined
        }
        onDispose { reg?.remove() }
    }

    // ====== Real-time users (names/photos/phones + presence) ======
    LaunchedEffect(chatSummaries) {
        val targets = chatSummaries
            .flatMap { it.members + (it.lastMessageSenderId ?: "") }
            .filter { it.isNotBlank() && it != currentUserId }
            .distinct()

        val nameMap = mutableMapOf<String, String>()
        val photoMap = mutableMapOf<String, String?>()
        val phoneMap = mutableMapOf<String, String>()

        for (chunk in targets.chunked(10)) {
            try {
                val snap = db.collection("users")
                    .whereIn(FieldPath.documentId(), chunk)
                    .get()
                    .await()
                snap.documents.forEach { doc ->
                    nameMap[doc.id] = doc.getString("displayName") ?: "Unknown"
                    photoMap[doc.id] = doc.getString("photoUrl")
                    doc.getString("phoneNumber")?.let { phoneMap[doc.id] = it }
                }
            } catch (e: Exception) {
                Log.e("HomeScreen", "user maps chunk failed", e)
            }
        }
        userMap = nameMap
        userPhotoMap = photoMap
        userPhoneMap = phoneMap

        // Presence listeners
        presenceRegs.forEach { it.remove() }
        presenceRegs = emptyList()
        val regs = mutableListOf<ListenerRegistration>()
        for (chunk in targets.chunked(10)) {
            if (chunk.isEmpty()) continue
            val reg = db.collection("users")
                .whereIn(FieldPath.documentId(), chunk)
                .addSnapshotListener { snap, err ->
                    if (err != null || snap == null) return@addSnapshotListener
                    val m = onlineMap.toMutableMap()
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

    // ====== Delivery polling ======
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

    // ====== Search users (for + button) with debounce ======
    LaunchedEffect(searchQuery, showUserPicker) {
        if (!showUserPicker) return@LaunchedEffect
        val q = searchQuery.trim()
        if (q.length < 2) {
            userSearchResults = emptyList()
            return@LaunchedEffect
        }
        delay(200)
        HomePController.searchUsersByNameOrPhone(q, limit = 20) { rows ->
            userSearchResults = rows
        }
    }

    // ====== UI ======
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(painterResource(R.drawable.logo2), null, Modifier.height(40.dp))
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Default.MoreVert, null)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = {
                                        expanded = false
                                        context.startActivity(
                                            Intent(context, com.example.cnnct.settings.view.SettingsActivity::class.java)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Logout") },
                                    onClick = { expanded = false; onLogout() }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    showUserPicker = true
                    scope.launch {
                        delay(50)
                        keyboardController?.show()
                    }
                }
            ) { Icon(Icons.Default.Add, contentDescription = "New chat") }
        },
        bottomBar = { BottomNavigationBar(currentScreen = "chats") }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(if (showUserPicker) "Search users by name or phone..." else "Search chats...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .focusRequester(focusRequester),
                singleLine = true
            )

            // User picker panel
            if (showUserPicker) {
                if (searchQuery.isBlank()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Type a name or phone number to start a chat", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .padding(bottom = 8.dp)
                    ) {
                        items(userSearchResults) { (uid, name, phone) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        HomePController.createOrGetPrivateChat(uid) { chatId ->
                                            if (chatId != null) {
                                                showUserPicker = false
                                                searchQuery = ""
                                                val intent = Intent(context, ChatActivity::class.java)
                                                    .putExtra("chatId", chatId)
                                                context.startActivity(intent)
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                                    if (!phone.isNullOrBlank()) {
                                        Text(phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text("Start", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                            Divider()
                        }
                        if (userSearchResults.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text("No users found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Divider(thickness = 1.dp)
            }

            ChatListView(
                chatSummaries = chatSummaries,
                searchQuery = searchQuery,
                userMap = userMap,
                userPhotoMap = userPhotoMap,
                userPhoneMap = userPhoneMap,
                onlineMap = onlineMap,
                currentUserId = currentUserId,
                context = context
            )
        }
    }
}

@Composable
fun ChatListView(
    chatSummaries: List<ChatSummary>,
    searchQuery: String,
    userMap: Map<String, String>,
    userPhotoMap: Map<String, String?>,
    userPhoneMap: Map<String, String>,
    onlineMap: Map<String, Long?>,
    currentUserId: String,
    context: android.content.Context
) {
    val filtered = chatSummaries.filter { chat ->
        if (searchQuery.isBlank()) true else {
            val (name, phone) = when (chat.type) {
                "group" -> (chat.groupName ?: "") to ""
                "private" -> {
                    val other = chat.members.firstOrNull { it != currentUserId }
                    (userMap[other] ?: "") to (userPhoneMap[other] ?: "")
                }
                else -> "" to ""
            }
            name.contains(searchQuery, true) || phone.contains(searchQuery, true)
        }
    }

    if (filtered.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("No chats found")
        }
    } else {
        LazyColumn {
            items(filtered, key = { it.id }) { chat ->
                val photoUrlForRow = when (chat.type) {
                    "private" -> {
                        val other = chat.members.firstOrNull { it != currentUserId }
                        userPhotoMap[other]
                    }
                    "group" -> null
                    else -> null
                }
                ChatListItem(
                    chatSummary = chat,
                    currentUserId = currentUserId,
                    userMap = userMap,
                    onClick = {
                        val intent = Intent(context, ChatActivity::class.java).putExtra("chatId", chat.id)
                        context.startActivity(intent)
                    },
                    onlineMap = onlineMap,
                    blockedUserIds = emptySet(),
                    photoUrl = photoUrlForRow
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomNavigationBar(currentScreen: String = "chats") {
    val context = LocalContext.current

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
                    context.startActivity(Intent(context, CallsActivity::class.java))
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
