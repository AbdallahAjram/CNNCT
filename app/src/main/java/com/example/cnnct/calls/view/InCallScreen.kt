package com.example.cnnct.calls.view

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun InCallScreen(
    callerName: String = "Caller",
    initialElapsedSeconds: Long = 0L,
    callStatus: String = "ringing",
    onEnd: () -> Unit,
    onToggleMute: (Boolean) -> Unit
) {
    var muted by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(initialElapsedSeconds) }

    if (callStatus == "in-progress") {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                elapsed += 1
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(24.dp)
        ) {
            if (callStatus == "ringing") {
                Text("Calling...", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("In call with", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(callerName, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Text(formatElapsed(elapsed), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(24.dp))
            Row {
                Button(onClick = {
                    muted = !muted
                    onToggleMute(muted)
                }) {
                    Text(if (muted) "Unmute" else "Mute")
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onEnd, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("End")
                }
            }
        }
    }
}

private fun formatElapsed(seconds: Long): String {
    val hh = seconds / 3600
    val mm = (seconds % 3600) / 60
    val ss = seconds % 60
    return String.format("%02d:%02d:%02d", hh, mm, ss)
}
