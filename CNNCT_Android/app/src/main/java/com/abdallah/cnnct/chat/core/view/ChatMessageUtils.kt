package com.cnnct.chat.mvc.view

import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.ui.unit.dp
import com.cnnct.chat.mvc.model.Message

/* ===== Times & grouping ===== */

fun Message.sentAtMs(): Long? =
    createdAt?.toDate()?.time ?: createdAtClient?.toDate()?.time

const val GROUP_GAP_MS = 5 * 60 * 1000L  // 5 minutes
const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L

data class MsgGroup(
    val start: Int,
    val end: Int,           // inclusive
    val senderId: String
)

fun buildGroups(messages: List<Message>): List<MsgGroup> {
    if (messages.isEmpty()) return emptyList()
    val groups = mutableListOf<MsgGroup>()
    var start = 0
    var sender = messages.first().senderId
    var lastMs = messages.first().sentAtMs() ?: Long.MIN_VALUE

    for (i in 1 until messages.size) {
        val cur = messages[i]
        val curMs = cur.sentAtMs() ?: Long.MIN_VALUE
        val sameSender = cur.senderId == sender
        val close = curMs - lastMs <= GROUP_GAP_MS
        if (!(sameSender && close)) {
            groups += MsgGroup(start, i - 1, sender)
            start = i
            sender = cur.senderId
        }
        lastMs = curMs
    }
    groups += MsgGroup(start, messages.lastIndex, sender)
    return groups
}

/* ===== Bubble shape ===== */

fun bubbleShapeInBlock(isMe: Boolean, idx: Int, lastIdx: Int): AbsoluteRoundedCornerShape {
    val big  = 18.dp
    val flat = 0.dp
    val tiny = 4.dp

    val isFirst = idx == 0
    val isLast  = idx == lastIdx

    return when {
        lastIdx == 0 -> {
            if (isMe) AbsoluteRoundedCornerShape(topLeft = big, topRight = tiny, bottomRight = big, bottomLeft = big)
            else      AbsoluteRoundedCornerShape(topLeft = tiny, topRight = big, bottomRight = big, bottomLeft = big)
        }
        isFirst -> {
            if (isMe) AbsoluteRoundedCornerShape(topLeft = big, topRight = tiny, bottomRight = flat, bottomLeft = flat)
            else      AbsoluteRoundedCornerShape(topLeft = tiny, topRight = big, bottomRight = flat, bottomLeft = flat)
        }
        !isLast -> {
            AbsoluteRoundedCornerShape(topLeft = flat, topRight = flat, bottomRight = flat, bottomLeft = flat)
        }
        else -> {
            AbsoluteRoundedCornerShape(topLeft = flat, topRight = flat, bottomRight = big, bottomLeft = big)
        }
    }
}

/* ===== Delete-for-everyone eligibility ===== */

data class DeleteEligibility(
    val okIds: List<String>,
    val notMine: List<String>,
    val alreadyDeleted: List<String>,
    val tooOld: List<String>,
)

fun computeDeleteEligibility(
    messages: List<Message>,
    selected: List<String>,
    currentUserId: String
): DeleteEligibility {
    val now = System.currentTimeMillis()
    val notMine = mutableListOf<String>()
    val alreadyDeleted = mutableListOf<String>()
    val tooOld = mutableListOf<String>()
    val ok = mutableListOf<String>()

    selected.forEach { id ->
        val m = messages.firstOrNull { it.id == id } ?: return@forEach
        when {
            m.senderId != currentUserId -> notMine += id
            m.deleted -> alreadyDeleted += id
            (m.sentAtMs()?.let { now - it } ?: Long.MAX_VALUE) > TWO_HOURS_MS -> tooOld += id
            else -> ok += id
        }
    }
    return DeleteEligibility(ok, notMine, alreadyDeleted, tooOld)
}
