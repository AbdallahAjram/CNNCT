package com.example.cnnct.auth.controller

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.cnnct.R
import com.example.cnnct.auth.view.SignupForm
import com.example.cnnct.homepage.view.HomeActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class SignupActivity : ComponentActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var googleSignInClient: GoogleSignInClient

    companion object {
        private const val RC_SIGN_IN = 9001
        private const val TAG = "SignupActivity"
        private const val ERR_PHONE_TAKEN = "PHONE_TAKEN"
        private const val ERR_USERNAME_TAKEN = "USERNAME_TAKEN"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        val gSO = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gSO)

        setContent {
            SignupForm(
                onSignupClick = { name, displayName, email, phoneRaw, password, confirmPassword ->
                    val phone = normalizePhone(phoneRaw) // e.g., "03123456" (8 digits)
                    when {
                        name.isBlank() || displayName.isBlank() || email.isBlank() || phone.isBlank()
                                || password.isBlank() || confirmPassword.isBlank() -> {
                            toast("Please fill in all fields")
                        }
                        password != confirmPassword -> toast("Passwords do not match")
                        phone.length != 8 -> toast("Phone must be 8 digits (e.g., 03-123456)")
                        else -> signupUser(name.trim(), displayName.trim(), email.trim(), phone, password)
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

    private fun normalizePhone(input: String): String {
        // Keep digits only, expect 8-digit local number
        return input.filter { it.isDigit() }.take(8)
    }

    private fun normalizeDisplayName(input: String): String = input.trim().lowercase()

    private fun signupUser(
        name: String,
        displayName: String,
        email: String,
        phone: String,
        password: String
    ) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    toast("Registration failed: ${task.exception?.localizedMessage}")
                    return@addOnCompleteListener
                }

                val user = auth.currentUser
                val uid = user?.uid
                if (uid == null) {
                    toast("Registration failed: user not available")
                    return@addOnCompleteListener
                }

                // Refs
                val userRef = firestore.collection("users").document(uid)
                val phoneRef = firestore.collection("phones").document(phone) // unique by doc id
                val usernameKey = normalizeDisplayName(displayName)
                val usernameRef = firestore.collection("usernames").document(usernameKey)
                val userDoc = hashMapOf(
                    "name" to name,
                    "displayName" to displayName,
                    "email" to email,
                    "phoneNumber" to phone,
                    "photoUrl" to null,
                    "notificationsEnabled" to true,
                    "chatNotificationsEnabled" to true,
                    "callNotificationsEnabled" to true,
                    "fcmTokens" to emptyMap<String, Any>(),
                    "platform" to "android",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )


                // Transaction to ensure atomicity & uniqueness
                firestore.runTransaction { tx ->
                    // 1) Unique phone
                    if (tx.get(phoneRef).exists()) {
                        throw IllegalStateException(ERR_PHONE_TAKEN)
                    }
                    // 2) Unique username/displayName
                    if (tx.get(usernameRef).exists()) {
                        throw IllegalStateException(ERR_USERNAME_TAKEN)
                    }

                    // 3) Writes
                    tx.set(userRef, userDoc, SetOptions.merge())
                    // phones/{phone} must contain { uid, email } to satisfy rules
                    tx.set(phoneRef, mapOf("uid" to uid, "email" to email))
                    // usernames/{displayName} -> reserve
                    tx.set(usernameRef, mapOf("uid" to uid))
                    null
                }.addOnSuccessListener {
                    // Send verification email
                    user.sendEmailVerification()
                        .addOnSuccessListener {
                            toast("Verification email sent. Check your inbox.")
                            startActivity(Intent(this, LoginActivity::class.java))
                            finish()
                        }
                        .addOnFailureListener { e ->
                            toast("User created but failed to send verification: ${e.localizedMessage}")
                            startActivity(Intent(this, LoginActivity::class.java))
                            finish()
                        }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Transaction failed", e)
                    when (e.message) {
                        ERR_PHONE_TAKEN -> toast("Phone already registered")
                        ERR_USERNAME_TAKEN -> toast("Display name already taken")
                        else -> toast("Failed to save profile: ${e.localizedMessage}")
                    }
                    // Roll back auth user to keep consistent
                    auth.currentUser?.delete()?.addOnCompleteListener {
                        auth.signOut()
                    }
                }
            }
    }

    private fun signInWithGoogle() {
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    @Deprecated("onActivityResult is deprecated; fine for this capstone, or migrate later.")
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
                    toast("Google sign-in failed: No ID token")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Google sign-in failed", e)
                toast("Google sign-in failed: ${e.message}")
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (!task.isSuccessful) {
                    toast("Authentication failed: ${task.exception?.localizedMessage}")
                    return@addOnCompleteListener
                }

                val user = auth.currentUser ?: run {
                    toast("Authentication failed: user not available")
                    return@addOnCompleteListener
                }
                val userId = user.uid
                val userDocRef = firestore.collection("users").document(userId)

                userDocRef.get()
                    .addOnSuccessListener { document ->
                        if (!document.exists()) {
                            val userMap = hashMapOf(
                                "displayName" to (user.displayName ?: ""),
                                "email" to (user.email ?: ""),
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                            userDocRef.set(userMap)
                                .addOnSuccessListener {
                                    Log.d(TAG, "User doc created for Google account.")
                                    checkUserProfileAndRedirect(userId)
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "Firestore write error", e)
                                    toast("Failed to create user profile")
                                }
                        } else {
                            Log.d(TAG, "User doc exists. Checking completeness...")
                            checkUserProfileAndRedirect(userId)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Firestore fetch error", e)
                        toast("Error checking user doc")
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
                toast("Error checking profile data")
            }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
