package com.abdallah.cnnct.settings.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abdallah.cnnct.common.view.UserAvatar
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun BlockedSettingsScreen(
    firestore: FirebaseFirestore,
    auth: FirebaseAuth,
    contentPadding: PaddingValues = PaddingValues()
) {
    val scope = rememberCoroutineScope()
    val me = auth.currentUser?.uid ?: return
    var blockedUsers by remember { mutableStateOf<List<Triple<String, String, String?>>>(emptyList()) } // (uid, name, photo)
    var loading by remember { mutableStateOf(true) }

    // For confirm dialog
    var confirmDialogVisible by remember { mutableStateOf(false) }
    var pendingUnblockId by remember { mutableStateOf<String?>(null) }
    var pendingUnblockName by remember { mutableStateOf<String?>(null) }

    // Load all chats where iBlockedPeer == true
    LaunchedEffect(me) {
        firestore.collection("chats")
            .whereArrayContains("members", me)
            .get()
            .addOnSuccessListener { snap ->
                val blockedIds = mutableListOf<String>()
                snap.documents.forEach { doc ->
                    val memberMeta = doc.get("memberMeta") as? Map<*, *>
                    val myMeta = memberMeta?.get(me) as? Map<*, *>
                    if (myMeta?.get("iBlockedPeer") == true) {
                        val members = doc.get("members") as? List<*>
                        val other = members?.firstOrNull { it != me } as? String
                        if (other != null) blockedIds += other
                    }
                }

                if (blockedIds.isEmpty()) {
                    blockedUsers = emptyList()
                    loading = false
                } else {
                    firestore.collection("users")
                        .whereIn(FieldPath.documentId(), blockedIds)
                        .get()
                        .addOnSuccessListener { users ->
                            val data = users.documents.map {
                                Triple(
                                    it.id,
                                    it.getString("displayName") ?: "Unknown",
                                    it.getString("photoUrl")
                                )
                            }
                            blockedUsers = data
                            loading = false
                        }
                        .addOnFailureListener { loading = false }
                }
            }
            .addOnFailureListener { loading = false }
    }

    val FooterGray = Color(0xFF6B7280)

    // --- Unblock logic ---
    fun unblockUser(peerId: String) {
        scope.launch {
            val chatDocs = firestore.collection("chats")
                .whereArrayContains("members", me)
                .get()
                .await()
            chatDocs.documents.forEach { doc ->
                val members = doc.get("members") as? List<*>
                if (members?.contains(peerId) == true) {
                    firestore.collection("chats").document(doc.id)
                        .update(
                            mapOf(
                                "memberMeta.$me.iBlockedPeer" to false,
                                "memberMeta.$peerId.blockedByOther" to false,
                                "updatedAt" to Timestamp.now()
                            )
                        )
                }
            }

            firestore.collection("users").document(me)
                .collection("blocks").document(peerId)
                .delete()
                .addOnSuccessListener {
                    blockedUsers = blockedUsers.filterNot { it.first == peerId }
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when {
                loading -> CircularProgressIndicator()
                blockedUsers.isEmpty() -> {
                    Text(
                        "You haven't blocked anyone yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(blockedUsers) { (uid, name, photo) ->
                            ListItem(
                                leadingContent = {
                                    UserAvatar(
                                        photoUrl = photo,
                                        size = 48.dp,
                                        fallbackRes = com.abdallah.cnnct.R.drawable.defaultpp,
                                        contentDescription = name
                                    )
                                },
                                headlineContent = {
                                    Text(name, style = MaterialTheme.typography.titleMedium)
                                },
                                supportingContent = {
                                    Text("Blocked user", style = MaterialTheme.typography.bodySmall)
                                },
                                trailingContent = {
                                    TextButton(
                                        onClick = {
                                            pendingUnblockId = uid
                                            pendingUnblockName = name
                                            confirmDialogVisible = true
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Block,
                                            contentDescription = "Unblock",
                                            tint = Color.Red,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Unblock", color = Color.Red)
                                    }
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("CNNCT© 2026", color = FooterGray, fontSize = 12.sp)
        }
    }

    // 🔴 Confirm Unblock Dialog
    if (confirmDialogVisible && pendingUnblockId != null) {
        AlertDialog(
            onDismissRequest = { confirmDialogVisible = false },
            title = { Text("Unblock ${pendingUnblockName ?: "this user"}?") },
            text = { Text("You will be able to message and call this user again.") },
            confirmButton = {
                TextButton(onClick = {
                    unblockUser(pendingUnblockId!!)
                    confirmDialogVisible = false
                }) {
                    Text("Unblock")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDialogVisible = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
