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
 * Inline-friendly attachment renderer:
 *  - Images: inline bubble with correct aspect ratio & rounded corners
 *  - Videos: inline bubble with thumbnail (frame extracted via coil-video), play overlay
 *  - Other files: compact row with icon + filename
 *
 * Long-press selection:
 *  - Parent bubble handles long-press via combinedClickable in ChatScreen.
 *  - We disable inner clickable when inSelection=true so long-press works.
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
    val kind = detectAttachmentKind(contentType, fileName ?: message.text)
    val label = fileName ?: (message.text ?: "Attachment")

    when (kind) {
        AttachmentKind.Image -> InlineImageBubble(
            url = message.mediaUrl.orEmpty(),
            enabled = !inSelection,
            onOpen = onOpen
        )
        AttachmentKind.Video -> InlineVideoBubble(
            videoUrl = message.mediaUrl.orEmpty(),
            enabled = !inSelection,
            onOpen = onOpen
        )
        else -> FileRow(
            name = label,
            secondary = sizeBytes?.let { prettyBytes(it) },
            onClick = onOpen
        )
    }
}

/* -------------------- Inline Image -------------------- */

@Composable
private fun InlineImageBubble(
    url: String,
    enabled: Boolean,
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
                .data(url)
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
                ) { CircularProgressIndicator() }
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
                ) { Text("Failed to load", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            },
            modifier = Modifier
                .width(maxBubbleWidth)
                .height(targetHeight)
        )
    }
}

/* -------------------- Inline Video (thumbnail via custom ImageLoader + VideoFrameDecoder) -------------------- */

@Composable
private fun InlineVideoBubble(
    videoUrl: String,
    enabled: Boolean,
    cornerRadiusDp: Int = 14,
    onOpen: () -> Unit
) {
    val context = LocalContext.current
    // Build an ImageLoader that can decode video frames.
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
                .data(videoUrl)
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
                ) { CircularProgressIndicator() }
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
                ) { Text("No preview", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            },
            modifier = Modifier
                .width(maxBubbleWidth)
                .height(targetHeight)
        )

        // Play button overlay
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

/* -------------------- File Row (pdf/doc/xlsx/etc.) -------------------- */

@Composable
private fun FileRow(
    name: String,
    secondary: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icons.Filled.InsertDriveFile, contentDescription = null)
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
                    text = secondary,
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
