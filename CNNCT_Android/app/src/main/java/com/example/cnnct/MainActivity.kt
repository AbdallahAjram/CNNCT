package com.example.cnnct

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.cnnct.auth.view.LoginActivity


import com.example.cnnct.homepage.view.HomeActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        FirebaseApp.initializeApp(this)



        lifecycleScope.launch {
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                // Preload/Warm-up: Start fetching chats immediately in background
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        FirebaseFirestore.getInstance()
                            .collection("chats")
                            .whereArrayContains("members", user.uid)
                            .limit(20)
                            .get() // fetched and cached
                    } catch (_: Exception) {}
                }
            }

            kotlinx.coroutines.delay(2000) // Reduced from 3000 to 2000 for snappier feel
            val currentUser = FirebaseAuth.getInstance().currentUser
            when {
                currentUser == null -> {
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
                !currentUser.isEmailVerified -> {
                    Toast.makeText(this@MainActivity,
                        "Please verify your email before logging in.",
                        Toast.LENGTH_LONG
                    ).show()
                    FirebaseAuth.getInstance().signOut()
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
                else -> {
                    // Check if profile is actually complete in Firestore
                    launch(kotlinx.coroutines.Dispatchers.IO) {
                        val isProfileComplete = try {
                            val doc = FirebaseFirestore.getInstance().collection("users").document(currentUser.uid).get().await()
                            val name = doc.getString("name")
                            val dName = doc.getString("displayName")
                            !name.isNullOrBlank() && !dName.isNullOrBlank()
                        } catch (e: Exception) {
                            false 
                        }

                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (isProfileComplete) {
                                startActivity(Intent(this@MainActivity, HomeActivity::class.java))
                            } else {
                                // Redirect to LoginActivity which will route to CompleteProfile
                                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                            }
                            finish()
                        }
                    }
                    // Return here to avoid hitting the outer finish() immediately if we were synchronous, 
                    // but we launched a coroutine. 
                    // Wait, the outer finish() is at line 61. 
                    // If I launch(IO), the main thread continues and hits finish() immediately! 
                    // I must move finish() inside the branches or prevent fall-through.
                    return@launch 
                }
            }
            // finish() // Removed from here, handled in branches or after check
        }
    }
        }
