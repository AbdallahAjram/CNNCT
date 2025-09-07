package com.example.cnnct.calls.view

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun InCallScreen(onEnd: () -> Unit, onToggleMute: (Boolean) -> Unit) {
    var muted by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsed++
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("In Call", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(String.format("%d:%02d", elapsed / 60, elapsed % 60))
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
