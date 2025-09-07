package com.example.cnnct.calls.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cnnct.calls.model.UserCallLog
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CallRow(log: UserCallLog, onClick: () -> Unit) {
    val isMissed = log.status == "missed"
    val arrow = if (log.direction == "outgoing") Icons.Default.ArrowUpward else Icons.Default.ArrowDownward
    val formatter = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val timeText = log.startedAt?.toDate()?.let { formatter.format(it) } ?: "—"

    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)
        .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = CircleShape, tonalElevation = 2.dp, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(arrow, contentDescription = null, modifier = Modifier.size(24.dp), tint = if (isMissed) Color.Red else Color.Unspecified)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = log.peerId, style = MaterialTheme.typography.bodyLarge) // replace peerId with displayName lookup if you want
            Text(text = timeText, style = MaterialTheme.typography.bodySmall)
        }
        if (log.duration != null && log.duration > 0) {
            val mins = log.duration / 60
            val secs = log.duration % 60
            Text(String.format("%d:%02d", mins, secs), style = MaterialTheme.typography.bodySmall)
        }
    }
}
