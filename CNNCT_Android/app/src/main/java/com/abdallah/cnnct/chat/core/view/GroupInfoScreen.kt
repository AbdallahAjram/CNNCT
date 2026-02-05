package com.abdallah.cnnct.chat.view

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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.abdallah.cnnct.groups.viewmodel.GroupInfoUiState
import com.abdallah.cnnct.groups.viewmodel.GroupInfoViewModel
import com.abdallah.cnnct.settings.model.UserProfile
import kotlinx.coroutines.launch
import com.abdallah.cnnct.R

/* ------------------------ Screen (Stateful Wrapper) ------------------------ */

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
    val context = LocalContext.current

    // ========= UI State =========
    var editingInfo by remember { mutableStateOf(false) }
    var showAddMembers by remember { mutableStateOf(false) }
    var confirmRemoveFor by remember { mutableStateOf<String?>(null) }
    var confirmMakeAdminFor by remember { mutableStateOf<String?>(null) }
    var confirmRevokeAdminFor by remember { mutableStateOf<String?>(null) }

    // ========= Add-members data =========
    var searchQuery by remember { mutableStateOf("") }
    var recentContacts by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var selectedNewMembers by remember { mutableStateOf(setOf<String>()) }

    // Logic: Load contacts for AddMembers
    LaunchedEffect(showAddMembers) {
        if (showAddMembers) {
            recentContacts = vm.loadRecentContacts()
            searchQuery = ""
            searchResults = emptyList()
            selectedNewMembers = emptySet()
        }
    }
    // Logic: Search for AddMembers
    LaunchedEffect(searchQuery) {
        if (showAddMembers) {
            searchResults = if (searchQuery.isBlank()) emptyList()
            else vm.searchUsers(searchQuery, limit = 20)
        }
    }

    val isAdmin = state.group?.adminIds?.contains(state.me) == true

    // Image Picker/Cropper
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
        val options = CropImageOptions(
            cropShape = CropImageView.CropShape.OVAL,
            aspectRatioX = 1, aspectRatioY = 1, fixAspectRatio = true,
            outputCompressFormat = Bitmap.CompressFormat.JPEG,
            activityTitle = "Adjust",
            cropMenuCropButtonTitle = "Done"
        )
        cropLauncher.launch(CropImageContractOptions(uri = picked, cropImageOptions = options))
    }

    // Render Content
    GroupInfoScreenContent(
        state = state,
        isAdmin = isAdmin,
        snackbarHostState = snackbarHostState,
        onBack = { activity?.finish() },
        onAddMembers = { showAddMembers = true },
        onEditGroup = { editingInfo = true },
        onPickPhoto = {
            pickGroupPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onMemberClick = { uid ->
            context.startActivity(
                android.content.Intent(context, com.abdallah.cnnct.chat.view.PersonInfoActivity::class.java)
                    .putExtra("uid", uid)
            )
        },
        onRemoveMember = { confirmRemoveFor = it },
        onMakeAdmin = { confirmMakeAdminFor = it },
        onRevokeAdmin = { confirmRevokeAdminFor = it },
        onLeaveGroup = { scope.launch { vm.leaveGroup { activity?.finish() } } }
    )

    // Render Dialogs
    if (editingInfo) {
        val g = state.group!!
        EditGroupDialog(
            initialName = g.groupName,
            initialDesc = g.groupDescription.orEmpty(),
            onDismiss = { editingInfo = false },
            onSave = { n, d ->
                editingInfo = false
                scope.launch { vm.updateGroupNameAndDescription(n, d) }
            }
        )
    }

    if (showAddMembers) {
        AddMembersDialog(
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            recentContacts = recentContacts,
            searchResults = searchResults,
            selectedUids = selectedNewMembers,
            onToggleSelection = { uid ->
                selectedNewMembers = if (selectedNewMembers.contains(uid)) selectedNewMembers - uid else selectedNewMembers + uid
            },
            onDismiss = { showAddMembers = false },
            onConfirm = {
                val list = selectedNewMembers.toList()
                showAddMembers = false
                scope.launch { vm.addMembers(list) }
            }
        )
    }

    if (confirmRemoveFor != null) {
        ConfirmDialog(
            title = "Remove member?", body = "They will be removed from the group.",
            onConfirm = {
                val uid = confirmRemoveFor!!
                confirmRemoveFor = null
                scope.launch { vm.removeMember(uid) }
            },
            onDismiss = { confirmRemoveFor = null }
        )
    }

    if (confirmMakeAdminFor != null) {
        ConfirmDialog(
            title = "Make admin?", body = "They’ll be able to add/remove members and edit the group.",
            onConfirm = {
                val uid = confirmMakeAdminFor!!
                confirmMakeAdminFor = null
                scope.launch { vm.makeAdmin(uid) }
            },
            onDismiss = { confirmMakeAdminFor = null }
        )
    }

    if (confirmRevokeAdminFor != null) {
        ConfirmDialog(
            title = "Revoke admin?", body = "They’ll lose admin privileges.",
            onConfirm = {
                val uid = confirmRevokeAdminFor!!
                confirmRevokeAdminFor = null
                scope.launch { vm.revokeAdmin(uid) }
            },
            onDismiss = { confirmRevokeAdminFor = null }
        )
    }
}

/* ------------------------ Content (Stateless) ------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreenContent(
    state: GroupInfoUiState,
    isAdmin: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAddMembers: () -> Unit,
    onEditGroup: () -> Unit,
    onPickPhoto: () -> Unit,
    onMemberClick: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onMakeAdmin: (String) -> Unit,
    onRevokeAdmin: (String) -> Unit,
    onLeaveGroup: () -> Unit
) {
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Group info") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (isAdmin) {
                        TextButton(onClick = onAddMembers) { Text("Add members") }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { pad ->
        when {
            state.loading -> LoadingBox(Modifier.padding(pad))
            state.error != null -> ErrorBox(state.error!!, Modifier.padding(pad))
            else -> {
                val g = state.group!!
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(pad)
                        .padding(16.dp)
                ) {
                    // Header
                    GroupHeader(g, isAdmin, onPickPhoto, onEditGroup)

                    Spacer(Modifier.height(20.dp))
                    Text("Members", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    state.members.forEach { u ->
                        MemberRow(
                            user = u,
                            me = state.me,
                            isAdmin = isAdmin,
                            isMemberAdmin = g.adminIds.contains(u.uid),
                            onMemberClick = { onMemberClick(u.uid) },
                            onRemove = { onRemoveMember(u.uid) },
                            onMakeAdmin = { onMakeAdmin(u.uid) },
                            onRevokeAdmin = { onRevokeAdmin(u.uid) },
                            onLeave = onLeaveGroup
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    if (!isAdmin) {
                        DangerCard(label = "Leave group", onClick = onLeaveGroup)
                    }
                }
            }
        }
    }
}

/* ---------------- Sub-components ---------------- */

@Composable
private fun GroupHeader(
    g: com.abdallah.cnnct.chat.model.GroupInfo,
    isAdmin: Boolean,
    onPickPhoto: () -> Unit,
    onEditGroup: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val groupUrl = g.groupPhotoUrl
        Box(
            modifier = Modifier
                .size(88.dp)
                .clickable(enabled = isAdmin, onClick = onPickPhoto),
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
                    IconButton(onClick = onEditGroup) {
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
}

@Composable
private fun MemberRow(
    user: UserProfile,
    me: String,
    isAdmin: Boolean,
    isMemberAdmin: Boolean,
    onMemberClick: () -> Unit,
    onRemove: () -> Unit,
    onMakeAdmin: () -> Unit,
    onRevokeAdmin: () -> Unit,
    onLeave: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val you = user.uid == me

    ListItem(
        headlineContent = {
            val suffix = when {
                you && isMemberAdmin -> " (you • admin)"
                you -> " (you)"
                isMemberAdmin -> " (admin)"
                else -> ""
            }
            Text(user.displayName + suffix)
        },
        supportingContent = {
            Text(user.phoneNumber ?: "")
        },
        leadingContent = {
            if (user.photoUrl.isNullOrBlank()) {
                Image(
                    painter = painterResource(R.drawable.defaultpp),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                SmallAvatar(user.photoUrl)
            }
        },
        trailingContent = {
            if (isAdmin && !you) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            onClick = { menuOpen = false; onRemove() }
                        )
                        if (isMemberAdmin) {
                            DropdownMenuItem(
                                text = { Text("Revoke admin") },
                                onClick = { menuOpen = false; onRevokeAdmin() }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Make admin") },
                                onClick = { menuOpen = false; onMakeAdmin() }
                            )
                        }
                    }
                }
            } else if (you && !isAdmin) { 
                // Allow non-admin leave from row (optional logic match)
                TextButton(onClick = onLeave) { Text("Leave") }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onMemberClick() }
    )
    Divider()
}

/* ---------------- Dialogs ---------------- */

@Composable
fun EditGroupDialog(
    initialName: String,
    initialDesc: String,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit
) {
    var nameText by remember { mutableStateOf(initialName) }
    var descText by remember { mutableStateOf(initialDesc) }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                onClick = { onSave(nameText.trim(), descText.trim().ifBlank { null }) }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AddMembersDialog(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    recentContacts: List<UserProfile>,
    searchResults: List<UserProfile>,
    selectedUids: Set<String>,
    onToggleSelection: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add members") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search users by name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                // ... (simplified for brevity, reused list item component would be better)
                if (recentContacts.isNotEmpty()) {
                     Text("Recent chats", style = MaterialTheme.typography.labelLarge)
                     UserSelectionList(recentContacts, selectedUids, onToggleSelection)
                }
                if (searchResults.isNotEmpty()) {
                     Spacer(Modifier.height(12.dp))
                     Text("Search results", style = MaterialTheme.typography.labelLarge)
                     UserSelectionList(searchResults, selectedUids, onToggleSelection)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selectedUids.isNotEmpty(), onClick = onConfirm) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun UserSelectionList(
    users: List<UserProfile>,
    selectedUids: Set<String>,
    onToggleSelection: (String) -> Unit
) {
    Column {
        users.forEach { u ->
            val selected = selectedUids.contains(u.uid)
            ListItem(
                headlineContent = { Text(u.displayName) },
                supportingContent = { Text(u.phoneNumber ?: "") },
                leadingContent = {
                    if (u.photoUrl.isNullOrBlank()) {
                         Image(painter = painterResource(R.drawable.defaultpp), contentDescription = null, modifier = Modifier.size(36.dp))
                    } else {
                         SmallAvatar(u.photoUrl)
                    }
                },
                trailingContent = {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelection(u.uid) })
                },
                modifier = Modifier.fillMaxWidth().clickable { onToggleSelection(u.uid) }
            )
            Divider()
        }
    }
}

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
