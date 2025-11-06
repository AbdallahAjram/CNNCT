package com.example.cnnct.homepage.view

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
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
import com.example.cnnct.notifications.MuteStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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

    // my block list (uids)
    var blockedPeers by remember { mutableStateOf<Set<String>>(emptySet()) }

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val incomingCall by callsController.incomingCall.collectAsState()

    // 🔔 MuteStore lifecycle + recomposition bridge
    var muteVersion by remember { mutableStateOf(0) }

    DisposableEffect(currentUserId) {
        if (currentUserId.isBlank()) return@DisposableEffect onDispose { }

        // start mute sync
        MuteStore.start()
        val listener: () -> Unit = { muteVersion++ }   // keeps list reacting to mute changes
        MuteStore.addListener(listener)

        onDispose {
            MuteStore.removeListener(listener)
        }
    }

    // ===== Selection state =====
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    fun toggleSelect(id: String) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        if (selectedIds.isEmpty()) selectionMode = false
    }
    fun clearSelection() {
        selectedIds.clear()
        selectionMode = false
    }

    // Helpers
    fun peerOf(chat: ChatSummary): String? =
        if (chat.type == "private") chat.members.firstOrNull { it != currentUserId } else null

    // Mirror block flags to chat.memberMeta so the OTHER device can see they're blocked
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
        } catch (e: Exception) {
            Log.w("HomeScreen", "updateChatBlockFlags failed", e)
        }
    }

    // ===== Confirmation dialog state for block/unblock =====
    var showBlockDialog by remember { mutableStateOf(false) }
    var pendingBlockPeerId by remember { mutableStateOf<String?>(null) }
    var pendingBlockChatId by remember { mutableStateOf<String?>(null) }
    var isPendingBlock by remember { mutableStateOf(false) }

    // ===== Confirmation dialog state for delete-for-me =====
    var showDeleteDialog by remember { mutableStateOf(false) }

    // ===== Confirmation dialog for archive =====
    var showArchiveDialog by remember { mutableStateOf(false) }

    // Block / Unblock Firestore writes
    fun blockPeer(chatId: String, peerId: String) {
        db.collection("users").document(currentUserId)
            .collection("blocks").document(peerId)
            .set(mapOf("blocked" to true, "createdAt" to FieldValue.serverTimestamp()))
            .addOnSuccessListener {
                scope.launch { updateChatBlockFlags(chatId, currentUserId, peerId, true) }
                Toast.makeText(context, "Blocked", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.e("HomeScreen", "blockPeer failed", e)
                Toast.makeText(context, "Block failed", Toast.LENGTH_SHORT).show()
            }
    }

    fun unblockPeer(chatId: String, peerId: String) {
        db.collection("users").document(currentUserId)
            .collection("blocks").document(peerId)
            .delete()
            .addOnSuccessListener {
                scope.launch { updateChatBlockFlags(chatId, currentUserId, peerId, false) }
                Toast.makeText(context, "Unblocked", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.e("HomeScreen", "unblockPeer failed", e)
                Toast.makeText(context, "Unblock failed", Toast.LENGTH_SHORT).show()
            }
    }

    // ===== Watch my block list =====
    DisposableEffect(currentUserId) {
        if (currentUserId.isBlank()) return@DisposableEffect onDispose {}
        val reg = db.collection("users").document(currentUserId)
            .collection("blocks")
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                blockedPeers = snap.documents
                    .filter { it.getBoolean("blocked") == true }
                    .map { it.id }
                    .toSet()
            }
        onDispose { reg.remove() }
    }

    // ===== Incoming call handling =====
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

    // ===== Listen to /chats =====
    DisposableEffect(currentUserId) {
        if (currentUserId.isBlank()) return@DisposableEffect onDispose {}
        val reg = HomePController.listenMyChats { joined ->
            chatSummaries = joined
            selectedIds.removeAll { id -> joined.none { it.id == id } }
            if (selectedIds.isEmpty()) selectionMode = false
        }
        onDispose { reg?.remove() }
    }

    // ===== Names/photos/phones + presence =====
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

        // presence listeners
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

    // ===== Delivery polling =====
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

    // ===== Search users =====
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

    // 🔵 Unread chats count for bottom badge: last msg from other && not read && has timestamp
    val unreadChatsCount by remember(chatSummaries, currentUserId) {
        mutableStateOf(
            chatSummaries.count {
                it.lastMessageTimestamp != null &&
                        it.lastMessageSenderId != null &&
                        it.lastMessageSenderId != currentUserId &&
                        !it.lastMessageIsRead
            }
        )
    }

    // ===== UI =====
    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { clearSelection() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Close selection")
                        }
                    },
                    title = { Text("${selectedIds.size} selected") },
                    actions = {
                        val selectedCount = selectedIds.size

                        // --- MUTE with options (1 hour, 12 hours, forever) ---
                        var muteMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { muteMenu = true }) {
                                Icon(Icons.Default.NotificationsOff, contentDescription = "Mute")
                            }
                            DropdownMenu(expanded = muteMenu, onDismissRequest = { muteMenu = false }) {

                                fun applyMuteFor(hours: Long?) {
                                    scope.launch {
                                        val now = System.currentTimeMillis()
                                        selectedIds.toList().forEach { chatId ->
                                            when (hours) {
                                                null -> {
                                                    // Forever = year 2100 sentinel
                                                    val farFuture = java.util.GregorianCalendar(2100, 0, 1, 0, 0, 0).timeInMillis
                                                    MuteStore.prime(chatId, farFuture) // local instant
                                                    HomePController.muteChatForever(currentUserId, chatId)
                                                }
                                                0L -> {
                                                    MuteStore.clearLocal(chatId)
                                                    HomePController.unmuteChat(currentUserId, chatId)
                                                }
                                                else -> {
                                                    val until = now + hours * 60L * 60L * 1000L
                                                    MuteStore.prime(chatId, until)
                                                    HomePController.muteChatForHours(currentUserId, chatId, hours)
                                                }
                                            }
                                        }
                                        Toast.makeText(context, "Mute updated", Toast.LENGTH_SHORT).show()
                                        clearSelection()
                                    }
                                    muteMenu = false
                                }

                                DropdownMenuItem(
                                    text = { Text("Mute for 1 hour") },
                                    onClick = { applyMuteFor(1L) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Mute for 12 hours") },
                                    onClick = { applyMuteFor(12L) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Mute forever") },
                                    onClick = { applyMuteFor(null) } // null => forever
                                )
                                Divider()
                                DropdownMenuItem(
                                    text = { Text("Unmute") },
                                    onClick = { applyMuteFor(0L) }
                                )
                            }
                        }

                        // 🗃️ ARCHIVE with confirmation
                        IconButton(onClick = { showArchiveDialog = true }) {
                            Icon(Icons.Default.Archive, contentDescription = "Archive")
                        }

                        // 🔴 DELETE with confirmation dialog (client-side delete-for-me)
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }

                        // Block/Unblock only when exactly one chat is selected and it's private
                        if (selectedCount == 1) {
                            val selectedChat = chatSummaries.firstOrNull { it.id == selectedIds.first() }
                            val peerId = selectedChat?.let { peerOf(it) }
                            val isBlocked = peerId != null && blockedPeers.contains(peerId)
                            if (peerId != null && selectedChat != null) {
                                IconButton(onClick = {
                                    // Open confirm dialog
                                    pendingBlockPeerId = peerId
                                    pendingBlockChatId = selectedChat.id
                                    isPendingBlock = !isBlocked
                                    showBlockDialog = true
                                }) {
                                    Icon(
                                        Icons.Default.Block,
                                        contentDescription = if (isBlocked) "Unblock" else "Block"
                                    )
                                }
                            }
                        }
                    }
                )
            } else {
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
                                        text = { Text("Archived") },
                                        onClick = {
                                            expanded = false
                                            context.startActivity(
                                                Intent(context, com.example.cnnct.settings.view.ArchiveSettingsActivity::class.java)
                                            )
                                        }
                                    )
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
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(
                    onClick = {
                        showUserPicker = true
                        scope.launch {
                            delay(50)
                            keyboardController?.show()
                        }
                    }
                ) { Icon(Icons.Default.Add, contentDescription = "New chat") }
            }
        },
        bottomBar = { if (!selectionMode) BottomNavigationBar(currentScreen = "chats", unreadChatsCount = unreadChatsCount) }
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
                                    .combinedClickable(
                                        onClick = {
                                            val me = FirebaseAuth.getInstance().currentUser!!.uid
                                            HomePController.createOrOpenPrivate(me = me, other = uid) { chatId ->
                                                if (chatId != null) {
                                                    showUserPicker = false
                                                    searchQuery = ""
                                                    val intent = Intent(context, ChatActivity::class.java)
                                                        .putExtra("chatId", chatId)
                                                    context.startActivity(intent)
                                                } else {
                                                    Toast.makeText(context, "Couldn’t start chat (permissions/index).", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        onLongClick = {}
                                    )
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

            // Chat list
            ChatListView(
                chatSummaries = chatSummaries,
                searchQuery = searchQuery,
                userMap = userMap,
                userPhotoMap = userPhotoMap,
                userPhoneMap = userPhoneMap,
                onlineMap = onlineMap,
                currentUserId = currentUserId,
                selectionMode = selectionMode,
                isSelected = { id -> selectedIds.contains(id) },
                onLongPress = { id ->
                    if (!selectionMode) selectionMode = true
                    toggleSelect(id)
                },
                onOpen = { chatId ->
                    if (selectionMode) {
                        toggleSelect(chatId)
                    } else {
                        val intent = Intent(context, ChatActivity::class.java).putExtra("chatId", chatId)
                        context.startActivity(intent)
                    }
                },
                blockedPeers = blockedPeers,
                // this lambda lets rows render a mute badge and will recompose when muteVersion changes
                isMuted = { chatId -> muteVersion /* read to subscribe */; MuteStore.isMuted(chatId) }
            )
        }
    }

    // 🧱 Confirm Block / Unblock
    if (showBlockDialog) {
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            title = {
                Text(if (isPendingBlock) "Block this user?" else "Unblock this user?")
            },
            text = {
                Text(
                    if (isPendingBlock)
                        "They won’t be able to message or call you."
                    else
                        "You will be able to message this user again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val chatId = pendingBlockChatId ?: return@TextButton
                    val peerId = pendingBlockPeerId ?: return@TextButton
                    if (isPendingBlock) blockPeer(chatId, peerId) else unblockPeer(chatId, peerId)
                    showBlockDialog = false
                    // keep or clear selection; UX: clear
                    clearSelection()
                }) {
                    Text(if (isPendingBlock) "Block" else "Unblock")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 🗑️ Confirm Delete (client-side "delete for me")
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete chat?") },
            text = { Text("This hides the chat for you and clears the preview. Others still keep the chat.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        // apply delete-for-me on all selected chats
                        val ids = selectedIds.toList()
                        scope.launch {
                            var ok = 0
                            ids.forEach { chatId ->
                                try {
                                    HomePController.deleteChatForMe(currentUserId, chatId)
                                    ok++
                                } catch (e: Exception) {
                                    Log.e("HomeScreen", "deleteChatForMe failed for $chatId", e)
                                }
                            }
                            if (ok > 0) {
                                Toast.makeText(context, "Deleted $ok chat(s)", Toast.LENGTH_SHORT).show()
                            }
                            clearSelection()
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 🗃️ Confirm Archive
    if (showArchiveDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            title = { Text("Archive chats?") },
            text = { Text("Chats will move to Archived and disappear from your home list.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showArchiveDialog = false
                        val ids = selectedIds.toList()
                        scope.launch {
                            var ok = 0
                            val farFuture = java.util.GregorianCalendar(2100, 0, 1, 0, 0, 0).timeInMillis

                            ids.forEach { chatId ->
                                try {
                                    // 🔊 local instant effect
                                    MuteStore.prime(chatId, farFuture)

                                    // server-side: archive + mutedUntil in one write
                                    HomePController.setArchived(currentUserId, chatId, archived = true)
                                    ok++
                                } catch (e: Exception) {
                                    Log.e("HomeScreen", "archive failed for $chatId", e)
                                }
                            }
                            if (ok > 0) Toast.makeText(context, "Archived $ok chat(s)", Toast.LENGTH_SHORT).show()
                            clearSelection()
                        }


                    }
                ) { Text("Archive") }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveDialog = false }) { Text("Cancel") }
            }
        )
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
    selectionMode: Boolean,
    isSelected: (String) -> Boolean,
    onLongPress: (String) -> Unit,
    onOpen: (String) -> Unit,
    blockedPeers: Set<String>,
    isMuted: (String) -> Boolean
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
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No chats found") }
    } else {
        LazyColumn {
            items(filtered, key = { it.id }) { chat ->
                val other = if (chat.type == "private") chat.members.firstOrNull { it != currentUserId } else null

                // choose the correct photo URL to pass down
                val photoUrlForRow: String? = when (chat.type) {
                    "private" -> other?.let { userPhotoMap[it] }
                    "group" -> chat.groupPhotoUrl
                    else -> null
                }

                // blocked indicator logic
                val blockedForMe = (other != null && blockedPeers.contains(other)) ||
                        (chat.iBlockedPeer == true) ||
                        (chat.blockedByOther == true)
                val blockedSetForRow = if (other != null && blockedForMe) setOf(other) else emptySet()

                Box(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onOpen(chat.id) },
                            onLongClick = { onLongPress(chat.id) }
                        )
                        .padding(vertical = 2.dp)
                ) {
                    ChatListItem(
                        chatSummary = chat,
                        currentUserId = currentUserId,
                        userMap = userMap,
                        onClick = null,
                        onlineMap = onlineMap,
                        blockedUserIds = blockedSetForRow,
                        photoUrl = photoUrlForRow,      // use groupPhotoUrl when group
                        selectionMode = selectionMode,
                        selected = isSelected(chat.id),
                        muted = isMuted(chat.id)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomNavigationBar(currentScreen: String = "chats", unreadChatsCount: Int = 0) {
    val context = LocalContext.current
    NavigationBar {
        NavigationBarItem(
            icon = {
                BadgedBox(
                    badge = {
                        if (unreadChatsCount > 0) {
                            Badge { Text(unreadChatsCount.toString()) }
                        }
                    }
                ) {
                    Icon(Icons.Default.Chat, contentDescription = "Chats")
                }
            },
            label = { Text("Chats") },
            selected = currentScreen == "chats",
            onClick = { if (currentScreen != "chats") context.startActivity(Intent(context, HomeActivity::class.java)) }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Group, contentDescription = "Groups") },
            label = { Text("Groups") },
            selected = currentScreen == "groups",
            onClick = { if (currentScreen != "groups") context.startActivity(Intent(context, com.example.cnnct.groups.view.GroupActivity::class.java)) }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Call, contentDescription = "Calls") },
            label = { Text("Calls") },
            selected = currentScreen == "calls",
            onClick = { if (currentScreen != "calls") context.startActivity(Intent(context, com.example.cnnct.calls.view.CallsActivity::class.java)) }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            selected = currentScreen == "settings",
            onClick = { if (currentScreen != "settings") context.startActivity(Intent(context, com.example.cnnct.settings.view.SettingsActivity::class.java)) }
        )
    }
}
