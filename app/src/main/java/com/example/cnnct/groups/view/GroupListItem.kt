import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cnnct.homepage.model.ChatSummary
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun GroupListItem(
    chatSummary: ChatSummary,
    currentUserId: String,
    userMap: Map<String, String>,
    onClick: () -> Unit
) {
    val chatName = chatSummary.groupName ?: "Group"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Title + time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = chatName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
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

            // Last line + ticks
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val rawText = chatSummary.lastMessageText.takeIf { it.isNotBlank() } ?: "No messages yet"
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

                if (chatSummary.lastMessageSenderId == currentUserId && chatSummary.lastMessageText.isNotBlank()) {
                    val effectiveStatus = chatSummary.lastMessageStatus
                        ?: if (chatSummary.lastMessageIsRead) "read" else "delivered"
                    val (ticks, color) = when (effectiveStatus) {
                        "read" -> "✓✓" to MaterialTheme.colorScheme.primary
                        "delivered" -> "✓✓" to MaterialTheme.colorScheme.onSurfaceVariant
                        "sent" -> "✓" to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> null to MaterialTheme.colorScheme.onSurfaceVariant
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

/* ---------- helpers ---------- */

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
