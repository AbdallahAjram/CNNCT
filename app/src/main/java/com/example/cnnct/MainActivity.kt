package com.example.cnnct

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.cnnct.auth.controller.LoginActivity
import com.example.cnnct.homepage.controller.HomePController
import com.example.cnnct.homepage.controller.PreloadedChatsCache
import com.example.cnnct.homepage.view.HomeActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        FirebaseApp.initializeApp(this)

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            HomePController.getUserChats { chats ->
                PreloadedChatsCache.chatSummaries = chats
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val user = FirebaseAuth.getInstance().currentUser
            when {
                user == null -> startActivity(Intent(this, LoginActivity::class.java))
                !user.isEmailVerified -> {
                    Toast.makeText(this,
                        "Please verify your email before logging in.",
                        Toast.LENGTH_LONG
                    ).show()
                    FirebaseAuth.getInstance().signOut()
                    startActivity(Intent(this, LoginActivity::class.java))
                }
                else -> startActivity(Intent(this, HomeActivity::class.java))
            }
            finish()
        }, 3000)
    }
}
