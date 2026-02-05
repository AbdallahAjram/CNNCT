package com.abdallah.cnnct.settings.view

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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.abdallah.cnnct.settings.model.UserProfile
import com.abdallah.cnnct.settings.viewmodel.AccountViewModel
import kotlinx.coroutines.launch
import com.abdallah.cnnct.R

// Palette
private val Lavender = Color(0xFFF1EAF5)
private val FieldGray = Color(0xFFD1D5DB)
private val TextBlack = Color(0xFF111827)
private val AccentGreen = Color(0xFF34C799)
private val FooterGray = Color(0xFF6B7280)

/* ------------------------ Wrapper (Stateful) ------------------------ */

@Composable
fun AccountScreen(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    onLogout: () -> Unit,
    vm: AccountViewModel = viewModel(factory = AccountViewModel.Factory)
) {
    val scope = rememberCoroutineScope()
    val profileState by vm.profileFlow.collectAsState()

    // -------------------- Cropper --------------------
    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val croppedUri = result.uriContent ?: return@rememberLauncherForActivityResult
            snackbarHostState.currentSnackbarData?.dismiss()
            scope.launch {
                snackbarHostState.showSnackbar("Uploading photo…")
                vm.uploadAndSaveAvatar(
                    uri = croppedUri,
                    onSuccess = {
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar("Profile photo updated")
                        }
                    },
                    onError = { err ->
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar("Upload failed: $err")
                        }
                    }
                )
            }
        } else {
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar("Crop canceled")
            }
        }
    }

    val context = LocalContext.current
    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { pickedUri: Uri? ->
        pickedUri ?: return@rememberLauncherForActivityResult
        val options = CropImageOptions(
            cropShape = CropImageView.CropShape.OVAL,
            aspectRatioX = 1, aspectRatioY = 1, fixAspectRatio = true,
            outputCompressFormat = Bitmap.CompressFormat.JPEG,
            activityTitle = "Adjust",
            toolbarColor = ContextCompat.getColor(context, R.color.black),
            toolbarTitleColor = ContextCompat.getColor(context, android.R.color.white),
            cropMenuCropButtonTitle = "Done"
        )
        cropLauncher.launch(CropImageContractOptions(uri = pickedUri, cropImageOptions = options))
    }

    // Delete Dialog Removed (Moved to PrivacySettingsScreen)

    AccountScreenContent(
        profile = profileState,
        contentPadding = contentPadding,
        onUpdateDisplayName = { name ->
             scope.launch {
                 vm.updateDisplayName(name)
                 snackbarHostState.showSnackbar("Display name updated successfully")
             }
        },
        onUpdateAbout = { about ->
             scope.launch {
                 vm.updateAbout(about)
                 snackbarHostState.showSnackbar("About section updated successfully")
             }
        },
        onPickPhoto = {
            pickPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
    // Delete action moved to Privacy
    )
}

/* ------------------------ Content (Stateless) ------------------------ */

@Composable
fun AccountScreenContent(
    profile: UserProfile?,
    contentPadding: PaddingValues,
    onUpdateDisplayName: (String) -> Unit,
    onUpdateAbout: (String) -> Unit,
    onPickPhoto: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val nameFocusRequester = remember { FocusRequester() }
    val aboutFocusRequester = remember { FocusRequester() }

    // local editable copies
    var localProfile by remember { mutableStateOf<UserProfile?>(null) }
    var nameEditing by remember { mutableStateOf(false) }
    var aboutEditing by remember { mutableStateOf(false) }
    var nameText by remember { mutableStateOf("") }
    var aboutText by remember { mutableStateOf("") }
    var nameOriginal by remember { mutableStateOf("") }
    var aboutOriginal by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    // Sync local state when profile changes (and not editing)
    LaunchedEffect(profile) {
        profile?.let {
            localProfile = it
            if (!nameEditing) {
                nameText = it.displayName
                nameOriginal = it.displayName
            }
            if (!aboutEditing) {
                aboutText = it.about.orEmpty()
                aboutOriginal = it.about.orEmpty()
            }
        }
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
        when (val p = localProfile) {
            null -> {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator()
            }
            else -> {
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
                        .clickable { onPickPhoto() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )

                Spacer(Modifier.height(18.dp))

                // Phone
                LabeledField("Phone") { ReadOnlyBubble(text = p.phoneNumber.orEmpty()) }
                Spacer(Modifier.height(12.dp))

                // Display Name
                LabeledField("Display name") {
                    EditableBubble(
                        value = nameText,
                        onValueChange = { nameText = it },
                        isEditing = nameEditing,
                        canSave = nameText.isNotBlank() && nameText != nameOriginal,
                        onStartEdit = {
                            nameEditing = true
                            nameOriginal = nameText
                            scope.launch {
                                kotlinx.coroutines.delay(10)
                                nameFocusRequester.requestFocus()
                            }
                        },
                        onSave = {
                            nameEditing = false
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            onUpdateDisplayName(nameText)
                        },
                        onCancel = {
                            nameText = nameOriginal
                            nameEditing = false
                            keyboardController?.hide()
                            focusManager.clearFocus()
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
                        canSave = aboutText != aboutOriginal,
                        onStartEdit = {
                            aboutEditing = true
                            aboutOriginal = aboutText
                            scope.launch {
                                kotlinx.coroutines.delay(10)
                                aboutFocusRequester.requestFocus()
                            }
                        },
                        onSave = {
                            aboutEditing = false
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            onUpdateAbout(aboutText)
                        },
                        onCancel = {
                            aboutText = aboutOriginal
                            aboutEditing = false
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        },
                        focusRequester = aboutFocusRequester
                    )
                }

                Spacer(Modifier.height(32.dp))
                
                // Privacy & Security moved to Privacy Screen

                Spacer(Modifier.weight(1f))
                Text("CNNCT© 2026", color = FooterGray, fontSize = 12.sp)
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
