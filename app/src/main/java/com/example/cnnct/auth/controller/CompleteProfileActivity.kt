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
        var phone by remember { mutableStateOf("") }
        var phoneLocked by remember { mutableStateOf(false) } // new flag

        val uid = auth.currentUser?.uid

        // Load existing profile data
        LaunchedEffect(uid) {
            if (!uid.isNullOrEmpty()) {
                firestore.collection("users").document(uid).get()
                    .addOnSuccessListener { doc ->
                        name = doc.getString("name") ?: ""
                        displayName = doc.getString("displayName") ?: ""
                        val existingPhone = doc.getString("phoneNumber")
                        if (!existingPhone.isNullOrEmpty()) {
                            phone = existingPhone
                            phoneLocked = true
                        }
                    }
            }
        }

        fun formatPhoneInput(input: String): String {
            val digits = input.filter { it.isDigit() }.take(8)
            return if (digits.length > 2) {
                digits.substring(0, 2) + "-" + digits.substring(2)
            } else digits
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
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("Display Name") },
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
                            value = if (phoneLocked) formatPhoneInput(phone) else formatPhoneInput(phone),
                            onValueChange = { if (!phoneLocked) phone = it },
                            label = { Text("Phone (03-123456)") },
                            singleLine = true,
                            enabled = !phoneLocked, // 🔒 disable if already set
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (uid.isNullOrBlank()) {
                                Toast.makeText(
                                    this@CompleteProfileActivity,
                                    "User not logged in",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@Button
                            }

                            val normalizedDisplayName = displayName.lowercase().trim()
                            val normalizedPhone = phone.replace("-", "")

                            if (!phoneLocked && normalizedPhone.length != 8) {
                                Toast.makeText(
                                    this@CompleteProfileActivity,
                                    "Phone number must be 8 digits",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@Button
                            }

                            // Ensure displayName unique
                            firestore.collection("users")
                                .get()
                                .addOnSuccessListener { snapshot ->
                                    val displayNames = snapshot.documents
                                        .filter { it.id != uid }
                                        .mapNotNull { it.getString("displayName")?.lowercase()?.trim() }

                                    if (displayNames.contains(normalizedDisplayName)) {
                                        Toast.makeText(
                                            this@CompleteProfileActivity,
                                            "Display name already taken.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@addOnSuccessListener
                                    }

                                    val data = mutableMapOf(
                                        "name" to name,
                                        "displayName" to displayName,
                                        "email" to (auth.currentUser?.email ?: "")
                                    )

                                    if (!phoneLocked) {
                                        firestore.collection("phones").document(normalizedPhone).get()
                                            .addOnSuccessListener { doc ->
                                                if (doc.exists()) {
                                                    Toast.makeText(
                                                        this@CompleteProfileActivity,
                                                        "Phone already registered",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    return@addOnSuccessListener
                                                }

                                                data["phoneNumber"] = normalizedPhone
                                                firestore.collection("users").document(uid)
                                                    .set(data, SetOptions.merge())
                                                    .addOnSuccessListener {
                                                        firestore.collection("phones").document(normalizedPhone)
                                                            .set(mapOf("uid" to uid))
                                                        gotoHome()
                                                    }
                                            }
                                    } else {
                                        firestore.collection("users").document(uid)
                                            .set(data, SetOptions.merge())
                                            .addOnSuccessListener {
                                                gotoHome()
                                            }
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
        Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
