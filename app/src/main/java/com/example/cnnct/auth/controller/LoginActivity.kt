package com.example.cnnct.auth.controller

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.cnnct.R
import com.example.cnnct.auth.view.LoginForm
import com.example.cnnct.homepage.view.HomeActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

private lateinit var auth: FirebaseAuth
private val RC_SIGN_IN = 1001
private lateinit var googleSignInClient: GoogleSignInClient

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setContent {
            LoginForm(
                onLoginClick = { identifier, password ->
                    if (identifier.isBlank() || password.isBlank()) {
                        Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                        return@LoginForm
                    }

                    val looksLikePhone = identifier.matches(Regex("\\d{2}-?\\d{6}"))

                    if (looksLikePhone) {
                        // Normalize: strip dash
                        val normalizedPhone = identifier.replace("-", "")
                        FirebaseFirestore.getInstance().collection("phones").document(normalizedPhone)
                            .get()
                            .addOnSuccessListener { doc ->
                                val uid = doc.getString("uid")
                                if (uid == null) {
                                    Toast.makeText(this, "No account found with this phone", Toast.LENGTH_SHORT).show()
                                } else {
                                    FirebaseFirestore.getInstance().collection("users").document(uid).get()
                                        .addOnSuccessListener { userDoc ->
                                            val email = userDoc.getString("email")
                                            if (email.isNullOrEmpty()) {
                                                Toast.makeText(this, "No email linked to this account", Toast.LENGTH_SHORT).show()
                                                return@addOnSuccessListener
                                            }
                                            auth.signInWithEmailAndPassword(email, password)
                                                .addOnCompleteListener(this) { task ->
                                                    if (task.isSuccessful) {
                                                        val user = auth.currentUser
                                                        if (user != null && user.isEmailVerified) {
                                                            startActivity(Intent(this, HomeActivity::class.java))
                                                            finish()
                                                        } else {

                                                            // Ask if they want a new verification email
                                                            AlertDialog.Builder(this@LoginActivity)
                                                                .setTitle("Email not verified")
                                                                .setMessage("Would you like us to resend the verification email?")
                                                                .setPositiveButton("Resend") { _, _ ->
                                                                    user?.sendEmailVerification()
                                                                        ?.addOnSuccessListener {
                                                                            Toast.makeText(this, "Verification email sent again!", Toast.LENGTH_LONG).show()
                                                                        }
                                                                        ?.addOnFailureListener {
                                                                            Toast.makeText(this, "Failed to send: ${it.message}", Toast.LENGTH_LONG).show()
                                                                        }
                                                                }
                                                                .setNegativeButton("Cancel", null)
                                                                .show()
                                                        }
                                                    } else {
                                                        Toast.makeText(this, "Login failed: ${task.exception?.localizedMessage}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }

                                        }
                                }
                            }
                    } else {
                        // Email login
                        auth.signInWithEmailAndPassword(identifier, password)
                            .addOnCompleteListener(this) { task ->
                                if (task.isSuccessful) {
                                    val user = auth.currentUser
                                    if (user != null && user.isEmailVerified) {
                                        startActivity(Intent(this, HomeActivity::class.java))
                                        finish()
                                    } else {
                                        Toast.makeText(this, "Please verify your email before logging in.", Toast.LENGTH_LONG).show()
                                        // Ask if they want a new verification email
                                        AlertDialog.Builder(this@LoginActivity)
                                            .setTitle("Email not verified")
                                            .setMessage("Would you like us to resend the verification email?")
                                            .setPositiveButton("Resend") { _, _ ->
                                                user?.sendEmailVerification()
                                                    ?.addOnSuccessListener {
                                                        Toast.makeText(this, "Verification email sent again!", Toast.LENGTH_LONG).show()
                                                    }
                                                    ?.addOnFailureListener {
                                                        Toast.makeText(this, "Failed to send: ${it.message}", Toast.LENGTH_LONG).show()
                                                    }
                                            }
                                            .setNegativeButton("Cancel", null)
                                            .show()
                                    }

                                } else {
                                    Toast.makeText(this, "Login failed: ${task.exception?.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            }
                    }
                },
                onGoogleSignInClick = { signInWithGoogle() },
                onForgotPasswordClick = {
                    val emailInput = EditText(this@LoginActivity)
                    val dialog = AlertDialog.Builder(this@LoginActivity)
                        .setTitle("Reset Password")
                        .setMessage("Enter your email to receive a password reset link.")
                        .setView(emailInput)
                        .setPositiveButton("Send") { _, _ ->
                            val email = emailInput.text.toString()
                            if (email.isNotBlank()) {
                                FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                                    .addOnSuccessListener {
                                        Toast.makeText(this, "Reset email sent. Check your inbox.", Toast.LENGTH_LONG).show()
                                    }
                                    .addOnFailureListener {
                                        if (it.message?.contains("no user record") == true) {
                                            Toast.makeText(this, "This email is registered via Google Sign-In. Please use Google login.", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(this, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                            } else {
                                Toast.makeText(this, "Email cannot be empty", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .create()
                    dialog.show()
                },
                onSignUpClick = {
                    val intent = Intent(this, SignupActivity::class.java)
                    startActivity(intent)
                }
            )
        }
    }

    private fun signInWithGoogle() {
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(Exception::class.java)
                val idToken = account?.idToken
                if (idToken != null) {
                    firebaseAuthWithGoogle(idToken)
                } else {
                    Toast.makeText(this, "Google sign-in failed: No ID token", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Google sign-in failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        val userRef = FirebaseFirestore.getInstance().collection("users").document(user.uid)
                        userRef.get().addOnSuccessListener { document ->
                            val name = document.getString("name")
                            val displayName = document.getString("displayName")

                            if (!document.exists() || name.isNullOrEmpty() || displayName.isNullOrEmpty()) {
                                startActivity(Intent(this, CompleteProfileActivity::class.java))
                            } else {
                                startActivity(Intent(this, HomeActivity::class.java))
                            }
                            finish()
                        }.addOnFailureListener {
                            Toast.makeText(this, "Failed to fetch user profile", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Google sign-in failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }
}
