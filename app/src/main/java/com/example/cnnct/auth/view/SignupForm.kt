package com.example.cnnct.auth.view

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import com.example.cnnct.R

@Composable
fun SignupForm(
    onSignupClick: (name: String, displayName: String, email: String, phone: String, password: String, confirmPassword: String) -> Unit = { _, _, _, _, _, _ -> },
    onGoogleSignupClick: () -> Unit = {},
    onLoginRedirectClick: () -> Unit = {}
) {
    var name by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    // Store phone as digits only; render formatted
    var phoneDigits by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    fun formatPhoneForUi(digitsOnly: String): String {
        val digits = digitsOnly.filter { it.isDigit() }.take(8)
        return if (digits.length >= 2) {
            digits.substring(0, 2) + if (digits.length > 2) "-" + digits.substring(2) else "-"
        } else digits
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Create Account 🎉",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Sign up to join CNNCT",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Full Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Display Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Phone
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "+961",
                modifier = Modifier.padding(end = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            OutlinedTextField(
                value = formatPhoneForUi(phoneDigits),
                onValueChange = { input ->
                    // Keep digits only internally
                    phoneDigits = input.filter { it.isDigit() }.take(8)
                },
                label = { Text("Phone (03-123456)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next
            ),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "Hide" else "Show")
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text("Confirm Password") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                onSignupClick(
                    name.trim(),
                    displayName.trim(),
                    email.trim(),
                    phoneDigits, // pass normalized (digits only)
                    password,
                    confirmPassword
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text("Sign Up", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onGoogleSignupClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Image(
                painter = painterResource(id = R.drawable.google_round),
                contentDescription = "Sign up with Google",
                modifier = Modifier.height(48.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign up with Google", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Already have an account? Log in",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onLoginRedirectClick() }
        )
    }
}
