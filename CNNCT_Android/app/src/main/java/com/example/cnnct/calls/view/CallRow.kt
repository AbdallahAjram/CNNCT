// app/src/main/java/com/example/cnnct/calls/view/CallRow.kt
package com.example.cnnct.calls.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.cnnct.R
import com.example.cnnct.calls.model.UserCallLog
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CallRow(
    log: UserCallLog,
    onClick: () -> Unit,
    peerName: String? = null,
    peerPhotoUrl: String? = null,
    avatarSize: Dp = 48.dp
) {
    val isIncoming = log.direction == "incoming"
    val arrow = if (isIncoming) Icons.Rounded.SouthWest else Icons.Rounded.NorthEast

    // Declined (rejected) now red like missed
    // Declined (rejected) now red like missed
    // Incoming "ended" (no start time) means caller hung up -> treat as missed (Red)
    val arrowColor = when {
        log.status == "answered" -> Color(0xFF4CAF50) // Green for connected/answered calls (both directions)
        isIncoming -> Color(0xFFD32F2F) // Red for all non-answered Incoming (Missed, Rejected, Ended-without-answer)
        else -> MaterialTheme.colorScheme.primary // Purple for all non-answered Outgoing (Cancelled, No Answer, Rejected)
    }

    val ts = (log.endedAt ?: log.startedAt)?.toDate()
    val timeText = ts?.let { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(it) } ?: "—"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarThumb(photoUrl = peerPhotoUrl, size = avatarSize)

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = peerName ?: log.peerId,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = arrowColor.copy(alpha = 0.15f),
                    contentColor = arrowColor,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(arrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = statusLabel(log.status),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val dur = log.duration?.takeIf { it > 0 }
        if (dur != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = formatDuration(dur),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AvatarThumb(photoUrl: String?, size: Dp) {
    val shape = CircleShape
    if (photoUrl.isNullOrBlank()) {
        Image(
            painter = painterResource(R.drawable.defaultpp),
            contentDescription = "Avatar",
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
    } else {
        AsyncImage(
            model = photoUrl,
            contentDescription = "Avatar",
            modifier = Modifier
                .size(size)
                .clip(shape),
            contentScale = ContentScale.Crop
        )
    }
}

private fun statusLabel(status: String?): String = when (status) {
    "answered", "in-progress", "accepted" -> "Answered"
    "missed" -> "Missed"
    "rejected" -> "Declined"
    "ended" -> "Ended"
    else -> status ?: "—"
}

private fun formatDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
