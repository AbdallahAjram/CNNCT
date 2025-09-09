package com.example.cnnct.calls.view

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun IncomingCallScreen(callerId: String, onAccept: () -> Unit, onReject: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text("Incoming call", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(callerId, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            Row {
                Button(onClick = onReject, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Reject")
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onAccept) {
                    Text("Accept")
                }
            }
        }
    }
}
