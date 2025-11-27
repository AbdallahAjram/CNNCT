package com.example.cnnct.groups.view

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cnnct.common.view.UserAvatar
import com.example.cnnct.homepage.controller.HomePController
import com.example.cnnct.homepage.controller.PreloadedChatsCache
import com.example.cnnct.homepage.model.ChatSummary
import com.example.cnnct.homepage.view.BottomNavigationBar
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- Optional: batch names fetcher (stub) ----
object UsersController {
    fun getDisplayNames(
        uids: Set<String>,
        onResult: (Map<String, String>) -> Unit
    ) {
        if (uids.isEmpty()) { onResult(emptyMap()); return }
        val db = FirebaseFirestore.getInstance()
        val acc = mutableMapOf<String, String>()

        // Firestore whereIn caps at 10; chunk it
        val chunks = uids.toList().chunked(10)
        var remaining = chunks.size
        for (chunk in chunks) {
            db.collection("users")
                .whereIn(FieldPath.documentId(), chunk)
                .get()
                .addOnSuccessListener { snap ->
                    for (doc in snap.documents) {
                        acc[doc.id] = doc.getString("displayName") ?: "Unknown"
                    }
                    if (--remaining == 0) onResult(acc)
                }
                .addOnFailureListener {
                    Log.e("UsersController", "Failed fetching names", it)
                    if (--remaining == 0) onResult(acc)
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen() {
    // --- state
    var searchQuery by remember { mutableStateOf("") }
    var chatSummaries by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    var userMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // ---- 1) warm start from cache (instant paint)
    LaunchedEffect(Unit) {
        PreloadedChatsCache.chatSummaries?.let { cached ->
            val groups = cached.filter { it.type == "group" }
            chatSummaries = groups.sortedByDescending { it.lastMessageTimestamp?.toDate()?.time ?: 0L }

            val ids = buildSet {
                groups.forEach { c ->
                    c.members?.forEach { add(it) }
                    c.lastMessageSenderId?.let { add(it) }
                }
                remove(currentUserId)
            }
            if (ids.isNotEmpty()) {
                UsersController.getDisplayNames(ids) { userMap = it }
            }
        } ?: run {
            // If nothing cached (cold start), fetch once so UI isn't empty while listener attaches
            withContext(Dispatchers.IO) {
                HomePController.getUserChats { fetched ->
                    val groups = fetched.filter { it.type == "group" }
                    chatSummaries = groups.sortedByDescending { it.lastMessageTimestamp?.toDate()?.time ?: 0L }
                    PreloadedChatsCache.chatSummaries = fetched
                }
            }
        }
    }

    // ---- 2) realtime listener (keeps list live)
    DisposableEffect(currentUserId) {
        val db = FirebaseFirestore.getInstance()
        val query = db.collection("chats")
            .whereEqualTo("type", "group")
            .whereArrayContains("members", currentUserId)

        val reg = query.addSnapshotListener { snap, err ->
            if (err != null) {
                Log.e("GroupScreen", "Snapshot error", err)
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener

            val groups = snap.documents.mapNotNull { doc ->
                // Map to your ChatSummary and copy the doc id (assumes ChatSummary has id: String)
                doc.toObject(ChatSummary::class.java)?.copy(id = doc.id)
            }.sortedByDescending { it.lastMessageTimestamp?.toDate()?.time ?: 0L }

            chatSummaries = groups

            // merge back into global cache so the rest of the app is warm
            val existing = PreloadedChatsCache.chatSummaries ?: emptyList()
            val merged = (existing.filter { it.type != "group" } + groups)
            PreloadedChatsCache.chatSummaries = merged

            // refresh names for any new uids
            val ids = buildSet {
                groups.forEach { c ->
                    c.members?.forEach { add(it) }
                    c.lastMessageSenderId?.let { add(it) }
                }
                remove(currentUserId)
            }
            if (ids.isNotEmpty()) {
                UsersController.getDisplayNames(ids) { map -> userMap = userMap + map }
            }
        }
        onDispose { reg.remove() }
    }

    // ---- UI
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Groups") },
                actions = {
                    IconButton(onClick = {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                }
            )
        },
        bottomBar = { BottomNavigationBar(currentScreen = "groups") }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search groups...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .focusRequester(focusRequester),
                shape = MaterialTheme.shapes.large,
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Search")
                        }
                    }
                }
            )

            Spacer(Modifier.height(12.dp))

            val filteredGroups = remember(searchQuery, chatSummaries) {
                if (searchQuery.isBlank()) chatSummaries
                else chatSummaries.filter { it.groupName?.contains(searchQuery, ignoreCase = true) == true }
            }

            if (filteredGroups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No groups found", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn {
                    itemsIndexed(filteredGroups) { _, chat ->
                        GroupListItem(
                            chatSummary = chat,
                            currentUserId = currentUserId,
                            userMap = userMap,
                            onClick = {
                                val id = chat.id // ensure ChatSummary has `id: String`
                                if (id.isNotBlank()) {
                                    val intent = Intent(
                                        context,
                                        com.example.cnnct.chat.view.ChatActivity::class.java
                                    )
                                    intent.putExtra("chatId", id) // ChatActivity only needs this
                                    context.startActivity(intent)
                                } else {
                                    Log.e("GroupScreen", "Missing id for group: ${chat.groupName}")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/* ---------- Row item with avatar ---------- */

@Composable
private fun GroupListItem(
    chatSummary: ChatSummary,
    currentUserId: String,
    userMap: Map<String, String>,
    onClick: () -> Unit
) {
    val chatName = chatSummary.groupName ?: "Group"

    // If you later add a group photo URL to ChatSummary, plug it here:
    val groupPhotoUrl: String? = null
    // e.g., if you add `val groupPhotoUrl: String?` to ChatSummary, do:
    // val groupPhotoUrl = chatSummary.groupPhotoUrl

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 🔹 Group avatar (dynamic when URL exists; fallback to drawable via UserAvatar)
            UserAvatar(
                photoUrl = groupPhotoUrl,
                // If you have a dedicated group default drawable, pass it via fallbackRes:
                // fallbackRes = R.drawable.default_group,
                size = 44.dp,
                contentDescription = "Group avatar"
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Title + time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chatName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    chatSummary.lastMessageTimestamp?.let { ts ->
                        Text(
                            text = formatTimestamp(ts),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Last line + ticks
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val rawText = chatSummary.lastMessageText.takeIf { it.isNotBlank() } ?: "No messages yet"
                    val senderLabel: String? = when {
                        chatSummary.lastMessageSenderId.isNullOrBlank() -> null
                        chatSummary.lastMessageSenderId == currentUserId -> "You"
                        else -> userMap[chatSummary.lastMessageSenderId] ?: "Someone"
                    }
                    val finalText = if (senderLabel.isNullOrBlank()) rawText else "$senderLabel: $rawText"

                    Text(
                        text = finalText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )

                    if (chatSummary.lastMessageSenderId == currentUserId && chatSummary.lastMessageText.isNotBlank()) {
                        val effectiveStatus = chatSummary.lastMessageStatus
                            ?: if (chatSummary.lastMessageIsRead) "read" else "delivered"
                        val (ticks, color) = when (effectiveStatus) {
                            "read" -> "✓✓" to MaterialTheme.colorScheme.primary
                            "delivered" -> "✓✓" to MaterialTheme.colorScheme.onSurfaceVariant
                            "sent" -> "✓" to MaterialTheme.colorScheme.onSurfaceVariant
                            else -> null to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        if (ticks != null) {
                            Text(
                                text = ticks,
                                style = MaterialTheme.typography.bodySmall,
                                color = color,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ---------- helpers ---------- */

private fun formatTimestamp(ts: Timestamp): String {
    val date = ts.toDate()
    val now = Date()
    val diff = now.time - date.time
    val dayMs = 24 * 60 * 60 * 1000

    val fmt = when {
        diff < dayMs -> SimpleDateFormat("h:mm a", Locale.getDefault())
        diff < 7 * dayMs -> SimpleDateFormat("EEE", Locale.getDefault())
        else -> SimpleDateFormat("MMM d", Locale.getDefault())
    }
    return fmt.format(date)
}
