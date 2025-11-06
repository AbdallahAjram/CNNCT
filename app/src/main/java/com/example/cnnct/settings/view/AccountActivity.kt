package com.example.cnnct.settings.view

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.remember
import com.example.cnnct.homepage.view.BottomNavigationBar
import com.example.cnnct.settings.controller.AccountController
import com.example.cnnct.settings.model.AccountRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class AccountActivity : ComponentActivity() {
    private val repo by lazy {
        AccountRepository(
            auth = FirebaseAuth.getInstance(),
            db = FirebaseFirestore.getInstance()
        )
    }
    private val controller by lazy { AccountController(repo) }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val snackbarHostState = remember { SnackbarHostState() }

            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text("Profile") },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = { Icon(Icons.Default.Person, contentDescription = "Profile Icon") }
                    )
                },
                bottomBar = { BottomNavigationBar(currentScreen = "settings") },
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { padding ->
                AccountScreenContent(
                    contentPadding = padding,
                    controller = controller,
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }
}
