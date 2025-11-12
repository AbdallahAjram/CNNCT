package com.example.cnnct.auth.controller

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.cnnct.homepage.view.HomeActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class CompleteProfileActivity : ComponentActivity() {

    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ProfileForm()
                }
            }
        }
    }

    @Composable
    fun ProfileForm() {
        var name by remember { mutableStateOf("") }
        var displayName by remember { mutableStateOf("") }
        var originalDisplayName by remember { mutableStateOf<String?>(null) }

        // Keep digits-only in state; render with dash in UI
        var phoneDigits by remember { mutableStateOf("") }
        var phoneLocked by remember { mutableStateOf(false) }

        val uid = auth.currentUser?.uid

        // Load existing profile data
        LaunchedEffect(uid) {
            if (!uid.isNullOrEmpty()) {
                firestore.collection("users").document(uid)
                    .get()
                    .addOnSuccessListener { doc ->
                        name = doc.getString("name") ?: ""
                        displayName = doc.getString("displayName") ?: ""
                        originalDisplayName = displayName.ifBlank { null }

                        val existingPhone = doc.getString("phoneNumber")
                        if (!existingPhone.isNullOrEmpty()) {
                            phoneDigits = existingPhone.filter { it.isDigit() }.take(8)
                            phoneLocked = true
                        }
                    }
                    .addOnFailureListener {
                        toast("Failed to load profile: ${it.localizedMessage}")
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
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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
                            enabled = !phoneLocked, // 🔒 disable if already set
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (uid.isNullOrBlank()) {
                                toast("User not logged in")
                                return@Button
                            }
                            if (name.isBlank() || displayName.isBlank()) {
                                toast("Name and display name are required")
                                return@Button
                            }
                            if (!phoneLocked && phoneDigits.length != 8) {
                                toast("Phone number must be 8 digits")
                                return@Button
                            }

                            val normalizedDisplay = displayName.trim()
                            val usernameKey = normalizedDisplay.lowercase()
                            val oldUsernameKey = originalDisplayName?.trim()?.lowercase()

                            val userRef = firestore.collection("users").document(uid)
                            val usernameRef = firestore.collection("usernames").document(usernameKey)
                            val oldUsernameRef =
                                if (!oldUsernameKey.isNullOrBlank() && oldUsernameKey != usernameKey) {
                                    firestore.collection("usernames").document(oldUsernameKey!!)
                                } else null
                            val phoneRef =
                                if (!phoneLocked) firestore.collection("phones").document(phoneDigits) else null

                            val userPatch = hashMapOf(
                                "name" to name,
                                "displayName" to normalizedDisplay,
                                "email" to (auth.currentUser?.email ?: ""),
                                "updatedAt" to FieldValue.serverTimestamp()
                            ).apply {
                                if (!phoneLocked) put("phoneNumber", phoneDigits)
                            }

                            // Atomic transaction: reserve username, (optionally) reserve phone, update user
                            firestore.runTransaction { tx ->
                                // 🔹 Username reservation / validation
                                val usernameSnap = tx.get(usernameRef)
                                if (usernameSnap.exists()) {
                                    val owner = usernameSnap.getString("uid")
                                    if (owner != uid) {
                                        throw IllegalStateException("DISPLAY_TAKEN")
                                    }
                                } else {
                                    // Only create if it doesn't exist
                                    tx.set(usernameRef, mapOf("uid" to uid))
                                }

                                // 🔹 If display name changed, remove old username reservation
                                if (oldUsernameRef != null) {
                                    val oldSnap = tx.get(oldUsernameRef)
                                    if (oldSnap.exists()) {
                                        val owner = oldSnap.getString("uid")
                                        if (owner == uid) {
                                            tx.delete(oldUsernameRef)
                                        }
                                    }
                                }

                                // 🔹 Phone reservation — only if phone not locked and doc doesn’t exist
                                if (phoneRef != null) {
                                    val phoneSnap = tx.get(phoneRef)
                                    if (phoneSnap.exists()) {
                                        val owner = phoneSnap.getString("uid")
                                        if (owner != uid) {
                                            throw IllegalStateException("PHONE_TAKEN")
                                        }
                                    } else {
                                        val email = auth.currentUser?.email ?: ""
                                        tx.set(phoneRef, mapOf("uid" to uid, "email" to email))
                                    }
                                }

                                // 🔹 Merge user profile fields (safe update)
                                tx.set(userRef, userPatch, SetOptions.merge())
                                null
                            }.addOnSuccessListener {
                                toast("Profile updated")
                                gotoHome()
                            }.addOnFailureListener { e ->
                                when (e.message) {
                                    "DISPLAY_TAKEN" -> toast("Display name already taken.")
                                    "PHONE_TAKEN" -> toast("Phone already registered.")
                                    else -> toast("Failed to save: ${e.localizedMessage}")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue")
                    }
                }
            }
        }
    }

    private fun gotoHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
