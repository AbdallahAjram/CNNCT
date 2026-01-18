package com.example.cnnct.chat.view

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.example.cnnct.R
import com.example.cnnct.chat.controller.ChatInfoController
import com.example.cnnct.chat.controller.openPeerProfile
import com.example.cnnct.chat.model.GroupInfo
import com.example.cnnct.chat.model.UserProfile
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    chatId: String,
    nav: NavController,
    vm: GroupInfoViewModel = viewModel(factory = GroupInfoViewModel.factory(chatId))
) {
    val state by vm.state.collectAsState()
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // ========= Admin edit state (name/desc) =========
    var editingInfo by remember { mutableStateOf(false) }
    var nameText by remember { mutableStateOf("") }
    var descText by remember { mutableStateOf("") }

    // ========= Add-members UI state =========
    var showAddMembers by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var recentContacts by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var selectedNewMembers by remember { mutableStateOf(setOf<String>()) }

    // ========= Per-member overflow menus / confirms =========
    var menuForUserId by remember { mutableStateOf<String?>(null) }
    var confirmRemoveFor by remember { mutableStateOf<String?>(null) }
    var confirmMakeAdminFor by remember { mutableStateOf<String?>(null) }
    var confirmRevokeAdminFor by remember { mutableStateOf<String?>(null) }

    // ========= Image cropper for group photo (admins only) =========
    val context = LocalContext.current

    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        scope.launch {
            if (result.isSuccessful) {
                val croppedUri = result.uriContent ?: return@launch
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar("Uploading photo…")
                val ok = vm.updateGroupPhoto(croppedUri)
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(if (ok) "Group photo updated" else "Upload failed")
            } else {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar("Crop canceled")
            }
        }
    }
    val pickGroupPhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { picked: Uri? ->
        picked ?: return@rememberLauncherForActivityResult
        // Send to cropper
        val options = CropImageOptions(
            cropShape = CropImageView.CropShape.OVAL, // rounded mask
            aspectRatioX = 1,
            aspectRatioY = 1,
            fixAspectRatio = true,
            guidelines = CropImageView.Guidelines.OFF,
            outputCompressFormat = Bitmap.CompressFormat.JPEG,
            outputCompressQuality = 90,
            activityTitle = "Adjust",
            toolbarColor = androidx.core.content.ContextCompat.getColor(context, R.color.black),
            toolbarTitleColor = androidx.core.content.ContextCompat.getColor(context, android.R.color.white),
            cropMenuCropButtonTitle = "Done"
        )
        cropLauncher.launch(
            CropImageContractOptions(
                uri = picked,
                cropImageOptions = options
            )
        )
    }



    // Load “recent contacts” when we open the picker
    LaunchedEffect(showAddMembers) {
        if (showAddMembers) {
            recentContacts = vm.loadRecentContacts()
            searchQuery = ""
            searchResults = emptyList()
            selectedNewMembers = emptySet()
        }
    }

    // Global search by displayName
    LaunchedEffect(searchQuery) {
        if (showAddMembers) {
            searchResults = if (searchQuery.isBlank()) emptyList()
            else vm.searchUsers(searchQuery, limit = 20)
        }
    }

    val isAdmin = state.group?.adminIds?.contains(state.me) == true

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Group info") },
                navigationIcon = { BackButton { activity?.finish() } },
                actions = {
                    if (isAdmin) {
                        TextButton(onClick = { showAddMembers = true }) { Text("Add members") }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { pad ->
        when {
            state.loading -> LoadingBox(pad)
            state.error != null -> ErrorBox(state.error!!, pad)
            else -> {
                val g = state.group!!
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(pad)
                        .padding(16.dp)
                ) {
                    // ========= Header: Photo + Name/Desc with edit pencil for admins =========
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val groupUrl = g.groupPhotoUrl
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clickable(enabled = isAdmin) {
                                    if (isAdmin) {
                                        pickGroupPhoto.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (groupUrl.isNullOrBlank()) {
                                Image(
                                    painter = painterResource(R.drawable.defaultgpp),
                                    contentDescription = null,
                                    modifier = Modifier.size(88.dp)
                                )
                            } else {
                                AsyncImage(
                                    model = groupUrl,
                                    contentDescription = "Group photo",
                                    modifier = Modifier.size(88.dp)
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    g.groupName,
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isAdmin) {
                                    IconButton(
                                        onClick = {
                                            editingInfo = true
                                            nameText = g.groupName
                                            descText = g.groupDescription.orEmpty()
                                        }
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit group")
                                    }
                                }
                            }
                            g.groupDescription?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${g.members.size} members",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text("Members", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    state.members.forEach { u ->
                        val you = u.uid == state.me
                        val isMemberAdmin = g.adminIds.contains(u.uid)

                        ListItem(
                            headlineContent = {
                                val suffix = when {
                                    you && isMemberAdmin -> " (you • admin)"
                                    you -> " (you)"
                                    isMemberAdmin -> " (admin)"
                                    else -> ""
                                }
                                Text(u.displayName + suffix)
                            },
                            supportingContent = {
                                Text(u.phoneNumber ?: "")
                            },
                            leadingContent = {
                                if (u.photoUrl.isNullOrBlank()) {
                                    Image(
                                        painter = painterResource(R.drawable.defaultpp),
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp)
                                    )
                                } else {
                                    SmallAvatar(u.photoUrl)
                                }
                            },
                            trailingContent = {
                                // Per-member admin menu
                                if (isAdmin) {
                                    Box {
                                        IconButton(onClick = { menuForUserId = u.uid }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                                        }
                                        DropdownMenu(
                                            expanded = menuForUserId == u.uid,
                                            onDismissRequest = { menuForUserId = null }
                                        ) {
                                            if (!you) {
                                                DropdownMenuItem(
                                                    text = { Text("Remove") },
                                                    onClick = {
                                                        menuForUserId = null
                                                        confirmRemoveFor = u.uid
                                                    }
                                                )
                                                if (isMemberAdmin) {
                                                    DropdownMenuItem(
                                                        text = { Text("Revoke admin") },
                                                        onClick = {
                                                            menuForUserId = null
                                                            confirmRevokeAdminFor = u.uid
                                                        }
                                                    )
                                                } else {
                                                    DropdownMenuItem(
                                                        text = { Text("Make admin") },
                                                        onClick = {
                                                            menuForUserId = null
                                                            confirmMakeAdminFor = u.uid
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else if (you) {
                                    // Non-admins can leave from member row
                                    TextButton(onClick = { scope.launch { vm.leaveGroup { activity?.finish() } } }) {
                                        Text("Leave")
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { nav.openPeerProfile(u.uid) }
                        )
                        Divider()
                    }

                    Spacer(Modifier.height(24.dp))
                    if (!isAdmin) {
                        DangerCard(label = "Leave group") {
                            vm.leaveGroup { activity?.finish() }
                        }
                    }
                }
            }
        }
    }

    // ======= Dialogs =======

    // Generic confirm
    @Composable
    fun ConfirmDialog(
        title: String,
        body: String,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    }

    confirmRemoveFor?.let { uid ->
        ConfirmDialog(
            title = "Remove member?",
            body = "They will be removed from the group.",
            onConfirm = {
                confirmRemoveFor = null
                scope.launch { vm.removeMember(uid) }
            },
            onDismiss = { confirmRemoveFor = null }
        )
    }

    confirmMakeAdminFor?.let { uid ->
        ConfirmDialog(
            title = "Make admin?",
            body = "They’ll be able to add/remove members and edit the group.",
            onConfirm = {
                confirmMakeAdminFor = null
                scope.launch { vm.makeAdmin(uid) }
            },
            onDismiss = { confirmMakeAdminFor = null }
        )
    }

    confirmRevokeAdminFor?.let { uid ->
        ConfirmDialog(
            title = "Revoke admin?",
            body = "They’ll lose admin privileges.",
            onConfirm = {
                confirmRevokeAdminFor = null
                scope.launch { vm.revokeAdmin(uid) }
            },
            onDismiss = { confirmRevokeAdminFor = null }
        )
    }

    // Add members dialog
    if (showAddMembers) {
        AlertDialog(
            onDismissRequest = { showAddMembers = false },
            title = { Text("Add members") },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search users by name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    if (recentContacts.isNotEmpty()) {
                        Text("Recent chats", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        recentContacts.forEach { u ->
                            val selected = selectedNewMembers.contains(u.uid)
                            ListItem(
                                headlineContent = { Text(u.displayName) },
                                supportingContent = { Text(u.phoneNumber ?: "") },
                                leadingContent = {
                                    if (u.photoUrl.isNullOrBlank()) {
                                        Image(
                                            painter = painterResource(R.drawable.defaultpp),
                                            contentDescription = null,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    } else {
                                        SmallAvatar(u.photoUrl)
                                    }
                                },
                                trailingContent = {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = {
                                            selectedNewMembers =
                                                if (selected) selectedNewMembers - u.uid else selectedNewMembers + u.uid
                                        }
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedNewMembers =
                                            if (selected) selectedNewMembers - u.uid else selectedNewMembers + u.uid
                                    }
                            )
                            Divider()
                        }
                    }
                    if (searchResults.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Search results", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        searchResults.forEach { u ->
                            val selected = selectedNewMembers.contains(u.uid)
                            ListItem(
                                headlineContent = { Text(u.displayName) },
                                supportingContent = { Text(u.phoneNumber ?: "") },
                                leadingContent = {
                                    if (u.photoUrl.isNullOrBlank()) {
                                        Image(
                                            painter = painterResource(R.drawable.defaultpp),
                                            contentDescription = null,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    } else {
                                        SmallAvatar(u.photoUrl)
                                    }
                                },
                                trailingContent = {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = {
                                            selectedNewMembers =
                                                if (selected) selectedNewMembers - u.uid else selectedNewMembers + u.uid
                                        }
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedNewMembers =
                                            if (selected) selectedNewMembers - u.uid else selectedNewMembers + u.uid
                                    }
                            )
                            Divider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedNewMembers.isNotEmpty(),
                    onClick = {
                        val toAdd = selectedNewMembers.toList()
                        showAddMembers = false
                        scope.launch { vm.addMembers(toAdd) }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddMembers = false }) { Text("Cancel") }
            }
        )
    }

    // Edit group info dialog (admins)
    if (editingInfo) {
        AlertDialog(
            onDismissRequest = { editingInfo = false },
            title = { Text("Edit group") },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        label = { Text("Group name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = descText,
                        onValueChange = { descText = it },
                        label = { Text("Description (optional)") },
                        singleLine = false,
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nameText.trim().isNotEmpty(),
                    onClick = {
                        val n = nameText.trim()
                        val d = descText.trim().ifBlank { null }
                        editingInfo = false
                        scope.launch { vm.updateGroupNameAndDescription(n, d) }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingInfo = false }) { Text("Cancel") }
            }
        )
    }
}


@Composable
fun LoadingBox(paddingValues: PaddingValues = PaddingValues(0.dp)) {
    Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorBox(msg: String, paddingValues: PaddingValues = PaddingValues(0.dp)) {
    Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
        Text(msg, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun SmallAvatar(url: String) {
    AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(40.dp))
}


/* =================== ViewModel =================== */

class GroupInfoViewModel(
    private val chatId: String,
    private val ctrl: ChatInfoController
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val group: GroupInfo? = null,
        val members: List<UserProfile> = emptyList(),
        val error: String? = null,
        val me: String = ""
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        refreshAll()
    }

    private fun refreshAll() = viewModelScope.launch {
        runCatching { ctrl.getGroup(chatId) }
            .onSuccess { g ->
                val members = runCatching { ctrl.getUsers(g.members) }.getOrDefault(emptyList())
                _state.value = UiState(
                    loading = false,
                    group = g,
                    members = members,
                    me = ctrl.me()
                )
            }
            .onFailure { _state.value = UiState(loading = false, error = it.message) }
    }

    fun leaveGroup(onLeft: () -> Unit) = viewModelScope.launch {
        runCatching { ctrl.leaveGroup(chatId) }
            .onSuccess { onLeft() }
    }

    fun removeMember(uid: String) = viewModelScope.launch {
        runCatching { ctrl.removeMembers(chatId, listOf(uid)) }
            .onSuccess { refreshAll() }
    }

    suspend fun addMembers(uids: List<String>) {
        runCatching { ctrl.addMembers(chatId, uids) }
            .onSuccess { refreshAll() }
    }

    suspend fun loadRecentContacts(): List<UserProfile> {
        val currentMembers = _state.value.group?.members?.toSet().orEmpty()
        return runCatching { ctrl.getRecentPrivateChatPeers() }
            .getOrDefault(emptyList())
            .filterNot { it.uid in currentMembers }
    }

    suspend fun searchUsers(query: String, limit: Int): List<UserProfile> {
        val currentMembers = _state.value.group?.members?.toSet().orEmpty()
        return runCatching { ctrl.searchUsers(query, limit) }
            .getOrDefault(emptyList())
            .filterNot { it.uid in currentMembers }
    }

    fun makeAdmin(uid: String) = viewModelScope.launch {
        runCatching { ctrl.makeAdmin(chatId, uid) }.onSuccess { refreshAll() }
    }

    fun revokeAdmin(uid: String) = viewModelScope.launch {
        runCatching { ctrl.revokeAdmin(chatId, uid) }.onSuccess { refreshAll() }
    }

    fun updateGroupNameAndDescription(name: String, description: String?) = viewModelScope.launch {
        runCatching { ctrl.updateGroupNameAndDescription(chatId, name, description) }
            .onSuccess { refreshAll() }
    }

    suspend fun updateGroupPhoto(imageUri: Uri): Boolean {
        return runCatching { ctrl.updateGroupPhoto(chatId, imageUri) }
            .onSuccess { refreshAll() }
            .isSuccess
    }

    companion object {
        fun factory(chatId: String) = viewModelFactory {
            initializer { GroupInfoViewModel(chatId, ChatInfoController()) }
        }
    }
}
