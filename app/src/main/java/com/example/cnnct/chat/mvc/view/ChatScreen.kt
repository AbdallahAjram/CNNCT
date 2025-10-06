package com.cnnct.chat.mvc.view

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cnnct.chat.mvc.controller.ChatController
import com.cnnct.chat.mvc.model.Message
import com.cnnct.chat.mvc.model.MessageType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatId: String,
    currentUserId: String,
    controller: ChatController,
    chatType: String = "private",
    title: String = "Chat",
    subtitle: String? = null,

    // mapping helpers (✅ explicit types, with safe defaults)
    nameOf: (String) -> String = { it },
    userPhotoOf: (String) -> String? = { null },   // resolver for per-sender photo
    headerPhotoUrl: String? = null,                // explicit header photo (if provided by caller)
    groupPhotoUrl: String? = null,                 // optional header image for groups

    otherUserId: String? = null,
    otherLastReadId: String? = null,
    otherLastOpenedAtMs: Long? = null,
    memberIds: List<String> = emptyList(),
    memberMeta: Map<String, Any>? = null,
    blockedUserIds: Set<String> = emptySet(),
    onlineMap: Map<String, Long?> = emptyMap(),
    onBack: () -> Unit = {},
    onCallClick: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val messagesRaw by controller.messages.collectAsState()
    val listState = rememberLazyListState()

    // remove hidden-for-me
    val messages = remember(messagesRaw, currentUserId) {
        messagesRaw.filter { m -> m.hiddenFor?.contains(currentUserId) != true }
    }

    // selection
    val selected = remember { mutableStateListOf<String>() }
    val inSelection by remember(selected) { derivedStateOf { selected.isNotEmpty() } }
    fun toggleSelect(id: String) { if (selected.contains(id)) selected.remove(id) else selected.add(id) }
    fun clearSelection() = selected.clear()

    // edit
    var editTargetId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }

    // delete dialog
    var showDeleteDialog by remember { mutableStateOf(false) }

    // attach chooser
    var showAttachDialog by remember { mutableStateOf(false) }

    // media viewer
    data class ViewMedia(val url: String, val name: String?)
    var viewer by remember { mutableStateOf<ViewMedia?>(null) }

    /* ===== pickers ===== */
    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            controller.sendAttachments(chatId, currentUserId, uris, ctx.contentResolver)
        }
    }

    val pickDocuments = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { u ->
                try {
                    ctx.contentResolver.takePersistableUriPermission(
                        u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {}
            }
            controller.sendAttachments(chatId, currentUserId, uris, ctx.contentResolver)
        }
    }

    val docTypes = remember {
        arrayOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    }

    // member meta helpers
    fun metaOf(uid: String): Map<*, *>? = (memberMeta as? Map<*, *>)?.get(uid) as? Map<*, *>
    fun openedAtMs(uid: String): Long? =
        (metaOf(uid)?.get("lastOpenedAt") as? com.google.firebase.Timestamp)?.toDate()?.time
    fun msgTimeMsById(id: String?, msgs: List<Message>): Long? {
        if (id.isNullOrBlank()) return null
        val m = msgs.firstOrNull { it.id == id } ?: return null
        return m.createdAt?.toDate()?.time ?: m.createdAtClient?.toDate()?.time
    }

    // presence
    fun presenceFor(uid: String?): Presence {
        if (uid == null) return Presence.Offline
        if (blockedUserIds.contains(uid)) return Presence.Blocked
        val last = onlineMap[uid]
        val now = System.currentTimeMillis()
        return if (last != null && now - last <= 2.minutes.inWholeMilliseconds)
            Presence.Online else Presence.Offline
    }

    // lifecycle
    DisposableEffect(chatId) {
        controller.openChat(chatId)
        onDispose { controller.stop() }
    }
    LaunchedEffect(chatId) { controller.markOpened(chatId, currentUserId) }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    val isAtBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            lastVisible != null && messages.isNotEmpty() && lastVisible >= messages.lastIndex
        }
    }
    LaunchedEffect(isAtBottom, messages.lastOrNull()?.id, inSelection) {
        if (isAtBottom && !inSelection) {
            messages.lastOrNull()?.let { last -> controller.markRead(chatId, currentUserId, last.id) }
        }
    }

    // day labels
    val zone = remember { ZoneId.systemDefault() }
    fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun dayLabel(ms: Long): String {
        val d = dateOf(ms)
        val today = dateOf(System.currentTimeMillis())
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            else -> d.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        }
    }

    // header presence + photo (use provided headerPhotoUrl if non-null, else derive)
    val headerPresence = if (chatType == "private") presenceFor(otherUserId) else Presence.Offline
    val resolvedHeaderPhoto = headerPhotoUrl
        ?: if (chatType == "private") userPhotoOf(otherUserId ?: "") else groupPhotoUrl

    // delete eligibility
    val eligibility by remember(selected, messages, currentUserId) {
        derivedStateOf { computeDeleteEligibility(messages, selected, currentUserId) }
    }
    val canDeleteEveryone by remember(eligibility, selected) {
        derivedStateOf { selected.isNotEmpty() && eligibility.okIds.size == selected.size }
    }

    Scaffold(
        topBar = {
            if (inSelection) {
                SelectionTopBar(
                    count = selected.size,
                    canEdit = selected.size == 1 &&
                            messages.firstOrNull { it.id == selected.first() }?.let {
                                it.senderId == currentUserId && !it.deleted && it.type == MessageType.text
                            } == true,
                    onClose = { clearSelection() },
                    onEdit = {
                        val m = messages.firstOrNull { it.id == selected.first() } ?: return@SelectionTopBar
                        editTargetId = m.id
                        editText = m.text.orElse("")
                    },
                    onDeleteClick = { showDeleteDialog = true }
                )
            } else {
                TopBar(
                    title = title,
                    subtitle = subtitle,
                    presence = headerPresence,
                    photoUrl = resolvedHeaderPhoto,
                    onBack = onBack,
                    onCallClick = onCallClick
                )
            }
        },
        bottomBar = {
            if (!inSelection && editTargetId == null) {
                MessageInput(
                    onSend = { text -> controller.sendText(chatId, currentUserId, text) },
                    onAttach = { showAttachDialog = true }
                )
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .background(Color(0xFFF1EAF5))
        ) {
            val screenWidth = LocalConfiguration.current.screenWidthDp.dp
            val maxBubbleWidth = screenWidth * 0.78f
            val groups = remember(messages) { buildGroups(messages) }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                contentPadding = PaddingValues(bottom = if (editTargetId == null) 80.dp else 16.dp)
            ) {
                itemsIndexed(groups) { gIndex, g ->
                    val firstMsg = messages[g.start]
                    val isMeBlock = firstMsg.senderId == currentUserId
                    val isGroupChat = chatType == "group"

                    // Day divider between blocks
                    val prevMsg = messages.getOrNull(g.start - 1)
                    val prevMs = prevMsg?.sentAtMs()
                    val firstMs = firstMsg.sentAtMs()
                    val showDayDivider = firstMs != null && (prevMs == null || dateOf(firstMs) != dateOf(prevMs))
                    if (showDayDivider && firstMs != null) {
                        DayDivider(dayLabel(firstMs))
                        Spacer(Modifier.height(4.dp))
                    }

                    val blockHorizontalPad = 12.dp
                    val blockVerticalPad = 4.dp
                    val interBlockSpace = 12.dp

                    // Render each message in the group…
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = blockHorizontalPad, vertical = blockVerticalPad)
                    ) {
                        for (i in g.start..g.end) {
                            val m = messages[i]
                            val me = (m.senderId == currentUserId)
                            val selectedThis = selected.contains(m.id)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (me) Arrangement.End else Arrangement.Start,
                                verticalAlignment = Alignment.Top
                            ) {
                                // ⬅️ Show avatar per message for group chats (for other users)
                                if (!me && isGroupChat) {
                                    AvatarWithStatus(
                                        size = 30.dp,
                                        presence = presenceFor(m.senderId),
                                        photoUrl = userPhotoOf(m.senderId) // per-message photo
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }

                                // Sender label on first message of a (sender) run in group chats
                                val showNameLabel =
                                    isGroupChat && !me &&
                                            (i == g.start || messages[i - 1].senderId != m.senderId)

                                Column(
                                    horizontalAlignment = if (me) Alignment.End else Alignment.Start,
                                    modifier = Modifier.widthIn(max = maxBubbleWidth)
                                ) {
                                    if (showNameLabel) {
                                        Text(
                                            text = nameOf(m.senderId),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color(0xFF6B7280),
                                            modifier = Modifier.padding(bottom = 2.dp, start = 4.dp, end = 4.dp)
                                        )
                                    }

                                    val bubbleColor = when {
                                        m.deleted -> MaterialTheme.colorScheme.surfaceVariant
                                        me -> Color(0xFF7A3EB1)
                                        else -> Color(0xFF2D7FF9)
                                    }
                                    val shape = bubbleShapeInBlock(
                                        isMe = me,
                                        idx = 0,              // per-message bubble (no inner rounding needed)
                                        lastIdx = 0
                                    )

                                    Box(
                                        modifier = Modifier.combinedClickable(
                                            onClick = {
                                                if (inSelection) {
                                                    toggleSelect(m.id)
                                                } else if (!m.deleted && m.type != MessageType.text) {
                                                    m.mediaUrl?.let { url ->
                                                        viewer = ViewMedia(url, m.text)
                                                    }
                                                }
                                            },
                                            onLongClick = { toggleSelect(m.id) }
                                        )
                                    ) {
                                        Surface(
                                            color = if (selectedThis) bubbleColor.copy(alpha = 0.78f) else bubbleColor,
                                            shape = shape,
                                            tonalElevation = 0.dp,
                                            shadowElevation = 0.dp,
                                            modifier = Modifier.then(
                                                if (selectedThis) Modifier.border(
                                                    2.dp,
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                                    shape
                                                ) else Modifier
                                            )
                                        ) {
                                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                                if (m.deleted) {
                                                    Text(
                                                        text = if (me) "You deleted this message." else "This message was deleted.",
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                } else if (m.type == MessageType.text) {
                                                    Text(
                                                        text = m.text.orElse(""),
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                } else {
                                                    AttachmentContent(
                                                        message = m,
                                                        inSelection = inSelection,
                                                        onOpen = {
                                                            m.mediaUrl?.let { url ->
                                                                viewer = ViewMedia(url, m.text)
                                                            }
                                                        }
                                                    )
                                                }

                                                Spacer(Modifier.height(2.dp))

                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    val displayTs = m.createdAt ?: m.createdAtClient
                                                    val time = displayTs?.toDate()?.let {
                                                        android.text.format.DateFormat
                                                            .format("h:mm a", it).toString()
                                                    } ?: "…"
                                                    val timeSuffix =
                                                        if (m.editedAt != null && !m.deleted) " (edited)" else ""

                                                    Text(
                                                        time + timeSuffix,
                                                        color = if (m.deleted)
                                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                                        else Color.White.copy(alpha = 0.75f),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )

                                                    if (me && !m.deleted) {
                                                        Spacer(Modifier.width(6.dp))
                                                        val createdMs = m.sentAtMs()
                                                        val delivered: Boolean
                                                        val read: Boolean

                                                        if (chatType == "private") {
                                                            delivered = createdMs != null &&
                                                                    (otherLastOpenedAtMs != null && otherLastOpenedAtMs >= createdMs)
                                                            val lastReadCutoff =
                                                                msgTimeMsById(otherLastReadId, messages)
                                                            read = createdMs != null && lastReadCutoff != null &&
                                                                    createdMs <= lastReadCutoff
                                                        } else if (chatType == "group") {
                                                            val others = memberIds.filter { it != currentUserId }
                                                            delivered = createdMs != null && others.all { uid ->
                                                                val opened = openedAtMs(uid)
                                                                opened != null && opened >= createdMs
                                                            }
                                                            read = createdMs != null && others.all { uid ->
                                                                val readId = metaOf(uid)?.get("lastReadMessageId") as? String
                                                                val cutoff = msgTimeMsById(readId, messages)
                                                                cutoff != null && cutoff >= createdMs
                                                            }
                                                        } else {
                                                            delivered = false
                                                            read = false
                                                        }

                                                        Ticks(
                                                            sent = createdMs != null,
                                                            delivered = delivered,
                                                            read = read
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // selection badge
                                        if (selectedThis) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .offset(x = 6.dp, y = (-6).dp)
                                                    .size(20.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Filled.Done,
                                                    contentDescription = "Selected",
                                                    tint = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // vertical spacing between messages
                            if (i != g.end) Spacer(Modifier.height(8.dp))
                        }
                    }

                    if (gIndex != groups.lastIndex) Spacer(Modifier.height(interBlockSpace))
                }
            }

            // Edit dialog
            if (editTargetId != null) {
                EditMessageDialog(
                    text = editText,
                    onTextChange = { editText = it },
                    onCancel = { editTargetId = null },
                    onSave = {
                        val id = editTargetId ?: return@EditMessageDialog
                        controller.editMessage(chatId, id, editText)
                        editTargetId = null
                    }
                )
            }

            // Delete dialog
            if (showDeleteDialog) {
                DeleteChoiceDialog(
                    count = selected.size,
                    canDeleteEveryone = canDeleteEveryone,
                    problems = mapOf(
                        "Not yours" to eligibility.notMine.size,
                        "Already deleted" to eligibility.alreadyDeleted.size,
                        "Older than 2 hours" to eligibility.tooOld.size
                    ).filterValues { it > 0 },
                    onDismiss = { showDeleteDialog = false },
                    onDeleteForMe = {
                        controller.deleteForMe(chatId, selected.toList())
                        selected.clear()
                        showDeleteDialog = false
                    },
                    onDeleteForEveryone = {
                        controller.deleteForEveryone(chatId, eligibility.okIds)
                        selected.clear()
                        showDeleteDialog = false
                    }
                )
            }

            // Attach chooser dialog
            if (showAttachDialog) {
                AttachChooserDialog(
                    onPickMedia = {
                        pickMedia.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    onPickDocument = { pickDocuments.launch(docTypes) },
                    onPickMultipleMedia = {
                        pickMedia.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    onPickMultipleDocuments = { pickDocuments.launch(docTypes) },
                    onDismiss = { showAttachDialog = false }
                )
            }

            // Media viewer
            viewer?.let { v ->
                MediaViewerDialog(
                    url = v.url,
                    fileName = v.name,
                    onDismiss = { viewer = null }
                )
            }
        }
    }
}

/* ---------- tiny helpers ---------- */

private fun String?.orElse(fallback: String) = if (this.isNullOrEmpty()) fallback else this
