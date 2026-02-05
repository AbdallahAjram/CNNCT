package com.abdallah.cnnct.groups.view

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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.abdallah.cnnct.common.view.UserAvatar
import com.abdallah.cnnct.groups.viewmodel.GroupsUiState
import com.abdallah.cnnct.groups.viewmodel.GroupsViewModel
import com.abdallah.cnnct.homepage.model.ChatSummary
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.abdallah.cnnct.R

/* ------------------------ Screen (Stateful Wrapper) ------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    onChatClick: (String) -> Unit,
    vm: GroupsViewModel = viewModel(factory = GroupsViewModel.Factory)
) {
    val state by vm.state.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    GroupScreenContent(
        state = state,
        onChatClick = onChatClick,
        onCreateClick = { showCreate = true }
    )

    if (showCreate) {
        CreateGroupSheet(
            onDismiss = { showCreate = false },
            onCreatedOpen = { chatId ->
                showCreate = false
                onChatClick(chatId)
            }
        )
    }
}

/* ------------------------ Content (Stateless) ------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreenContent(
    state: GroupsUiState,
    onChatClick: (String) -> Unit,
    onCreateClick: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

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
            androidx.compose.material3.FloatingActionButton(onClick = onCreateClick) {
                Icon(Icons.Default.Add, contentDescription = "Create group")
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

            if (state.loading) {
                 Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                     androidx.compose.material3.CircularProgressIndicator()
                 }
            } else {
                val filteredGroups = remember(searchQuery, state.groups) {
                    if (searchQuery.isBlank()) state.groups
                    else state.groups.filter { it.groupName?.contains(searchQuery, ignoreCase = true) == true }
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
                                currentUserId = state.currentUserId,
                                userMap = state.userMap,
                                onClick = {
                                    val id = chat.id
                                    if (id.isNotBlank()) {
                                        onChatClick(id)
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

private data class SelectableUser(
    val uid: String,
    val name: String,
    val phone: String? = null,
    var selected: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGroupSheet(
    onDismiss: () -> Unit,
    onCreatedOpen: (String) -> Unit,
    vm: com.abdallah.cnnct.groups.viewmodel.GroupCreateViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = com.abdallah.cnnct.groups.viewmodel.GroupCreateViewModel.Factory)
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // To handle one-time events (navigation / toast) based on state changes
    LaunchedEffect(state.createdChatId) {
        state.createdChatId?.let { chatId ->
            onCreatedOpen(chatId)
            vm.resetCreatedChatId()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { err ->
            Toast.makeText(context, "Error: $err", Toast.LENGTH_LONG).show()
            vm.clearError()
        }
    }

    var groupName by remember { mutableStateOf("") }
    var groupDesc by remember { mutableStateOf("") }
    var iconUri by remember { mutableStateOf<Uri?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Search debounce
    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) {
             kotlinx.coroutines.delay(500) // simple debounce
             vm.searchUsers(searchQuery)
        }
    }

    // Cropper
    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) iconUri = result.uriContent
        else Toast.makeText(context, "Crop canceled", Toast.LENGTH_SHORT).show()
    }
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { picked: Uri? ->
        picked ?: return@rememberLauncherForActivityResult
        cropLauncher.launch(
            CropImageContractOptions(
                uri = picked,
                cropImageOptions = CropImageOptions(
                    cropShape = CropImageView.CropShape.RECTANGLE,
                    aspectRatioX = 1, aspectRatioY = 1, fixAspectRatio = true,
                    guidelines = CropImageView.Guidelines.ON_TOUCH,
                    outputCompressFormat = android.graphics.Bitmap.CompressFormat.JPEG,
                    activityTitle = "Crop Group Icon"
                )
            )
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Create Group", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            // Name & Desc
            OutlinedTextField(
                value = groupName, onValueChange = { groupName = it },
                label = { Text("Group name") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = groupDesc, onValueChange = { groupDesc = it },
                label = { Text("Description (optional)") },
                singleLine = false, minLines = 2, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            // Icon
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Group icon (optional)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text("Choose")
                }
            }
            if (iconUri != null) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Image(
                        painter = rememberAsyncImagePainter(model = iconUri),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(100.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Search
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                placeholder = { Text("Search users…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            Text("Members", style = MaterialTheme.typography.titleMedium)
            
            val info = when {
                state.loadingPeers -> "Loading contacts…"
                state.searching -> "Searching…"
                state.candidates.isEmpty() -> "No users found."
                else -> null
            }
            if (info != null) {
                Text(info, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(state.candidates, key = { it.uid }) { user ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.toggleSelection(user.uid) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = user.selected,
                                onCheckedChange = { vm.toggleSelection(user.uid) }
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(user.name, style = MaterialTheme.typography.bodyLarge)
                                user.phone?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        Divider()
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), enabled = !state.creating) { Text("Cancel") }
                Button(
                    onClick = { vm.createGroup(groupName, groupDesc, iconUri) },
                    modifier = Modifier.weight(1f),
                    enabled = groupName.isNotBlank() && !state.creating
                ) {
                    Text(if (state.creating) "Creating…" else "Create")
                }
            }
            Spacer(Modifier.height(20.dp))
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
