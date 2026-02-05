// app/src/main/java/com/example/cnnct/calls/view/IncomingCallScreen.kt
package com.abdallah.cnnct.calls.view

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.abdallah.cnnct.calls.view.components.*

@Composable
fun IncomingCallScreen(
    callerId: String,
    callerName: String? = null,
    callerPhotoUrl: String? = null,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val acceptGreen = Color(0xFF10B981)
    val rejectRed = MaterialTheme.colorScheme.error

    BackgroundSurface {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(photoUrl = callerPhotoUrl, size = 128.dp, contentDescription = "Caller photo")
                Spacer(Modifier.height(16.dp))
                TitleAndSubtitle(
                    title = callerName ?: callerId,
                    subtitle = "Incoming call",
                    center = true
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallIconButton(
                        icon = { Icon(Icons.Rounded.CallEnd, contentDescription = "Reject") },
                        onClick = onReject,
                        containerColor = rejectRed,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                    CallIconButton(
                        icon = { Icon(Icons.Rounded.Call, contentDescription = "Accept") },
                        onClick = onAccept,
                        containerColor = acceptGreen,
                        contentColor = Color.White
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
