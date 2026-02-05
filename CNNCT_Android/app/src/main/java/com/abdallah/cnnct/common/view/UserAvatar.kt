package com.abdallah.cnnct.common.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.abdallah.cnnct.R

/**
 * Reusable avatar that handles:
 * - photoUrl (if present)
 * - fallback to drawable/defaultpp
 * - circular mask
 *
 * Usage:
 *   UserAvatar(photoUrl = user.photoUrl)
 *   UserAvatar(userId = senderId, loader = { uid -> repo.observePhotoUrl(uid) })
 *
 * If you have a loader, prefer the second overload below.
 */
@Composable
fun UserAvatar(
    photoUrl: String?,
    size: Dp = 40.dp,
    contentDescription: String? = "Avatar",
    fallbackRes: Int = R.drawable.defaultpp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val model = remember(photoUrl) {
        if (photoUrl.isNullOrBlank()) null
        else ImageRequest.Builder(context)
            .data(photoUrl)
            .crossfade(true)
            .build()
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFFD1D5DB)),
        contentAlignment = Alignment.Center
    ) {
        if (model == null) {
            Image(
                painter = painterResource(fallbackRes),
                contentDescription = contentDescription
            )
        } else {
            AsyncImage(
                model = model,
                contentDescription = contentDescription
            )
        }
    }
}
