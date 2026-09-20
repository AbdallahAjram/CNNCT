// app/src/main/java/com/example/cnnct/calls/view/IncomingCallScreen.kt
package com.abdallah.cnnct.calls.view

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.abdallah.cnnct.calls.view.components.*
import kotlinx.coroutines.launch

@Composable
fun IncomingCallScreen(
    callerId: String,
    callerName: String? = null,
    callerPhotoUrl: String? = null,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val acceptGreen = MaterialTheme.colorScheme.primary
    val rejectRed = MaterialTheme.colorScheme.error

    var visible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        visible = true
    }

    // Function to handle delayed exit
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun handleAction(action: () -> Unit) {
        visible = false
        // wait for exit animation to finish before invoking action
        scope.launch {
            kotlinx.coroutines.delay(300) // approx exit animation duration
            action()
        }
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + androidx.compose.animation.fadeOut()
    ) {
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
                            onClick = { handleAction(onReject) },
                            containerColor = rejectRed,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                        CallIconButton(
                            icon = { Icon(Icons.Rounded.Call, contentDescription = "Accept") },
                            onClick = { handleAction(onAccept) },
                            containerColor = acceptGreen,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
