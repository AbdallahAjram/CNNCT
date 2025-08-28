package com.example.cnnct.homepage.view

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.cnnct.R
import com.example.cnnct.auth.controller.LoginActivity
import com.example.cnnct.chat.view.ChatActivity
import com.example.cnnct.homepage.model.ChatSummary
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import com.example.cnnct.homepage.controller.HomePController


class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HomeScreen(onLogout = {
                FirebaseAuth.getInstance().signOut()
                Log.d("HomeActivity", "User logged out")
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            })
        }
    }
}

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
                    context.startActivity(
                        Intent(context, com.example.cnnct.homepage.view.HomeActivity::class.java)
                    )
                }
            }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Group, contentDescription = "Groups") },
            label = { Text("Groups") },
            selected = currentScreen == "groups",
            onClick = {
                if (currentScreen != "groups") {
                    context.startActivity(
                        Intent(context, com.example.cnnct.groups.view.GroupActivity::class.java)
                    )
                }
            }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Call, contentDescription = "Calls") },
            label = { Text("Calls") },
            selected = currentScreen == "calls",
            onClick = {
                if (currentScreen != "calls") {
                    context.startActivity(
                        Intent(context, com.example.cnnct.calls.view.CallsActivity::class.java)
                    )
                }
            }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            selected = currentScreen == "settings",
            onClick = {
                if (currentScreen != "settings") {
                    context.startActivity(
                        Intent(context, com.example.cnnct.settings.view.SettingsActivity::class.java)
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    var chatSummaries by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    var userMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var userPhotoMap by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf("") }

    // Presence state
    var onlineMap by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var presenceRegs by remember { mutableStateOf<List<ListenerRegistration>>(emptyList()) }

    var showNewChatSheet by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var menuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // ==== Real-time chats (hybrid: previews + chat metadata) ====
    DisposableEffect(currentUserId) {
        if (currentUserId.isBlank()) return@DisposableEffect onDispose {}
        val reg: ListenerRegistration =
            db.collection("userChats")
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
                                    Log.e("HomeScreen", "merge chat ${meta.id}", e); null
                                }
                            }

                            val sorted = merged.sortedByDescending {
                                it.lastMessageTimestamp?.toDate()?.time
                                    ?: it.updatedAt?.toDate()?.time
                                    ?: Long.MIN_VALUE
                            }
                            chatSummaries = sorted
                        } catch (e: Exception) {
                            Log.e("HomeScreen", "merge previews + chats failed", e)
                            chatSummaries = emptyList()
                        }
                    }
                }
        onDispose { reg.remove() }
    }

    // Build user names + photos + presence listeners when chats change
    LaunchedEffect(chatSummaries) {
        val targets = chatSummaries
            .flatMap { it.members + (it.lastMessageSenderId ?: "") }
            .filter { it.isNotBlank() && it != currentUserId }
            .distinct()

        // Names & Photos (one-shot)
        val nameMap = mutableMapOf<String, String>()
        val photoMap = mutableMapOf<String, String?>()

        for (chunk in targets.chunked(10)) {
            try {
                val snap = db.collection("users")
                    .whereIn(FieldPath.documentId(), chunk)
                    .get()
                    .await()

                snap.documents.forEach { doc ->
                    val uid = doc.id
                    nameMap[uid] = doc.getString("displayName") ?: "Unknown"
                    photoMap[uid] = doc.getString("photoUrl") // nullable
                }
            } catch (e: Exception) {
                Log.e("HomeScreen", "userMap/photoMap chunk failed", e)
            }
        }
        userMap = nameMap
        userPhotoMap = photoMap

        // Presence (real-time)
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
                com.example.cnnct.homepage.controller.HomePController
                    .promoteIncomingLastMessagesToDelivered(currentUserId, maxPerRun = 25)
            } catch (e: Exception) {
                Log.e("HomeScreen", "deliver-poll pass failed", e)
            }
            kotlinx.coroutines.delay(pollSeconds * 1000L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Image(
                        painter = painterResource(R.drawable.logo2),
                        contentDescription = "CNNCT Logo",
                        modifier = Modifier
                            .height(40.dp)
                            .offset(y = 4.dp)
                    )
                },
                actions = {
                    IconButton(onClick = {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }

                    // ⋮ menu with Settings + Logout
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    context.startActivity(
                                        Intent(context, com.example.cnnct.settings.view.SettingsActivity::class.java)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Logout") },
                                onClick = {
                                    menuExpanded = false
                                    onLogout()
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = { BottomNavigationBar(currentScreen = "chats") },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewChatSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "New Chat")
            }
        }
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
                placeholder = { Text("Search chats...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
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

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { /* TODO */ },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Archived Chats...") }

            Spacer(Modifier.height(12.dp))

            val filtered = remember(chatSummaries, searchQuery, userMap, currentUserId) {
                if (searchQuery.isBlank()) chatSummaries else {
                    chatSummaries.filter { chat ->
                        val name = when (chat.type) {
                            "group" -> chat.groupName ?: ""
                            "private" -> {
                                val other = chat.members.firstOrNull { it != currentUserId }
                                userMap[other] ?: ""
                            }
                            else -> ""
                        }
                        name.contains(searchQuery, ignoreCase = true)
                    }
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No chats found", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn {
                    items(filtered, key = { it.id }) { chat ->
                        val photoUrlForRow: String? = when (chat.type) {
                            "private" -> {
                                val other = chat.members.firstOrNull { it != currentUserId }
                                userPhotoMap[other]   // null ⇒ UserAvatar falls back to drawable
                            }
                            "group" -> {
                                // If you later add `groupPhotoUrl` to ChatSummary, use it:
                                // chat.groupPhotoUrl
                                null
                            }
                            else -> null
                        }

                        ChatListItem(
                            chatSummary = chat,
                            currentUserId = currentUserId,
                            userMap = userMap,
                            onClick = {
                                val intent = Intent(context, ChatActivity::class.java)
                                    .putExtra("chatId", chat.id)
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
    }

    // ========= NEW CHAT BOTTOM SHEET (FAB) =========
    if (showNewChatSheet) {
        data class Person(val uid: String, val name: String, val phoneDigits: String?)

        fun maskPhone(d: String?): String? {
            if (d.isNullOrBlank()) return null
            val digits = d.filter { it.isDigit() }.take(8)
            return when {
                digits.length >= 3 -> digits.substring(0, 2) + "-***" + digits.takeLast(3)
                digits.length == 2 -> digits + "-***"
                else -> digits
            }
        }

        suspend fun searchPeople(q: String): List<Person> {
            val results = mutableMapOf<String, Person>() // uid -> person

            val qTrim = q.trim()
            val digits = qTrim.filter { it.isDigit() }

            // 1) Display name prefix search
            try {
                val snap = db.collection("users")
                    .whereGreaterThanOrEqualTo("displayName", qTrim)
                    .whereLessThanOrEqualTo("displayName", qTrim + '\uf8ff')
                    .limit(20)
                    .get()
                    .await()

                snap.documents.forEach { d ->
                    val uid = d.id
                    if (uid == currentUserId) return@forEach
                    val name = d.getString("displayName") ?: return@forEach
                    val phone = d.getString("phoneNumber")
                    results[uid] = Person(uid, name, phone)
                }
            } catch (e: Exception) {
                Log.e("HomeScreen", "name search failed", e)
            }

            // 2) Phone prefix search on users.phoneNumber (string range)
            if (digits.length >= 2) {
                try {
                    val snap = db.collection("users")
                        .whereGreaterThanOrEqualTo("phoneNumber", digits)
                        .whereLessThanOrEqualTo("phoneNumber", digits + '\uf8ff')
                        .limit(20)
                        .get()
                        .await()

                    snap.documents.forEach { d ->
                        val uid = d.id
                        if (uid == currentUserId) return@forEach
                        val name = d.getString("displayName") ?: "Unknown"
                        val phone = d.getString("phoneNumber")
                        results[uid] = results[uid] ?: Person(uid, name, phone)
                    }
                } catch (e: Exception) {
                    Log.e("HomeScreen", "phone prefix search failed", e)
                }
            }

            // 3) Exact phone lookup via /phones/{digits} → uid
            if (digits.length == 8) {
                try {
                    val pdoc = db.collection("phones").document(digits).get().await()
                    val uid = pdoc.getString("uid")
                    if (!uid.isNullOrBlank() && uid != currentUserId) {
                        val udoc = db.collection("users").document(uid).get().await()
                        if (udoc.exists()) {
                            val name = udoc.getString("displayName") ?: "Unknown"
                            val phone = udoc.getString("phoneNumber")
                            results[uid] = Person(uid, name, phone ?: digits)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HomeScreen", "exact phone map failed", e)
                }
            }

            return results.values.sortedBy { it.name.lowercase() }
        }

        ModalBottomSheet(onDismissRequest = { showNewChatSheet = false }) {
            var query by remember { mutableStateOf("") }
            var people by remember { mutableStateOf<List<Person>>(emptyList()) }
            val cs = rememberCoroutineScope()

            Column(Modifier.padding(16.dp)) {
                Text("Start new chat", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { q ->
                        query = q
                        if (q.isBlank()) {
                            people = emptyList()
                        } else {
                            cs.launch(Dispatchers.IO) {
                                val res = searchPeople(q)
                                withContext(Dispatchers.Main) { people = res }
                            }
                        }
                    },
                    placeholder = { Text("Search by display name or phone (03-123456 or 03123456)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                LazyColumn {
                    items(people) { person ->
                        ListItem(
                            headlineContent = { Text(person.name) },
                            supportingContent = {
                                maskPhone(person.phoneDigits)?.let { Text(it) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    HomePController.createOrGetPrivateChat(person.uid) { chatId ->
                                        if (chatId != null) {
                                            val intent = Intent(context, ChatActivity::class.java)
                                                .putExtra("chatId", chatId)
                                            context.startActivity(intent)
                                            showNewChatSheet = false
                                        } else {
                                            Log.e("HomeScreen", "Failed to open/create chat with ${person.uid}")
                                        }
                                    }
                                }
                                .padding(vertical = 8.dp)
                        )
                        Divider()
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
