package com.abdallah.cnnct.chat.core.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet displaying AI smart replies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSuggestionsDialog(
    isVisible: Boolean,
    suggestions: List<String>,
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSuggestionClick: (String) -> Unit
) {
    if (!isVisible) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("AI Smart Replies", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))

            when {
                isLoading -> {
                    val loadingMessages = listOf("Analyzing context...", "Thinking...", "Drafting replies...")
                    var loadingIndex by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
                    androidx.compose.runtime.LaunchedEffect(isLoading) {
                        if (isLoading) {
                            loadingIndex = 0
                            while(true) {
                                kotlinx.coroutines.delay(1500)
                                loadingIndex = (loadingIndex + 1) % loadingMessages.size
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = loadingMessages[loadingIndex],
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                error != null -> Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> suggestions.forEach { reply ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onSuggestionClick(reply) },
                        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            text = reply,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
