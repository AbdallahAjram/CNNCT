package com.cnnct.chat.mvc.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.cnnct.chat.mvc.model.Message
import com.cnnct.chat.mvc.model.MessageType
import android.content.Intent // ⬅️ NEW
import android.net.Uri       // ⬅️ NEW

enum class AttachmentKind { Image, Video, Pdf, Docx, Xlsx, Other }

fun detectAttachmentKind(contentType: String?, fileName: String?): AttachmentKind {
    val name = (fileName ?: "").lowercase()
    return when {
        contentType?.startsWith("image/") == true || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp") -> AttachmentKind.Image
        contentType?.startsWith("video/") == true || name.endsWith(".mp4") || name.endsWith(".mov")
                || name.endsWith(".m4v") -> AttachmentKind.Video
        contentType == "application/pdf" || name.endsWith(".pdf") -> AttachmentKind.Pdf
        contentType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || name.endsWith(".docx") -> AttachmentKind.Docx
        contentType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" || name.endsWith(".xlsx") -> AttachmentKind.Xlsx
        else -> AttachmentKind.Other
    }
}

/**
 * Inline-friendly attachment renderer.
 */
@Composable
fun AttachmentContent(
    message: Message,
    inSelection: Boolean = false,
    fileName: String? = null,
    contentType: String? = null,
    sizeBytes: Long? = null,
    onOpen: () -> Unit = {}
) {
    // ⬇️ NEW: Location first
    if (message.type == MessageType.location && message.location != null) {
        LocationMessageBubble(
            lat = message.location.latitude,
            lng = message.location.longitude,
            address = message.text
        )
        return
    }

    val kind = detectAttachmentKind(contentType, fileName ?: message.text)
    val label = fileName ?: (message.text ?: "Attachment")
    
    // Check for progress
    // We import MessageStatus? No, it's in model package. 
    // Assuming imports are available or I assume Message contains status.
    // I need to import MessageStatus if not imported.
    // It is imported in Models.kt package but file doesn't import it relative.
    // I'll check imports.
    
    val progress = if (message.status == com.cnnct.chat.mvc.model.MessageStatus.Sending) message.uploadProgress else null

    when (kind) {
        AttachmentKind.Image -> InlineImageBubble(
            url = message.mediaUrl.orEmpty(),
            enabled = !inSelection && progress == null,
            progress = progress,
            onOpen = onOpen
        )
        AttachmentKind.Video -> InlineVideoBubble(
            videoUrl = message.mediaUrl.orEmpty(),
            thumbnailUrl = message.thumbnailUrl,
            enabled = !inSelection && progress == null,
            progress = progress,
            onOpen = onOpen
        )
        else -> FileRow(
            name = label,
            secondary = sizeBytes?.let { prettyBytes(it) },
            progress = progress,
            onClick = onOpen
        )
    }
}

/* -------------------- Inline Image -------------------- */

@Composable
private fun InlineImageBubble(
    url: String,
    enabled: Boolean,
    progress: Float? = null,
    cornerRadiusDp: Int = 14,
    onOpen: () -> Unit
) {
    val maxBubbleWidth = LocalConfiguration.current.screenWidthDp.dp * 0.78f
    var targetHeight by remember { mutableStateOf(180.dp) }

    Box(
        modifier = Modifier
            .width(maxBubbleWidth)
            .heightIn(min = 140.dp, max = 320.dp)
            .clip(RoundedCornerShape(cornerRadiusDp.dp))
            .then(
                if (enabled) Modifier.clickable { onOpen() } else Modifier
            )
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(url.ifEmpty { null }) // Handle empty URL for pending
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            loading = {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) { 
                    // Don't show generic loading if we have specific upload progress
                    if (progress == null) CircularProgressIndicator() 
                }
            },
            success = { state ->
                val dw = state.result.drawable.intrinsicWidth.takeIf { it > 0 } ?: 16
                val dh = state.result.drawable.intrinsicHeight.takeIf { it > 0 } ?: 9
                val aspect = dw.toFloat() / dh.toFloat()
                val computed = (maxBubbleWidth / aspect).coerceIn(140.dp, 320.dp)
                if (computed != targetHeight) targetHeight = computed

                SubcomposeAsyncImageContent(
                    modifier = Modifier
                        .width(maxBubbleWidth)
                        .height(targetHeight)
                )
            },
            error = {
                Box(
                    modifier = Modifier
                        .width(maxBubbleWidth)
                        .height(targetHeight)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) { 
                    if (progress != null) {
                         // Placeholder for uploading image (maybe implicit from file URI if supported by Coil)
                         // For now just gray box + progress
                    } else {
                        Text("Failed to load", color = MaterialTheme.colorScheme.onSurfaceVariant) 
                    }
                }
            },
            modifier = Modifier
                .width(maxBubbleWidth)
                .height(targetHeight)
        )
        
        if (progress != null) {
             Box(
                 modifier = Modifier
                     .matchParentSize()
                     .background(Color.Black.copy(alpha = 0.4f)),
                 contentAlignment = Alignment.Center
             ) {
                 CircularProgressIndicator(progress = progress, color = Color.White)
             }
        }
    }
}

/* -------------------- Inline Video -------------------- */

@Composable
private fun InlineVideoBubble(
    videoUrl: String,
    thumbnailUrl: String?,
    enabled: Boolean,
    progress: Float? = null,
    cornerRadiusDp: Int = 14,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }

    val maxBubbleWidth = LocalConfiguration.current.screenWidthDp.dp * 0.78f
    var targetHeight by remember { mutableStateOf(180.dp) }

    Box(
        modifier = Modifier
            .width(maxBubbleWidth)
            .heightIn(min = 140.dp, max = 320.dp)
            .clip(RoundedCornerShape(cornerRadiusDp.dp))
            .then(
                if (enabled) Modifier.clickable { onOpen() } else Modifier
            )
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(thumbnailUrl ?: videoUrl.ifEmpty { null })
                .crossfade(true)
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            loading = {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) { if (progress == null) CircularProgressIndicator() }
            },
            success = { state ->
                val dw = state.result.drawable.intrinsicWidth.takeIf { it > 0 } ?: 16
                val dh = state.result.drawable.intrinsicHeight.takeIf { it > 0 } ?: 9
                val aspect = dw.toFloat() / dh.toFloat()
                val computed = (maxBubbleWidth / aspect).coerceIn(140.dp, 320.dp)
                if (computed != targetHeight) targetHeight = computed

                SubcomposeAsyncImageContent(
                    modifier = Modifier
                        .width(maxBubbleWidth)
                        .height(targetHeight)
                )
            },
            error = {
                Box(
                    modifier = Modifier
                        .width(maxBubbleWidth)
                        .height(targetHeight)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) { 
                    if (progress == null) Text("No preview", color = MaterialTheme.colorScheme.onSurfaceVariant) 
                }
            },
            modifier = Modifier
                .width(maxBubbleWidth)
                .height(targetHeight)
        )

        if (progress != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(progress = progress, color = Color.White)
            }
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
            }
        }
    }
}

/* -------------------- File Row -------------------- */

@Composable
private fun FileRow(
    name: String,
    secondary: String?,
    progress: Float? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = progress == null) { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (progress != null) {
            CircularProgressIndicator(
                progress = progress, 
                modifier = Modifier.size(24.dp),
                strokeWidth = 3.dp
            )
        } else {
            Icon(imageVector = Icons.Filled.InsertDriveFile, contentDescription = null)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (secondary != null) {
                Text(
                    text = if (progress != null) "Uploading..." else secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun prettyBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.2f MB", bytes / mb)
        bytes >= kb -> String.format("%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
/* -------------------- NEW: Location bubble -------------------- */

@Composable
private fun LocationMessageBubble(
    lat: Double,
    lng: Double,
    address: String?
) {
    val context = LocalContext.current
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.78f

    Column(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp)
    ) {
        Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!address.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(2.dp))
        Text(String.format("%.5f, %.5f", lat, lng), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable {
                    // Prefer Google Maps app via geo:, fallback to web
                    val geo = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
                    val geoIntent = Intent(Intent.ACTION_VIEW, geo).apply {
                        `package` = "com.google.android.apps.maps"
                    }
                    try {
                        context.startActivity(geoIntent)
                    } catch (_: Exception) {
                        val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
                        context.startActivity(Intent(Intent.ACTION_VIEW, web))
                    }
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Open in Maps", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
