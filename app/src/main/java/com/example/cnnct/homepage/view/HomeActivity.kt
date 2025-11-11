package com.example.cnnct.homepage.view

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.cnnct.auth.controller.LoginActivity
import com.example.cnnct.calls.controller.CallsController
import com.google.firebase.auth.FirebaseAuth

class HomeActivity : ComponentActivity() {

    private lateinit var callsController: CallsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callsController = CallsController(this)
        callsController.startIncomingWatcher()
        FirebaseAuth.getInstance().currentUser?.getIdToken(true)
            ?.addOnSuccessListener { token ->
                Log.d("TOKEN", token.token ?: "")
            }
        setContent {

            HomeScreen(
                callsController = callsController,
                onLogout = {
                    FirebaseAuth.getInstance().signOut()
                    Log.d("HomeActivity", "User logged out")
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callsController.stopIncomingWatcher()
        callsController.clear()
    }
}
