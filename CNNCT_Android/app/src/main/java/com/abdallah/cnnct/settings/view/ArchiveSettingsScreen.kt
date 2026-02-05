package com.abdallah.cnnct.settings.view

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.abdallah.cnnct.homepage.model.ChatSummary

import com.abdallah.cnnct.homepage.viewmodel.HomeViewModel
import com.abdallah.cnnct.notifications.MuteStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = HomeViewModel.Factory)
) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    val uiState by viewModel.uiState.collectAsState()
    val chatSummaries = uiState.archivedChats

    var userMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var userPhotoMap by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }

    // presence (optional here)
    var onlineMap by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var presenceRegs by remember { mutableStateOf<List<ListenerRegistration>>(emptyList()) }

    // selection
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

    // block list
    var blockedPeers by remember { mutableStateOf<Set<String>>(emptySet()) }

    // mute bridge
    var muteVersion by remember { mutableStateOf(0) }
    DisposableEffect(currentUserId) {
        if (currentUserId.isBlank()) return@DisposableEffect onDispose {}
        MuteStore.start()
        val listener: () -> Unit = { muteVersion++ }
        MuteStore.addListener(listener)
        onDispose { MuteStore.removeListener(listener) }
    }

    val scope = rememberCoroutineScope()

    fun peerOf(chat: ChatSummary): String? =
        if (chat.type == "private") chat.members.firstOrNull { it != currentUserId } else null

    // Names/photos (presence not required)
    LaunchedEffect(chatSummaries) {
        val targets = chatSummaries
            .flatMap { it.members + (it.lastMessageSenderId ?: "") }
            .filter { it.isNotBlank() && it != currentUserId }
            .distinct()

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
                Log.e("ArchiveScreen", "user maps chunk failed", e)
            }
        }
        userMap = nameMap
        userPhotoMap = photoMap
    }

    // Block list watch
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

    // Confirm dialogs
    var showUnarchiveDialog by remember { mutableStateOf(false) }
    var showMuteMenu by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var blockTargetChatId by remember { mutableStateOf<String?>(null) }
    var blockTargetPeerId by remember { mutableStateOf<String?>(null) }
    var isPendingBlock by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(if (selectionMode) "${selectedIds.size} selected" else "Archived") },
                actions = {
                    if (selectionMode) {
                        // Mute / Unmute
                        Box {
                            IconButton(onClick = { showMuteMenu = true }) {
                                Icon(Icons.Default.NotificationsOff, contentDescription = "Mute")
                            }
                            DropdownMenu(expanded = showMuteMenu, onDismissRequest = { showMuteMenu = false }) {
                                fun applyMuteFor(hours: Long?) {
                                    scope.launch {
                                        selectedIds.toList().forEach { chatId ->
                                            when (hours) {
                                                null -> {
                                                    val farFuture = java.util.GregorianCalendar(2100, 0, 1, 0, 0, 0).timeInMillis
                                                    MuteStore.prime(chatId, farFuture)
                                                    viewModel.muteChat(chatId)
                                                }
                                                0L -> {
                                                    MuteStore.clearLocal(chatId)
                                                    viewModel.unmuteChat(chatId)
                                                }
                                                else -> {
                                                    // Hours not supported in VM yet
                                                }
                                            }
                                        }
                                        Toast.makeText(context, "Mute updated", Toast.LENGTH_SHORT).show()
                                        selectionMode = false
                                        selectedIds.clear()
                                    }
                                    showMuteMenu = false
                                }

                                DropdownMenuItem(text = { Text("Mute forever") }, onClick = { applyMuteFor(null) })
                                Divider()
                                DropdownMenuItem(text = { Text("Unmute") }, onClick = { applyMuteFor(0L) })
                            }
                        }

                        // Unarchive
                        IconButton(onClick = { showUnarchiveDialog = true }) {
                            Icon(Icons.Default.Unarchive, contentDescription = "Unarchive")
                        }

                        // Block (single)
                        if (selectedIds.size == 1) {
                            val chat = chatSummaries.firstOrNull { it.id == selectedIds.first() }
                            val peerId = chat?.let { peerOf(it) }
                            if (peerId != null) {
                                IconButton(onClick = {
                                    blockTargetChatId = chat.id
                                    blockTargetPeerId = peerId
                                    isPendingBlock = true
                                    showBlockDialog = true
                                }) {
                                    Icon(Icons.Default.Block, contentDescription = "Block")
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (chatSummaries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No archived chats")
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(chatSummaries, key = { it.id }) { chat ->
                    val other = if (chat.type == "private") chat.members.firstOrNull { it != currentUserId } else null
                    val photoUrlForRow = when (chat.type) {
                        "private" -> other?.let { userPhotoMap[it] }
                        "group" -> chat.groupPhotoUrl
                        else -> null
                    }
                    
                    // Box wrapper for clickable + list item
                     Box(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    val i = Intent(context, com.abdallah.cnnct.homepage.view.HomeActivity::class.java).putExtra("chatId", chat.id)
                                    context.startActivity(i)
                                },
                                onLongClick = {
                                    toggleSelect(chat.id)
                                    if (!selectionMode) selectionMode = true
                                }
                            )
                            .padding(vertical = 2.dp)
                    ) {
                        com.abdallah.cnnct.homepage.view.ChatListItem(
                            chatSummary = chat,
                            currentUserId = currentUserId,
                            userMap = userMap,
                            onClick = null, // Handled above
                            onlineMap = onlineMap,
                            blockedUserIds = if (other != null && blockedPeers.contains(other)) setOf(other) else emptySet(),
                            photoUrl = photoUrlForRow,
                            selectionMode = selectionMode,
                            selected = selectedIds.contains(chat.id),
                            muted = MuteStore.isMuted(chat.id)
                        )
                    }
                }
            }
        }
    }

    // Confirm Unarchive
    if (showUnarchiveDialog) {
        AlertDialog(
            onDismissRequest = { showUnarchiveDialog = false },
            title = { Text("Unarchive chats?") },
            text = { Text("Chats will return to your main list.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnarchiveDialog = false
                        val ids = selectedIds.toList()
                        scope.launch {
                            var ok = 0
                            ids.forEach { chatId ->
                                try {
                                    viewModel.unarchiveChat(chatId)
                                    ok++
                                } catch (e: Exception) {
                                    Log.e("ArchiveScreen", "unarchive failed for $chatId", e)
                                }
                            }
                            if (ok > 0) Toast.makeText(context, "Unarchived $ok chat(s)", Toast.LENGTH_SHORT).show()
                            clearSelection()
                        }
                    }
                ) { Text("Unarchive") }
            },
            dismissButton = { TextButton(onClick = { showUnarchiveDialog = false }) { Text("Cancel") } }
        )
    }

    // Confirm Block (Archive view: single)
    if (showBlockDialog) {
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            title = { Text(if (isPendingBlock) "Block this user?" else "Unblock this user?") },
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
                    val chatId = blockTargetChatId ?: return@TextButton
                    val peerId = blockTargetPeerId ?: return@TextButton
                    if (isPendingBlock) {
                        FirebaseFirestore.getInstance()
                            .collection("users").document(currentUserId)
                            .collection("blocks").document(peerId)
                            .set(mapOf("blocked" to true))
                            .addOnSuccessListener {
                                Toast.makeText(context, "Blocked", Toast.LENGTH_SHORT).show()
                            }
                    } else {
                        FirebaseFirestore.getInstance()
                            .collection("users").document(currentUserId)
                            .collection("blocks").document(peerId)
                            .delete()
                            .addOnSuccessListener {
                                Toast.makeText(context, "Unblocked", Toast.LENGTH_SHORT).show()
                            }
                    }
                    showBlockDialog = false
                    clearSelection()
                }) { Text(if (isPendingBlock) "Block" else "Unblock") }
            },
            dismissButton = { TextButton(onClick = { showBlockDialog = false }) { Text("Cancel") } }
        )
    }
}
