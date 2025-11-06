package com.example.cnnct.settings.view

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.cnnct.R
import com.example.cnnct.settings.controller.AccountController
import com.example.cnnct.settings.controller.AccountPhotoController
import com.example.cnnct.settings.model.UserProfile
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView

// Palette
private val Lavender = Color(0xFFF1EAF5)
private val FieldGray = Color(0xFFD1D5DB)
private val TextBlack = Color(0xFF111827)
private val AccentGreen = Color(0xFF34C799)
private val FooterGray = Color(0xFF6B7280)

@Composable
fun AccountScreenContent(
    contentPadding: PaddingValues,
    controller: AccountController,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    val nameFocusRequester = remember { FocusRequester() }
    val aboutFocusRequester = remember { FocusRequester() }

    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var nameEditing by remember { mutableStateOf(false) }
    var aboutEditing by remember { mutableStateOf(false) }

    var nameText by remember { mutableStateOf("") }
    var aboutText by remember { mutableStateOf("") }

    var nameOriginal by remember { mutableStateOf("") }
    var aboutOriginal by remember { mutableStateOf("") }

    /* -------------------- Cropper -------------------- */

    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        scope.launch {
            if (result.isSuccessful) {
                val croppedUri = result.uriContent ?: return@launch
                try {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar("Uploading photo…")
                    withContext(Dispatchers.IO) {
                        AccountPhotoController.uploadAndSaveAvatar(croppedUri)
                    }
                    // Refresh local profile (and UI)
                    profile = controller.getProfile()
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar("Profile photo updated")
                } catch (e: Exception) {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar("Upload failed: ${e.message}")
                }
            } else {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar("Crop canceled")
            }
        }
    }

    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { pickedUri: Uri? ->
        pickedUri ?: return@rememberLauncherForActivityResult
        val options = CropImageOptions(
            cropShape = CropImageView.CropShape.OVAL, // circular mask
            aspectRatioX = 1,
            aspectRatioY = 1,
            fixAspectRatio = true,
            guidelines = CropImageView.Guidelines.OFF,
            outputCompressFormat = Bitmap.CompressFormat.JPEG,
            outputCompressQuality = 90,
            activityTitle = "Adjust",
            toolbarColor = ContextCompat.getColor(context, R.color.black),
            toolbarTitleColor = ContextCompat.getColor(context, android.R.color.white),
            cropMenuCropButtonTitle = "Done"
        )
        cropLauncher.launch(
            CropImageContractOptions(
                uri = pickedUri,
                cropImageOptions = options
            )
        )
    }

    /* -------------------- Load profile once -------------------- */
    LaunchedEffect(Unit) {
        runCatching { controller.getProfile() }
            .onSuccess {
                profile = it
                nameText = it.displayName
                aboutText = it.about.orEmpty()
                nameOriginal = nameText
                aboutOriginal = aboutText
            }
            .onFailure { error = it.message }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Lavender)
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            loading -> {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator()
            }
            error != null -> {
                Text("Error: $error", color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val p = profile!!

                // Avatar
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(FieldGray),
                    contentAlignment = Alignment.Center
                ) {
                    val photoUrl = p.photoUrl
                    if (photoUrl.isNullOrBlank()) {
                        Image(
                            painter = painterResource(R.drawable.defaultpp),
                            contentDescription = "Profile picture",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AsyncImage(
                            model = photoUrl,
                            contentDescription = "Profile picture",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Edit photo",
                    color = AccentGreen,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            pickPhotoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )

                Spacer(Modifier.height(18.dp))

                // Phone
                LabeledField("Phone") {
                    ReadOnlyBubble(text = p.phone.orEmpty())
                }

                Spacer(Modifier.height(12.dp))

                // Display Name
                LabeledField("Display Name") {
                    EditableBubble(
                        value = nameText,
                        onValueChange = { nameText = it },
                        isEditing = nameEditing,
                        canSave = nameEditing && nameText.trim().isNotEmpty() && nameText.trim() != nameOriginal.trim(),
                        onStartEdit = {
                            nameEditing = true
                            nameFocusRequester.requestFocus()
                            keyboardController?.show()
                        },
                        onSave = {
                            val newName = nameText.trim()
                            if (newName.isEmpty() || newName == nameOriginal.trim()) {
                                nameEditing = false
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                return@EditableBubble
                            }
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar("Saving…")
                                val result = runCatching { controller.updateDisplayName(newName) }
                                snackbarHostState.currentSnackbarData?.dismiss()
                                if (result.isSuccess) {
                                    // Reflect locally right away
                                    profile = profile?.copy(displayName = newName)
                                    nameOriginal = newName
                                    snackbarHostState.showSnackbar("Display name updated")
                                } else {
                                    nameText = nameOriginal
                                    snackbarHostState.showSnackbar("Failed: ${result.exceptionOrNull()?.message}")
                                }
                            }
                            nameEditing = false
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        },
                        onCancel = {
                            nameText = nameOriginal
                            nameEditing = false
                            keyboardController?.hide()
                        },
                        focusRequester = nameFocusRequester
                    )
                }

                Spacer(Modifier.height(12.dp))

                // About
                LabeledField("About") {
                    EditableBubble(
                        value = aboutText,
                        onValueChange = { aboutText = it },
                        isEditing = aboutEditing,
                        canSave = aboutEditing && aboutText.trim() != aboutOriginal.trim(),
                        onStartEdit = {
                            aboutEditing = true
                            aboutFocusRequester.requestFocus()
                            keyboardController?.show()
                        },
                        onSave = {
                            val newAbout = aboutText.trim()
                            if (newAbout == aboutOriginal.trim()) {
                                aboutEditing = false
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                return@EditableBubble
                            }
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar("Saving…")
                                val result = runCatching { controller.updateAbout(newAbout) }
                                snackbarHostState.currentSnackbarData?.dismiss()
                                if (result.isSuccess) {
                                    profile = profile?.copy(about = newAbout)
                                    aboutOriginal = newAbout
                                    snackbarHostState.showSnackbar("About updated")
                                } else {
                                    aboutText = aboutOriginal
                                    snackbarHostState.showSnackbar("Failed: ${result.exceptionOrNull()?.message}")
                                }
                            }
                            aboutEditing = false
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        },
                        onCancel = {
                            aboutText = aboutOriginal
                            aboutEditing = false
                            keyboardController?.hide()
                        },
                        focusRequester = aboutFocusRequester
                    )
                }

                Spacer(Modifier.weight(1f))
                Text("CNNCT© 2025", color = FooterGray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TextBlack, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun ReadOnlyBubble(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FieldGray, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(text, color = TextBlack, fontSize = 16.sp)
    }
}

@Composable
private fun EditableBubble(
    value: String,
    onValueChange: (String) -> Unit,
    isEditing: Boolean,
    canSave: Boolean,
    onStartEdit: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    focusRequester: FocusRequester
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FieldGray, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 8.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = !isEditing,
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    // Only cancel if we’re editing AND we actually lost focus (tap out)
                    if (isEditing && !state.isFocused) onCancel()
                },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) onSave() else onCancel() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = FieldGray,
                unfocusedContainerColor = FieldGray,
                disabledContainerColor = FieldGray,
                focusedTextColor = TextBlack,
                unfocusedTextColor = TextBlack,
                disabledTextColor = TextBlack,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent
            )
        )
        IconButton(onClick = { if (isEditing) { if (canSave) onSave() else onCancel() } else onStartEdit() }) {
            Icon(
                imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                contentDescription = if (isEditing) "Save" else "Edit",
                tint = if (isEditing && canSave) AccentGreen else AccentGreen.copy(alpha = if (isEditing) 0.5f else 1f)
            )
        }
    }
}
