package com.abdallah.cnnct.auth.repository

import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {


    val currentUser = auth.currentUser

    // Flow observing auth state changes
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getAuthStateFlow(): Flow<com.google.firebase.auth.FirebaseUser?> {
        val flow = MutableStateFlow(auth.currentUser)
        val listener = FirebaseAuth.AuthStateListener {  firebaseAuth ->
            flow.value = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        // Note: For a real flow we'd use callbackFlow, but this is a simple approximation for now
        // or just rely on the VM polling/checking. 
        // Better:
        return kotlinx.coroutines.flow.callbackFlow {
             val authListener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
             auth.addAuthStateListener(authListener)
             awaitClose { auth.removeAuthStateListener(authListener) }
        }
    }

    suspend fun signInWithEmail(email: String, pass: String): AuthResult = withContext(Dispatchers.IO) {
        auth.signInWithEmailAndPassword(email, pass).await()
    }

    suspend fun signUpWithEmail(name: String, displayName: String, email: String, phone: String, pass: String): AuthResult = withContext(Dispatchers.IO) {
        // 1. Create Auth User
        val result = auth.createUserWithEmailAndPassword(email, pass).await()
        val user = result.user ?: throw Exception("User creation failed")
        val uid = user.uid

        // 2. Transact to create profile & reserve unique fields
        val userRef = firestore.collection("users").document(uid)
        val phoneRef = firestore.collection("phones").document(phone)
        val usernameKey = displayName.trim().lowercase()
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

        firestore.runTransaction { tx ->
            if (tx.get(phoneRef).exists()) throw Exception("PHONE_TAKEN")
            if (tx.get(usernameRef).exists()) throw Exception("USERNAME_TAKEN")

            tx.set(userRef, userDoc, SetOptions.merge())
            tx.set(phoneRef, mapOf("uid" to uid, "email" to email))
            tx.set(usernameRef, mapOf("uid" to uid))
            null
        }.await()

        // 3. Send Verification Email
        try {
            user.sendEmailVerification().await()
        } catch (_: Exception) { 
            // Warning: Email not sent
        }

        result
    }

    suspend fun signInWithGoogle(idToken: String): Boolean = withContext(Dispatchers.IO) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = auth.signInWithCredential(credential).await()
        val user = result.user ?: throw Exception("Google Auth failed")

        // Check/Create Profile
        val userRef = firestore.collection("users").document(user.uid)
        val doc = userRef.get().await()
        
        if (!doc.exists()) {
            val userMap = hashMapOf(
                "displayName" to (user.displayName ?: ""),
                "email" to (user.email ?: ""),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            userRef.set(userMap).await()
            false // Profile newly created, likely incomplete (missing phone etc) -> return false to trigger check
        } else {
             // Profile exists, check if complete
             val name = doc.getString("name")
             val dName = doc.getString("displayName")
             !name.isNullOrBlank() && !dName.isNullOrBlank()
        }
    }
    
    suspend fun isProfileComplete(uid: String): Boolean = withContext(Dispatchers.IO) {
        val doc = firestore.collection("users").document(uid).get().await()
        val name = doc.getString("name")
        val dName = doc.getString("displayName")
        // Phone is optional? In Signup it was mandatory, but Google signin might skip it initially?
        // Logic in SignupActivity said: check name & displayName.
        !name.isNullOrBlank() && !dName.isNullOrBlank()
    }

    suspend fun updateProfile(uid: String, name: String, displayName: String, phoneDigits: String, phoneLocked: Boolean): Unit = withContext(Dispatchers.IO) {
        val normalizedDisplay = displayName.trim()
        val usernameKey = normalizedDisplay.lowercase()
        
        // We need original display name to release old reservation.
        // Fetch it first.
        val userRef = firestore.collection("users").document(uid)
        val currentDoc = userRef.get().await()
        val originalDisplayName = currentDoc.getString("displayName")
        val oldUsernameKey = originalDisplayName?.trim()?.lowercase()

        val usernameRef = firestore.collection("usernames").document(usernameKey)
        val oldUsernameRef = if (!oldUsernameKey.isNullOrBlank() && oldUsernameKey != usernameKey) {
             firestore.collection("usernames").document(oldUsernameKey)
        } else null
        
        val phoneRef = if (!phoneLocked) firestore.collection("phones").document(phoneDigits) else null

        val userPatch = hashMapOf(
             "name" to name,
             "displayName" to normalizedDisplay,
             "email" to (auth.currentUser?.email ?: ""),
             "updatedAt" to FieldValue.serverTimestamp()
        ).apply {
             if (!phoneLocked) put("phoneNumber", phoneDigits)
        }

        firestore.runTransaction { tx ->
             // Reads
             val usernameSnap = tx.get(usernameRef)
             val oldUsernameSnap = oldUsernameRef?.let { tx.get(it) }
             val phoneSnap = phoneRef?.let { tx.get(it) }

             // Validate
             if (usernameSnap.exists()) {
                 val owner = usernameSnap.getString("uid")
                 if (owner != uid) throw Exception("DISPLAY_TAKEN")
             }
             if (phoneSnap != null && phoneSnap.exists()) {
                 val owner = phoneSnap.getString("uid")
                 if (owner != uid) throw Exception("PHONE_TAKEN")
             }

             // Writes
             if (!usernameSnap.exists()) tx.set(usernameRef, mapOf("uid" to uid))
             
             if (oldUsernameSnap != null && oldUsernameSnap.exists()) {
                 val owner = oldUsernameSnap.getString("uid")
                 if (owner == uid) tx.delete(oldUsernameRef!!)
             }

             if (phoneRef != null && (phoneSnap == null || !phoneSnap.exists())) {
                 tx.set(phoneRef, mapOf("uid" to uid, "email" to (auth.currentUser?.email ?: "")))
             }

             tx.set(userRef, userPatch, SetOptions.merge())
        }.await()
    }
    
    suspend fun resetPassword(email: String) = withContext(Dispatchers.IO) {
        auth.sendPasswordResetEmail(email).await()
    }
    
    suspend fun resendVerificationEmail() = withContext(Dispatchers.IO) {
        auth.currentUser?.sendEmailVerification()?.await()
    }
    
    fun signOut() {
        auth.signOut()
    }
}
