package com.abdallah.cnnct.auth.view

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CompleteProfileScreen(
    onCompleteClick: (name: String, displayName: String, phoneDigits: String, phoneLocked: Boolean) -> Unit,
    currentUserFn: () -> com.google.firebase.auth.FirebaseUser?, // or pass user object directly
    fetchProfileData: suspend (String) -> Map<String, Any?>?,
    isLoading: Boolean = false
) {
    // We need the user ID to fetch data.
    val user = currentUserFn()
    val uid = user?.uid

    var name by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    // var originalDisplayName by remember { mutableStateOf<String?>(null) } // Handled by Repo update logic now

    // Keep digits-only in state; render with dash in UI
    var phoneDigits by remember { mutableStateOf("") }
    var phoneLocked by remember { mutableStateOf(false) }
    
    // Load existing profile data
    LaunchedEffect(uid) {
        if (!uid.isNullOrEmpty()) {
            val data = fetchProfileData(uid)
            if (data != null) {
                name = data["name"] as? String ?: ""
                displayName = data["displayName"] as? String ?: ""
                val existingPhone = data["phoneNumber"] as? String
                if (!existingPhone.isNullOrEmpty()) {
                    phoneDigits = existingPhone.filter { it.isDigit() }.take(8)
                    phoneLocked = true
                }
            }
        }
    }

    fun formatPhoneForUi(digitsOnly: String): String {
        val digits = digitsOnly.filter { it.isDigit() }.take(8)
        return when {
            digits.length >= 3 -> digits.substring(0, 2) + "-" + digits.substring(2)
            digits.length == 2 -> digits + "-"
            else -> digits
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Complete Your Profile",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "+961",
                        modifier = Modifier.padding(end = 8.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    OutlinedTextField(
                        value = formatPhoneForUi(phoneDigits),
                        onValueChange = { input ->
                            if (!phoneLocked) {
                                // keep digits-only in state; UI shows dashed
                                phoneDigits = input.filter { it.isDigit() }.take(8)
                            }
                        },
                        label = { Text(if (phoneLocked) "Phone (locked)" else "Phone (03-123456)") },
                        singleLine = true,
                        enabled = !phoneLocked && !isLoading, // disable if already set or loading
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        onCompleteClick(name, displayName, phoneDigits, phoneLocked)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                     if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Continue")
                    }
                }

            }
        }
    }
}
