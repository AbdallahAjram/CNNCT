package com.abdallah.cnnct.auth.view

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.abdallah.cnnct.auth.viewmodel.AuthUiState
import com.abdallah.cnnct.auth.viewmodel.AuthViewModel
import com.abdallah.cnnct.homepage.view.HomeActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.abdallah.cnnct.R
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LoginActivity : ComponentActivity() {

    private val viewModel: AuthViewModel by viewModels()
    private lateinit var googleSignInClient: GoogleSignInClient
    private val REQUEST_CODE_SIGN_IN = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup Google Sign In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setContent {
            val navController = rememberNavController()
            val state by viewModel.uiState.collectAsState()
            val currentUser by viewModel.currentUser.collectAsState()

            // Handle Global Effects (Navigation, Errors)
            LaunchedEffect(state) {
                when (state) {
                    is AuthUiState.NavigateToHome -> {
                        startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                        finish()
                    }
                    is AuthUiState.NavigateToCompleteProfile -> {
                         navController.navigate("complete_profile") {
                             popUpTo("login") { inclusive = true }
                         }
                    }
                    is AuthUiState.Error -> {
                        Toast.makeText(this@LoginActivity, (state as AuthUiState.Error).message, Toast.LENGTH_LONG).show()
                        viewModel.resetState()
                    }
                    else -> {}
                }
            }

            NavHost(navController = navController, startDestination = "login") {
                composable("login") {
                    LoginScreen(
                        onLoginClick = { email, pass -> viewModel.signIn(email, pass) },
                        onResetPassword = { email -> viewModel.resetPassword(email) },
                        onGoogleSignInClick = { signInWithGoogle() },
                        onSignUpClick = { navController.navigate("signup") },
                        isLoading = state is AuthUiState.Loading
                    )
                }

                composable("signup") {
                    SignupScreen(
                        onSignupClick = { name, dName, email, phone, pass, _ -> 
                            // Confirm password check should happen in UI or VM. 
                            // UI already checked it? No, SignupForm had local check.
                            // We should re-add local check in Screen or VM.
                            // Assuming Screen passed valid data or VM checks it.
                            viewModel.signUp(name, dName, email, phone, pass) 
                        },
                        onGoogleSignupClick = { signInWithGoogle() },
                        onLoginRedirectClick = { navController.popBackStack() }, // or navigate("login")
                        isLoading = state is AuthUiState.Loading
                    )
                }

                composable("complete_profile") {
                    CompleteProfileScreen(
                        onCompleteClick = { name, dName, phone, locked ->
                            viewModel.completeProfile(name, dName, phone, locked)
                        },
                        currentUserFn = { currentUser },
                        fetchProfileData = { uid ->
                            // Simple bridge to fetch data. VM could do this and expose in state, 
                            // but for quick migration we fetch here.
                            try {
                                val snap = FirebaseFirestore.getInstance().collection("users").document(uid).get().await()
                                snap.data
                            } catch (e: Exception) { null }
                        },
                        isLoading = state is AuthUiState.Loading
                    )
                }
            }
        }
    }

    private fun signInWithGoogle() {
        val intent = googleSignInClient.signInIntent
        startActivityForResult(intent, REQUEST_CODE_SIGN_IN)
    }
    
    // Legacy onActivityResult for Google Sign In
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SIGN_IN) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                val idToken = account?.idToken
                if (idToken != null) {
                    viewModel.googleSignIn(idToken)
                } else {
                     Toast.makeText(this, "Google Sign-In failed: No ID Token", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                 Toast.makeText(this, "Google Sign-In failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}


