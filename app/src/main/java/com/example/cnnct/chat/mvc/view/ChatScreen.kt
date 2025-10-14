package com.cnnct.chat.mvc.view

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cnnct.chat.mvc.controller.ChatController
import com.cnnct.chat.mvc.model.Message
import com.cnnct.chat.mvc.model.MessageType
import com.example.cnnct.chat.view.GroupInfoActivity
import com.example.cnnct.chat.view.PersonInfoActivity
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

    nameOf: (String) -> String = { it },
    userPhotoOf: (String) -> String? = { null },
    headerPhotoUrl: String? = null,
    groupPhotoUrl: String? = null,

    otherUserId: String? = null,
    otherLastReadId: String? = null,
    otherLastOpenedAtMs: Long? = null,
    memberIds: List<String> = emptyList(),
    memberMeta: Map<String, Any>? = null,

    iBlockedPeer: Boolean = false,
    blockedByOther: Boolean = false,

    // timestamp after which messages are visible (per-user clear)
    clearedBeforeMs: Long? = null,

    blockedUserIds: Set<String> = emptySet(),
    onlineMap: Map<String, Long?> = emptyMap(),
    onBack: () -> Unit = {},
    onCallClick: () -> Unit = {},

    // header tap + 3-dots menu callbacks
    onHeaderClick: () -> Unit = {},
    onSearch: (() -> Unit)? = null,
    onClearChat: (() -> Unit)? = null,
    onBlockPeer: (() -> Unit)? = null,  // private only
    onLeaveGroup: (() -> Unit)? = null  // group only
) {
    val ctx = LocalContext.current
    val messagesRaw by controller.messages.collectAsState()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // Hide messages that are "for me" cleared: only show items with time >= clearedBeforeMs
    val messages = remember(messagesRaw, currentUserId, clearedBeforeMs) {
        messagesRaw.filter { m ->
            val hiddenOk = m.hiddenFor?.contains(currentUserId) != true
            val sentTime = m.createdAt?.toDate()?.time ?: m.createdAtClient?.toDate()?.time
            val clearOk = clearedBeforeMs?.let { cutoff ->
                sentTime == null || sentTime >= cutoff
            } ?: true
            hiddenOk && clearOk
        }
    }

    // Search functionality state
    var searchQuery by remember { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var currentMatchIndex by remember { mutableStateOf(0) }
    var isSearchActive by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    // Update search results when query or messages change
    LaunchedEffect(searchQuery, messages) {
        if (searchQuery.isNotBlank()) {
            val matches = messages.mapIndexedNotNull { index, msg ->
                if (msg.text?.contains(searchQuery, ignoreCase = true) == true &&
                    !msg.deleted && msg.type == MessageType.text) index else null
            }
            searchMatches = matches
            currentMatchIndex = if (matches.isNotEmpty()) 0 else -1

            // Auto-scroll to first match
            if (matches.isNotEmpty()) {
                listState.animateScrollToItem(matches[0])
            }
        } else {
            searchMatches = emptyList()
            currentMatchIndex = -1
        }
    }

    // Auto-scroll to current match when navigating
    LaunchedEffect(currentMatchIndex) {
        if (currentMatchIndex >= 0 && currentMatchIndex < searchMatches.size) {
            val matchIndex = searchMatches[currentMatchIndex]
            if (matchIndex >= 0 && matchIndex < messages.size) {
                listState.animateScrollToItem(matchIndex)
            }
        }
    }

    val selected = remember { mutableStateListOf<String>() }
    val inSelection by remember(selected) { derivedStateOf { selected.isNotEmpty() } }
    fun toggleSelect(id: String) { if (selected.contains(id)) selected.remove(id) else selected.add(id) }
    fun clearSelection() = selected.clear()

    var editTargetId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAttachDialog by remember { mutableStateOf(false) }

    // NEW: confirmation dialogs
    var showClearDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }

    data class ViewMedia(val url: String, val name: String?)
    var viewer by remember { mutableStateOf<ViewMedia?>(null) }

    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) controller.sendAttachments(chatId, currentUserId, uris, ctx.contentResolver)
    }

    val pickDocuments = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { u ->
                try {
                    ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

    fun metaOf(uid: String): Map<*, *>? = (memberMeta as? Map<*, *>)?.get(uid) as? Map<*, *>
    fun openedAtMs(uid: String): Long? =
        (metaOf(uid)?.get("lastOpenedAt") as? com.google.firebase.Timestamp)?.toDate()?.time
    fun msgTimeMsById(id: String?, msgs: List<Message>): Long? {
        if (id.isNullOrBlank()) return null
        val m = msgs.firstOrNull { it.id == id } ?: return null
        return m.createdAt?.toDate()?.time ?: m.createdAtClient?.toDate()?.time
    }

    fun presenceFor(uid: String?): Presence {
        if (uid == null) return Presence.Offline
        if (blockedUserIds.contains(uid)) return Presence.Blocked
        val last = onlineMap[uid]
        val now = System.currentTimeMillis()
        return if (last != null && now - last <= 2.minutes.inWholeMilliseconds)
            Presence.Online else Presence.Offline
    }

    DisposableEffect(chatId) {
        controller.openChat(chatId)
        onDispose { controller.stop() }
    }
    LaunchedEffect(chatId) { controller.markOpened(chatId, currentUserId) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && !isSearchActive) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val isAtBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            lastVisible != null && messages.isNotEmpty() && lastVisible >= messages.lastIndex
        }
    }
    LaunchedEffect(isAtBottom, messages.lastOrNull()?.id, inSelection, isSearchActive) {
        if (isAtBottom && !inSelection && !isSearchActive) {
            messages.lastOrNull()?.let { last -> controller.markRead(chatId, currentUserId, last.id) }
        }
    }

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

    val headerPresence = if (chatType == "private") presenceFor(otherUserId) else Presence.Offline
    val resolvedHeaderPhoto = headerPhotoUrl
        ?: if (chatType == "private") userPhotoOf(otherUserId ?: "") else groupPhotoUrl

    val eligibility by remember(selected, messages, currentUserId) {
        derivedStateOf { computeDeleteEligibility(messages, selected, currentUserId) }
    }
    val canDeleteEveryone by remember(eligibility, selected) {
        derivedStateOf { selected.isNotEmpty() && eligibility.okIds.size == selected.size }
    }

    Scaffold(
        topBar = {
            if (isSearchActive) {
                SearchTopBar(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    matchCount = searchMatches.size,
                    currentMatchIndex = currentMatchIndex,
                    onPreviousMatch = {
                        if (searchMatches.isNotEmpty()) {
                            currentMatchIndex = if (currentMatchIndex > 0) currentMatchIndex - 1 else searchMatches.lastIndex
                        }
                    },
                    onNextMatch = {
                        if (searchMatches.isNotEmpty()) {
                            currentMatchIndex = if (currentMatchIndex < searchMatches.lastIndex) currentMatchIndex + 1 else 0
                        }
                    },
                    onCloseSearch = {
                        isSearchActive = false
                        searchQuery = ""
                        focusManager.clearFocus()
                    },
                    focusRequester = searchFocusRequester
                )
            } else if (inSelection) {
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
                // FIX: Use LaunchedEffect to handle focus when search becomes active
                var triggerFocus by remember { mutableStateOf(false) }

                val doSearch = {
                    isSearchActive = true
                    triggerFocus = true
                }

                val doClear = {
                    if (onClearChat != null) showClearDialog = true
                    else Toast.makeText(ctx, "Clear chat (placeholder)", Toast.LENGTH_SHORT).show()
                }
                val doBlock = {
                    if (onBlockPeer != null) showBlockDialog = true
                    else Toast.makeText(ctx, "Block (placeholder)", Toast.LENGTH_SHORT).show()
                }
                val doLeave = onLeaveGroup ?: { Toast.makeText(ctx, "Leave group (placeholder)", Toast.LENGTH_SHORT).show() }

                // Handle focus when search is triggered
                if (triggerFocus) {
                    LaunchedEffect(Unit) {
                        searchFocusRequester.requestFocus()
                        triggerFocus = false // Reset the trigger
                    }
                }

                TopBar(
                    title = title,
                    subtitle = subtitle,
                    presence = headerPresence,
                    photoUrl = resolvedHeaderPhoto,
                    onBack = onBack,
                    onCallClick = onCallClick,
                    onHeaderClick = {
                        if (chatType == "private" && otherUserId != null) {
                            ctx.startActivity(
                                Intent(ctx, PersonInfoActivity::class.java)
                                    .putExtra("uid", otherUserId)
                            )
                        } else if (chatType == "group") {
                            ctx.startActivity(
                                Intent(ctx, GroupInfoActivity::class.java)
                                    .putExtra("chatId", chatId)
                            )
                        }
                        onHeaderClick()
                    },
                    chatType = chatType,
                    onSearch = doSearch,
                    onClearChat = doClear,
                    onBlockPeer = if (chatType == "private") doBlock else null,
                    onLeaveGroup = if (chatType == "group") doLeave else null
                )
            }
        },
        bottomBar = {
            if (!inSelection && editTargetId == null && !isSearchActive) {
                MessageInput(
                    onSend = { text ->
                        when {
                            iBlockedPeer -> {
                                Toast.makeText(ctx, "You blocked this user", Toast.LENGTH_SHORT).show()
                                return@MessageInput
                            }
                            blockedByOther -> {
                                Toast.makeText(ctx, "This user blocked you", Toast.LENGTH_SHORT).show()
                                return@MessageInput
                            }
                        }
                        controller.sendText(chatId, currentUserId, text)
                    },
                    onAttach = {
                        when {
                            iBlockedPeer -> {
                                Toast.makeText(ctx, "You blocked this user", Toast.LENGTH_SHORT).show()
                                return@MessageInput
                            }
                            blockedByOther -> {
                                Toast.makeText(ctx, "This user blocked you", Toast.LENGTH_SHORT).show()
                                return@MessageInput
                            }
                        }
                        showAttachDialog = true
                    }
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
            Column(Modifier.fillMaxSize()) {
                when {
                    iBlockedPeer -> {
                        BlockBanner(
                            text = "🚫 You blocked this user. To chat, unblock first.",
                            bg = MaterialTheme.colorScheme.error,
                            fg = MaterialTheme.colorScheme.onError
                        )
                    }
                    blockedByOther -> {
                        BlockBanner(
                            text = "⚠️ This user has blocked you. You can't send messages.",
                            bg = MaterialTheme.colorScheme.tertiaryContainer,
                            fg = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }

                val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                val maxBubbleWidth = screenWidth * 0.78f
                val groups = remember(messages) { buildGroups(messages) }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(bottom = if (editTargetId == null && !isSearchActive) 80.dp else 16.dp)
                ) {
                    itemsIndexed(groups) { gIndex, g ->
                        val firstMsg = messages[g.start]
                        val isGroupChat = chatType == "group"

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

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = blockHorizontalPad, vertical = blockVerticalPad)
                        ) {
                            for (i in g.start..g.end) {
                                val m = messages[i]
                                val me = (m.senderId == currentUserId)
                                val selectedThis = selected.contains(m.id)

                                // Search highlighting
                                val isSearchMatch = searchMatches.contains(i)
                                val isCurrentSearchMatch = searchMatches.getOrNull(currentMatchIndex) == i

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (me) Arrangement.End else Arrangement.Start,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    if (!me && isGroupChat) {
                                        AvatarWithStatus(
                                            size = 30.dp,
                                            presence = presenceFor(m.senderId),
                                            photoUrl = userPhotoOf(m.senderId)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }

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
                                            isCurrentSearchMatch -> Color(0xFFCCE5FF) // Highlight current search match
                                            isSearchMatch -> Color(0xFFEAF3FF) // Highlight all search matches
                                            m.deleted -> MaterialTheme.colorScheme.surfaceVariant
                                            me -> Color(0xFF7A3EB1)
                                            else -> Color(0xFF2D7FF9)
                                        }

                                        val textColor = when {
                                            isCurrentSearchMatch || isSearchMatch -> Color.Black // Dark text for highlighted backgrounds
                                            m.deleted -> MaterialTheme.colorScheme.onSurfaceVariant
                                            else -> Color.White
                                        }

                                        val shape = bubbleShapeInBlock(isMe = me, idx = 0, lastIdx = 0)

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
                                                            color = textColor,
                                                            style = MaterialTheme.typography.bodyMedium
                                                        )
                                                    } else if (m.type == MessageType.text) {
                                                        val messageText = m.text.orElse("")
                                                        if (isSearchMatch && searchQuery.isNotBlank()) {
                                                            // Highlight search terms in message text
                                                            HighlightedText(
                                                                text = messageText,
                                                                query = searchQuery,
                                                                textColor = textColor
                                                            )
                                                        } else {
                                                            Text(
                                                                text = messageText,
                                                                color = textColor,
                                                                style = MaterialTheme.typography.bodyMedium
                                                            )
                                                        }
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
                                                            else textColor.copy(alpha = 0.75f),
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

                                if (i != g.end) Spacer(Modifier.height(8.dp))
                            }
                        }

                        if (gIndex != groups.lastIndex) Spacer(Modifier.height(interBlockSpace))
                    }
                }

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

                viewer?.let { v ->
                    MediaViewerDialog(
                        url = v.url,
                        fileName = v.name,
                        onDismiss = { viewer = null }
                    )
                }

                // ========================
                // Confirm dialogs
                // ========================
                if (showClearDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearDialog = false },
                        title = { Text("Clear chat?") },
                        text = {
                            Text(
                                "This will clear the conversation on your device. " +
                                        "Messages remain for others and in Firestore."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showClearDialog = false
                                onClearChat?.invoke()
                            }) { Text("Clear") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
                        }
                    )
                }

                if (showBlockDialog) {
                    val isPrivate = chatType == "private"
                    AlertDialog(
                        onDismissRequest = { showBlockDialog = false },
                        title = { Text("Block user?") },
                        text = {
                            Text(
                                if (isPrivate) {
                                    "You won't receive messages or calls from this user. " +
                                            "You can unblock later from their profile."
                                } else {
                                    "Blocking is only available for one-to-one chats."
                                }
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = isPrivate && onBlockPeer != null && otherUserId != null,
                                onClick = {
                                    showBlockDialog = false
                                    if (isPrivate && onBlockPeer != null && otherUserId != null) {
                                        onBlockPeer.invoke()
                                    } else {
                                        Toast.makeText(ctx, "Block not available", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) { Text("Block") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showBlockDialog = false }) { Text("Cancel") }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    matchCount: Int,
    currentMatchIndex: Int,
    onPreviousMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onCloseSearch: () -> Unit,
    focusRequester: FocusRequester
) {
    CenterAlignedTopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Search field - using basic styling
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search...", color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    // Use default colors without custom configuration
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { /* Handle done */ })
                )

                // Match counter and navigation
                if (matchCount > 0) {
                    Text(
                        text = "${currentMatchIndex + 1}/$matchCount",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    IconButton(
                        onClick = onPreviousMatch,
                        enabled = matchCount > 0
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = "Previous match",
                            tint = if (matchCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }

                    IconButton(
                        onClick = onNextMatch,
                        enabled = matchCount > 0
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Next match",
                            tint = if (matchCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onCloseSearch) {
                Icon(Icons.Default.Close, contentDescription = "Close search")
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
private fun HighlightedText(
    text: String,
    query: String,
    textColor: Color
) {
    if (query.isEmpty()) {
        Text(text = text, color = textColor, style = MaterialTheme.typography.bodyMedium)
        return
    }

    val regex = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
    val matches = regex.findAll(text)
    val spans = mutableListOf<TextSpan>()
    var lastIndex = 0

    matches.forEach { match ->
        // Add normal text before match
        if (match.range.first > lastIndex) {
            spans.add(TextSpan(text.substring(lastIndex, match.range.first), false))
        }
        // Add highlighted match
        spans.add(TextSpan(text.substring(match.range.first, match.range.last + 1), true))
        lastIndex = match.range.last + 1
    }
    // Add remaining text
    if (lastIndex < text.length) {
        spans.add(TextSpan(text.substring(lastIndex), false))
    }

    Text(
        text = buildAnnotatedString {
            spans.forEach { span ->
                if (span.isHighlighted) {
                    // Use basic color properties instead of complex SpanStyle constructor
                    withStyle(
                        style = SpanStyle(
                            background = Color.Yellow,
                            color = Color.Black
                        )
                    ) {
                        append(span.text)
                    }
                } else {
                    withStyle(
                        style = SpanStyle(color = textColor)
                    ) {
                        append(span.text)
                    }
                }
            }
        },
        style = MaterialTheme.typography.bodyMedium
    )
}

private data class TextSpan(val text: String, val isHighlighted: Boolean)

@Composable
private fun BlockBanner(text: String, bg: Color, fg: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun String?.orElse(fallback: String) = if (this.isNullOrEmpty()) fallback else this