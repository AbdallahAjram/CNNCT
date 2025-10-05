// app/src/main/java/com/example/cnnct/calls/view/InCallScreen.kt
package com.example.cnnct.calls.view

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cnnct.calls.view.components.*

@Composable
fun InCallScreen(
    callerName: String,
    callerPhone: String?,
    callerPhotoUrl: String?,
    initialElapsedSeconds: Long,
    callStatus: String,                 // ringing | in-progress | ended | rejected | missed
    onEnd: () -> Unit,
    onToggleMute: (Boolean) -> Unit
) {
    var muted by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(initialElapsedSeconds) }

    LaunchedEffect(callStatus) {
        if (callStatus == "in-progress") {
            while (true) {
                kotlinx.coroutines.delay(1000)
                elapsed += 1
            }
        }
    }

    val (statusLabel, statusColor) = when (callStatus) {
        "in-progress" -> "Live" to Color(0xFF10B981)      // green
        "ringing" -> "Ringing…" to MaterialTheme.colorScheme.primary
        "rejected" -> "Declined" to MaterialTheme.colorScheme.error
        "missed" -> "Missed" to MaterialTheme.colorScheme.error
        "ended" -> "Ended" to MaterialTheme.colorScheme.outline
        else -> callStatus to MaterialTheme.colorScheme.outline
    }

    val endRed = MaterialTheme.colorScheme.error

    BackgroundSurface {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(photoUrl = callerPhotoUrl, size = 112.dp, contentDescription = "Contact photo")
                Spacer(Modifier.height(16.dp))
                TitleAndSubtitle(title = callerName, subtitle = callerPhone ?: "")
                Spacer(Modifier.height(12.dp))
                StatusChip(text = statusLabel, color = statusColor)
                if (callStatus == "in-progress") {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = formatSeconds(elapsed),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CallIconButton(
                        icon = {
                            Icon(
                                imageVector = if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                contentDescription = if (muted) "Unmute" else "Mute"
                            )
                        },
                        onClick = {
                            muted = !muted
                            onToggleMute(muted)
                        }
                    )
                    CallPrimaryButton(
                        label = "End",
                        onClick = onEnd,
                        containerColor = endRed,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (muted) "Microphone is muted" else "Microphone is on",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun formatSeconds(total: Long): String {
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
