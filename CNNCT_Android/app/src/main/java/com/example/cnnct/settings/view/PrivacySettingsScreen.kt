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
import com.example.cnnct.auth.view.LoginActivity
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
    var showDeleteDialog by remember { mutableStateOf(false) }

    val backgroundColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground

    val accountVm: com.example.cnnct.settings.viewmodel.AccountViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(factory = com.example.cnnct.settings.viewmodel.AccountViewModel.Factory)

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
                    containerColor = backgroundColor
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
                            Toast.makeText(context, "No email found.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        isSending = true
                        auth.sendPasswordResetEmail(email)
                            .addOnSuccessListener {
                                isSending = false
                                Toast.makeText(context, "Reset email sent to $email", Toast.LENGTH_LONG).show()
                            }
                            .addOnFailureListener {
                                isSending = false
                                Toast.makeText(context, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                    },
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Send Password Reset Email")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 🚪 Logout button
                OutlinedButton(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Logout")
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 🔒 Privacy info card
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    onClick = { showDialog = true }
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Your Privacy Matters", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Your data is encrypted and stored in Google Cloud Firestore.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 🗑️ Delete Account Button
                Button(
                    onClick = { showDeleteDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Delete Account", fontWeight = FontWeight.Bold)
                }
            }

            // Footer
            Text(
                text = "CNNCT© 2026",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            )
        }
    }

    // --- DIALOGS (Inside the Composable function) ---

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text("Got it") } },
            title = { Text("Your Privacy Matters") },
            text = { Text("Your messages and account data are encrypted and securely stored in Google Cloud Firestore.") }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout?") },
            text = { Text("You will be signed out and redirected to the login page.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        auth.signOut()
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                        GoogleSignIn.getClient(context, gso).signOut().addOnCompleteListener {
                            val intent = Intent(context, LoginActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                            context.startActivity(intent)
                        }
                    }
                ) { Text("Logout", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Account") },
            text = { Text("Are you sure? This action cannot be undone and all data will be permanently lost.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        accountVm.deleteAccount(
                            onSuccess = {
                                auth.signOut()
                                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                                GoogleSignIn.getClient(context, gso).signOut().addOnCompleteListener {
                                    val intent = Intent(context, LoginActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    }
                                    context.startActivity(intent)
                                }
                            },
                            onError = { err -> Toast.makeText(context, err, Toast.LENGTH_LONG).show() }
                        )
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }
}