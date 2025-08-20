package com.example.cnnct.auth.controller

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.cnnct.homepage.view.HomeActivity
import com.example.cnnct.R
import com.example.cnnct.auth.view.SignupForm
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

class SignupActivity : ComponentActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var googleSignInClient: GoogleSignInClient

    companion object {
        private const val RC_SIGN_IN = 9001
        private const val TAG = "SignupActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setContent {
            SignupForm(
                onSignupClick = { name, displayName, email, phone, password, confirmPassword ->
                    if (name.isBlank() || displayName.isBlank() || email.isBlank() || phone.isBlank() ||
                        password.isBlank() || confirmPassword.isBlank()
                    ) {
                        Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                    } else if (password != confirmPassword) {
                        Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                    } else {
                        signupUser(name, displayName, email, phone, password)
                    }
                },
                onGoogleSignupClick = { signInWithGoogle() },
                onLoginRedirectClick = {
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
            )
        }
    }

    private fun signupUser(name: String, displayName: String, email: String, phone: String, password: String) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    val uid = user?.uid ?: return@addOnCompleteListener

                    // Ensure phone unique
                    firestore.collection("phones").document(phone).get()
                        .addOnSuccessListener { doc ->
                            if (doc.exists()) {
                                Toast.makeText(this, "Phone already registered", Toast.LENGTH_SHORT).show()
                                user?.delete()
                                return@addOnSuccessListener
                            }

                            // Check displayName uniqueness (as before)...
                            firestore.collection("users")
                                .get()
                                .addOnSuccessListener { querySnapshot ->
                                    val displayNames = querySnapshot.documents.mapNotNull {
                                        it.getString("displayName")?.lowercase()?.trim()
                                    }

                                    if (displayNames.contains(displayName.lowercase().trim())) {
                                        Toast.makeText(this, "Display name already taken.", Toast.LENGTH_SHORT).show()
                                        user?.delete()
                                        return@addOnSuccessListener
                                    }

                                    val userMap = hashMapOf(
                                        "name" to name,
                                        "displayName" to displayName,
                                        "email" to email,
                                        "phoneNumber" to phone
                                    )

                                    firestore.collection("users").document(uid)
                                        .set(userMap)
                                        .addOnSuccessListener {
                                            firestore.collection("phones").document(phone)
                                                .set(mapOf("uid" to uid))

                                            user.sendEmailVerification()
                                                .addOnCompleteListener { emailTask ->
                                                    if (emailTask.isSuccessful) {
                                                        Toast.makeText(this, "Verification email sent. Check inbox.", Toast.LENGTH_LONG).show()
                                                        startActivity(Intent(this, LoginActivity::class.java))
                                                        finish()
                                                    }
                                                }
                                        }
                                }
                        }
                } else {
                    Toast.makeText(this, "Registration failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
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
                account?.idToken?.let { firebaseAuthWithGoogle(it) }
                    ?: Toast.makeText(this, "Google sign-in failed: No ID token", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "Google sign-in failed", e)
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
                    user?.let {
                        val userId = it.uid
                        val userDocRef = firestore.collection("users").document(userId)

                        userDocRef.get()
                            .addOnSuccessListener { document ->
                                if (!document.exists()) {
                                    // Create basic user document
                                    val userMap = hashMapOf(
                                        "displayName" to (it.displayName ?: ""),
                                        "email" to (it.email ?: "")
                                    )
                                    userDocRef.set(userMap)
                                        .addOnSuccessListener {
                                            Log.d(TAG, "User doc created. Checking completeness...")
                                            checkUserProfileAndRedirect(userId)
                                        }
                                        .addOnFailureListener { e ->
                                            Toast.makeText(this, "Failed to create user doc", Toast.LENGTH_SHORT).show()
                                            Log.e(TAG, "Firestore write error", e)
                                        }
                                } else {
                                    Log.d(TAG, "User doc exists. Checking completeness...")
                                    checkUserProfileAndRedirect(userId)
                                }
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(this, "Error checking user doc", Toast.LENGTH_SHORT).show()
                                Log.e(TAG, "Firestore fetch error", e)
                            }
                    }
                } else {
                    Toast.makeText(this, "Authentication failed: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }



    private fun checkUserProfileAndRedirect(uid: String) {
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { document ->
                val name = document.getString("name")
                val displayName = document.getString("displayName")

                if (name.isNullOrBlank() || displayName.isNullOrBlank()) {
                    startActivity(Intent(this, CompleteProfileActivity::class.java))
                } else {
                    startActivity(Intent(this, HomeActivity::class.java))
                }

                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error checking profile data", Toast.LENGTH_SHORT).show()
            }
    }

}
