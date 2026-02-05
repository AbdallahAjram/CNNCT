package com.abdallah.cnnct.settings.view

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class BlockedSettingsActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val firestore = remember { FirebaseFirestore.getInstance() }
            val auth = remember { FirebaseAuth.getInstance() }

            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text("Blocked Accounts") },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { padding ->
                BlockedSettingsScreen(
                    firestore = firestore,
                    auth = auth,
                    contentPadding = padding
                )
            }
        }
    }
}
