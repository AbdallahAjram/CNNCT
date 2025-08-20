package com.cnnct.chat.mvc.view

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AttachChooserDialog(
    onPickMedia: () -> Unit,
    onPickDocument: () -> Unit,
    onPickMultipleMedia: () -> Unit,
    onPickMultipleDocuments: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text("Attach") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onPickMedia(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Photo or Video (Gallery)")
                }
                TextButton(onClick = { onPickMultipleMedia(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Photos/Videos — multiple")
                }
                TextButton(onClick = { onPickDocument(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Document (PDF / Word / Excel)")
                }
                TextButton(onClick = { onPickMultipleDocuments(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Documents — multiple")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMessageDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = { TextButton(onClick = onSave, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        title = { Text("Edit message") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text("Edit your message") },
                singleLine = false,
                minLines = 2
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteChoiceDialog(
    count: Int,
    canDeleteEveryone: Boolean,
    problems: Map<String, Int> = emptyMap(),
    onDismiss: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (count == 1) "Delete message" else "Delete $count messages",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Choose how you want to delete the selected ${if (count == 1) "message" else "messages"}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (problems.isNotEmpty()) {
                    problems.forEach { (label, n) ->
                        Text("• $n $label", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { onDeleteForMe(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete for me")
                }
                TextButton(
                    onClick = { onDeleteForEveryone(); onDismiss() },
                    enabled = canDeleteEveryone,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Delete for everyone") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}
