package com.example.cnnct.settings.view

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.cnnct.auth.controller.LoginActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(onBack: () -> Unit) {
    val auth = FirebaseAuth.getInstance()
    val context = LocalContext.current

    var showDialog by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Security", color = contentColor) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = contentColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backgroundColor,
                    titleContentColor = contentColor,
                    navigationIconContentColor = contentColor
                )
            )
        },
        containerColor = backgroundColor
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 🔑 Reset password button
                Button(
                    onClick = {
                        val email = auth.currentUser?.email
                        if (email.isNullOrEmpty()) {
                            Toast.makeText(
                                context,
                                "No email associated with this account.",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }

                        isSending = true
                        auth.sendPasswordResetEmail(email)
                            .addOnSuccessListener {
                                isSending = false
                                Toast.makeText(
                                    context,
                                    "Password reset email sent to $email",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            .addOnFailureListener {
                                isSending = false
                                Toast.makeText(
                                    context,
                                    "Failed to send reset email: ${it.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    },
                    enabled = !isSending,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Send Password Reset Email")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 🚪 Logout button
                OutlinedButton(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Logout")
                }

                Spacer(modifier = Modifier.height(32.dp))

                // 🔒 Privacy info card
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    onClick = { showDialog = true }
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Your Privacy Matters",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Your messages and account data are encrypted and securely stored in Google Cloud Firestore.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Footer
            Text(
                text = "CNNCT© 2025",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(vertical = 16.dp)
            )
        }
    }

    // 🗨️ Privacy message dialog
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Got it")
                }
            },
            title = { Text("Your Privacy Matters") },
            text = {
                Text(
                    "Your messages and account data are encrypted and securely stored in Google Cloud Firestore.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        )
    }

    // 🚪 Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout?") },
            text = {
                Text(
                    "You will be signed out from your account and redirected to the login page."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false

                        // 🔒 Sign out from Firebase + Google
                        try {
                            auth.signOut()
                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                            val googleClient = GoogleSignIn.getClient(context, gso)
                            googleClient.signOut().addOnCompleteListener {
                                val intent = Intent(context, LoginActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                context.startActivity(intent)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error signing out: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Logout", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
