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

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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
                currentUser == null -> startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                !currentUser.isEmailVerified -> {
                    Toast.makeText(this@MainActivity,
                        "Please verify your email before logging in.",
                        Toast.LENGTH_LONG
                    ).show()
                    FirebaseAuth.getInstance().signOut()
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                }
                else -> startActivity(Intent(this@MainActivity, HomeActivity::class.java))
            }
            finish()
        }
    }
}
