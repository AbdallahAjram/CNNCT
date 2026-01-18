package com.example.cnnct.groups.view

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.example.cnnct.R
import com.example.cnnct.common.view.UserAvatar
import com.example.cnnct.groups.controller.GroupController
import com.example.cnnct.homepage.controller.HomePController
import com.example.cnnct.homepage.controller.PreloadedChatsCache
import com.example.cnnct.homepage.model.ChatSummary
import com.example.cnnct.homepage.view.BottomNavigationBar
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ---------------- UsersController (displayName fetch, chunked) ---------------- */

object UsersController {
    fun getDisplayNames(
        uids: Set<String>,
        onResult: (Map<String, String>) -> Unit
    ) {
        if (uids.isEmpty()) { onResult(emptyMap()); return }
        val db = FirebaseFirestore.getInstance()
        val acc = mutableMapOf<String, String>()
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

/* ------------------------ Create Group Sheet state ------------------------ */
private data class SelectableUser(
    val uid: String,
    val name: String,
    val phone: String? = null,
    var selected: Boolean = false
)

/* ------------------------ Screen ------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // State
    var searchQuery by remember { mutableStateOf("") }
    var chatSummaries by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    var userMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Create group sheet visibility
    var showCreate by remember { mutableStateOf(false) }

    // 1) Warm from cache
    LaunchedEffect(Unit) {
        PreloadedChatsCache.chatSummaries?.let { cached ->
            val groups = cached.filter { it.type == "group" }
            chatSummaries = groups.sortedByDescending { it.lastMessageTimestamp?.toDate()?.time ?: 0L }

            val ids = buildSet {
                groups.forEach { c -> c.members.forEach { add(it) }; c.lastMessageSenderId?.let { add(it) } }
                remove(currentUserId)
            }
            if (ids.isNotEmpty()) {
                UsersController.getDisplayNames(ids) { userMap = it }
            }
        } ?: run {
            withContext(Dispatchers.IO) {
                HomePController.getUserChats { fetched ->
                    val groups = fetched.filter { it.type == "group" }
                    chatSummaries = groups.sortedByDescending { it.lastMessageTimestamp?.toDate()?.time ?: 0L }
                    PreloadedChatsCache.chatSummaries = fetched
                }
            }
        }
    }

    // 2) Realtime listener
    DisposableEffect(currentUserId) {
        if (currentUserId.isBlank()) return@DisposableEffect onDispose { }
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
                doc.toObject(ChatSummary::class.java)?.apply { id = doc.id }
            }.sortedByDescending { it.lastMessageTimestamp?.toDate()?.time ?: 0L }

            chatSummaries = groups
            val existing = PreloadedChatsCache.chatSummaries ?: emptyList()
            val merged = (existing.filter { it.type != "group" } + groups)
            PreloadedChatsCache.chatSummaries = merged

            val ids = buildSet {
                groups.forEach { c -> c.members.forEach { add(it) }; c.lastMessageSenderId?.let { add(it) } }
                remove(currentUserId)
            }
            if (ids.isNotEmpty()) {
                UsersController.getDisplayNames(ids) { map -> userMap = userMap + map }
            }
        }
        onDispose { reg.remove() }
    }

    // ---------------- UI ----------------
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.drawable.logo2),
                            contentDescription = null,
                            modifier = Modifier.height(40.dp)
                        )
                        Text(text = "Groups", style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create group")
            }
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
                    items(filteredGroups, key = { it.id }) { chat ->
                        GroupListItem(
                            chatSummary = chat,
                            currentUserId = currentUserId,
                            userMap = userMap,
                            onClick = {
                                val id = chat.id
                                if (id.isNotBlank()) {
                                    val intent = Intent(
                                        context,
                                        com.example.cnnct.chat.view.ChatActivity::class.java
                                    )
                                    intent.putExtra("chatId", id)
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

    if (showCreate) {
        CreateGroupSheet(
            onDismiss = { showCreate = false },
            onCreatedOpen = { chatId ->
                showCreate = false
                val intent = Intent(
                    context,
                    com.example.cnnct.chat.view.ChatActivity::class.java
                ).putExtra("chatId", chatId)
                context.startActivity(intent)
            }
        )
    }
}

/* ---------------- Row item with avatar + ticks ---------------- */

@Composable
private fun GroupListItem(
    chatSummary: ChatSummary,
    currentUserId: String,
    userMap: Map<String, String>,
    onClick: () -> Unit
) {
    val chatName = chatSummary.groupName ?: "Group"
    val groupPhotoUrl: String? = chatSummary.groupPhotoUrl

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
            UserAvatar(
                photoUrl = groupPhotoUrl,
                size = 44.dp,
                contentDescription = "Group avatar",
                fallbackRes = R.drawable.defaultgpp
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hasText = chatSummary.lastMessageText.isNotBlank()
                    val rawText = if (hasText) chatSummary.lastMessageText else "No messages yet"

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

                    if (hasText && chatSummary.lastMessageSenderId == currentUserId) {
                        val effectiveStatus = chatSummary.lastMessageStatus
                            ?: if (chatSummary.lastMessageIsRead) "read" else "delivered"

                        val (ticks, color) = when (effectiveStatus) {
                            "read"      -> "✓✓" to Color(0xFF34B7F1)
                            "delivered" -> "✓✓" to MaterialTheme.colorScheme.onSurfaceVariant
                            "sent"      -> "✓"  to MaterialTheme.colorScheme.onSurfaceVariant
                            else        -> null  to MaterialTheme.colorScheme.onSurfaceVariant
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

/* ---------------- Create Group Sheet (with cropper + visible buttons) ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGroupSheet(
    onDismiss: () -> Unit,
    onCreatedOpen: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val me = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val context = LocalContext.current

    var groupName by remember { mutableStateOf("") }
    var groupDesc by remember { mutableStateOf("") }

    // Icon: pick → crop → preview → upload
    var iconUri by remember { mutableStateOf<Uri?>(null) }

    // Cropper launcher
    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            iconUri = result.uriContent
        } else {
            Toast.makeText(context, "Crop canceled", Toast.LENGTH_SHORT).show()
        }
    }

    // Picker that immediately launches the cropper
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { picked: Uri? ->
        picked ?: return@rememberLauncherForActivityResult
        cropLauncher.launch(
            CropImageContractOptions(
                uri = picked,
                cropImageOptions = CropImageOptions(
                    cropShape = CropImageView.CropShape.RECTANGLE,
                    aspectRatioX = 1,
                    aspectRatioY = 1,
                    fixAspectRatio = true,
                    guidelines = CropImageView.Guidelines.ON_TOUCH,
                    outputCompressFormat = android.graphics.Bitmap.CompressFormat.JPEG,
                    outputCompressQuality = 92,
                    activityTitle = "Crop group icon",
                    cropMenuCropButtonTitle = "Done"
                )
            )
        )
    }

    // Candidates and selection
    var candidates by remember { mutableStateOf<List<SelectableUser>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var loadingPeers by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }

    // Load my private chat peers as defaults
    LaunchedEffect(Unit) {
        try {
            loadingPeers = true
            val peerIds = GroupController.fetchPrivateChatPeers(me)
            val users = GroupController.fetchUsersByIds(peerIds)
            candidates = users
                .map { SelectableUser(uid = it.uid, name = it.displayName, phone = it.phone) }
                .sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            Log.e("CreateGroupSheet", "peer load failed", e)
        } finally {
            loadingPeers = false
        }
    }

    // Global search (like Home)
    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.length < 2) return@LaunchedEffect
        searching = true
        try {
            val results = GroupController.globalSearchUsers(q, limit = 20)
            // merge into candidates list without losing existing selections
            val existingSel = candidates.associateBy { it.uid }
            val merged = LinkedHashMap<String, SelectableUser>()
            // keep current candidates first
            candidates.forEach { merged[it.uid] = it }
            // add/merge results
            for (u in results) {
                val old = existingSel[u.uid]
                merged[u.uid] = SelectableUser(
                    uid = u.uid,
                    name = u.displayName,
                    phone = u.phone,
                    selected = old?.selected == true
                )
            }
            candidates = merged.values.toList().sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            Log.e("CreateGroupSheet", "global search failed", e)
        } finally {
            searching = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text("Create Group", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            // Name
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Group name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            // Description
            OutlinedTextField(
                value = groupDesc,
                onValueChange = { groupDesc = it },
                label = { Text("Description (optional)") },
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            // Icon picker (choose → crop)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Group icon (optional)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                ) { Text("Choose") }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                val painter = if (iconUri != null) {
                    rememberAsyncImagePainter(model = iconUri)
                } else {
                    painterResource(R.drawable.defaultgpp)
                }
                Image(
                    painter = painter,
                    contentDescription = "Group icon",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Search bar (global)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search users by name or phone…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // Candidates list
            Text(
                text = "Members (tap to select)",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))

            val infoLine = when {
                loadingPeers -> "Loading your contacts…"
                searching -> "Searching…"
                candidates.isEmpty() -> "No candidates yet — try searching."
                else -> null
            }
            if (infoLine != null) {
                Text(infoLine, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                ) {
                    items(candidates, key = { it.uid }) { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val idx = candidates.indexOfFirst { it.uid == row.uid }
                                    if (idx >= 0) {
                                        val copy = candidates.toMutableList()
                                        copy[idx] = copy[idx].copy(selected = !copy[idx].selected)
                                        candidates = copy
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = row.selected,
                                onCheckedChange = {
                                    val idx = candidates.indexOfFirst { it.uid == row.uid }
                                    if (idx >= 0) {
                                        val copy = candidates.toMutableList()
                                        copy[idx] = copy[idx].copy(selected = it == true)
                                        candidates = copy
                                    }
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.bodyLarge)
                                row.phone?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        Divider()
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    enabled = !creating,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                ) { Text("Cancel") }

                Button(
                    onClick = {
                        if (groupName.isBlank()) return@Button
                        val selected = candidates.filter { it.selected }.map { it.uid }
                        scope.launch {
                            creating = true
                            try {
                                val chatId = GroupController.createGroup(
                                    me = me,
                                    name = groupName.trim(),
                                    description = groupDesc.trim().ifBlank { null },
                                    memberIds = selected,
                                    localIconUri = iconUri
                                )
                                onCreatedOpen(chatId)
                            } catch (e: Exception) {
                                android.util.Log.e("CreateGroupSheet", "createGroup failed", e)
                                Toast.makeText(
                                    context,
                                    "Create failed: ${e.message ?: "unknown error"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                creating = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = groupName.isNotBlank() && !creating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                    )
                ) { Text(if (creating) "Creating…" else "Create") }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/* ---------------- helpers ---------------- */

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
