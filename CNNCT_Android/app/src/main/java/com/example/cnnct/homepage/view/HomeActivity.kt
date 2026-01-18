package com.example.cnnct.homepage.view

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
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
            // --- runtime permission handling on entry ---
            val permissions = remember {
                mutableStateListOf<String>().apply {
                    add(Manifest.permission.RECORD_AUDIO)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            val denied = remember { mutableStateListOf<String>() }
            var checked by rememberSaveable { mutableStateOf(false) }

            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                denied.clear()
                result.forEach { (perm, granted) ->
                    if (!granted) denied.add(perm)
                }
                checked = true
            }

            LaunchedEffect(Unit) {
                val stillNeeded = permissions.filter {
                    ContextCompat.checkSelfPermission(this@HomeActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (stillNeeded.isNotEmpty()) {
                    launcher.launch(stillNeeded.toTypedArray())
                } else {
                    checked = true
                }
            }

            // UI
            if (!checked) {
                // simple loading screen while permissions dialog runs
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                // once checked, continue to home
                HomeScreen(
                    callsController = callsController,
                    onLogout = {
                        FirebaseAuth.getInstance().signOut()
                        Log.d("HomeActivity", "User logged out")
                        startActivity(Intent(this@HomeActivity, LoginActivity::class.java))
                        finish()
                    }
                )

                if (denied.isNotEmpty()) {
                    // optional gentle reminder snackbar/toast
                    LaunchedEffect(denied.joinToString()) {
                        Log.w("Permissions", "Denied: ${denied.joinToString()}")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callsController.stopIncomingWatcher()
        callsController.clear()
    }
}
