package com.example.cnnct.common.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow

/**
 * Use this when you only know the userId, not the URL.
 * Pass a loader that returns a Flow<String?> of photoUrl (e.g., repo.observePhotoUrl(uid)).
 */
@Composable
fun UserAvatar(
    userId: String,
    loader: (String) -> Flow<String?>,
    size: Dp = 40.dp,
    contentDescription: String? = "Avatar",
    modifier: Modifier = Modifier
) {
    val photoUrl by loader(userId).collectAsState(initial = null)
    UserAvatar(
        photoUrl = photoUrl,
        size = size,
        contentDescription = contentDescription,
        modifier = modifier
    )
}
