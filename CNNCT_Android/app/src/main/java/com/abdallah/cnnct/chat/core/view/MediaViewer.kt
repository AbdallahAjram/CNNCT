package com.cnnct.chat.mvc.view

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.cnnct.chat.player.MediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL

@Composable
fun MediaViewerDialog(
    url: String,
    fileName: String?,           // used to detect kind (image/video/pdf/etc.)
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val kind = remember(fileName) { detectAttachmentKind(contentType = null, fileName = fileName) }
    var saving by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                    FilledTonalIconButton(
                        enabled = !saving,
                        onClick = {
                            if (!saving) {
                                saving = true
                                scope.launch(Dispatchers.IO) {
                                    val result = saveToGallery(ctx, url, fileName)
                                    withContext(Dispatchers.Main) {
                                        saving = false
                                        val msg = when (result) {
                                            SaveResult.GALLERY -> "Saved to Gallery"
                                            SaveResult.DOWNLOADS -> "Saved to Downloads"
                                            SaveResult.ERROR -> "Failed to save"
                                        }
                                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = "Save")
                    }
                }

                when (kind) {
                    AttachmentKind.Image -> ZoomableImage(url)
                    AttachmentKind.Video -> CachedVideoViewer(url)
                    else -> FileViewerPlaceholder()
                }
            }
        }
    }
}

private enum class SaveResult { GALLERY, DOWNLOADS, ERROR }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(url: String) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 6f)
        val scaleRatio = if (scale == 0f) 1f else newScale / scale
        scale = newScale
        offsetX = (offsetX * scaleRatio + panChange.x).coerceIn(-2000f, 2000f)
        offsetY = (offsetY * scaleRatio + panChange.y).coerceIn(-2000f, 2000f)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
            contentDescription = null,
            loading = { CircularProgressIndicator(color = Color.White) },
            error = { Text("Failed to load image", color = Color.White) },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
                .transformable(transformState)
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
@Composable
private fun CachedVideoViewer(url: String) {
    val ctx = LocalContext.current
    val mediaSourceFactory = remember {
        DefaultMediaSourceFactory(MediaCache.dataSourceFactory(ctx))
    }
    val exoPlayer = remember(url) {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = false
            }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                player = exoPlayer
                layoutParams = android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShowFastForwardButton(false)
                setShowRewindButton(false)
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    )
}

@Composable
private fun FileViewerPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Preview not supported. Use Download.", color = Color.White)
    }
}

/* ---------- Save to Gallery (MediaStore) ---------- */

private fun saveToGallery(context: Context, url: String, fileName: String?): SaveResult {
    // Infer mime/type from file name (fallback to jpg/mp4)
    val lower = (fileName ?: Uri.parse(url).lastPathSegment ?: "file").lowercase()
    val ext = lower.substringAfterLast('.', "")
    val detectedMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)

    val isVideo = detectedMime?.startsWith("video") == true || ext in listOf("mp4", "mov", "m4v", "3gp")
    val isImage = detectedMime?.startsWith("image") == true || ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")

    // If it's not a supported media type for MediaStore (e.g. PDF, DOC), fallback to DownloadManager
    if (!isVideo && !isImage) {
        return if (enqueueDownload(context, url, fileName)) {
            SaveResult.DOWNLOADS
        } else {
            SaveResult.ERROR
        }
    }

    val mime = detectedMime ?: if (isVideo) "video/mp4" else "image/jpeg"

    val (collection, relPath) = if (isVideo) {
        (MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/CNNCT")
    } else {
        (MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/CNNCT")
    }

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, if (ext.isNotBlank()) lower else "$lower.${if (isVideo) "mp4" else "jpg"}")
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    return try {
        val resolver = context.contentResolver
        val itemUri = resolver.insert(collection, values) ?: return SaveResult.ERROR

        resolver.openOutputStream(itemUri)?.use { out ->
            URL(url).openStream().use { input -> input.copyTo(out) }
        } ?: return SaveResult.ERROR

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val v2 = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(itemUri, v2, null, null)
        }
        SaveResult.GALLERY
    } catch (e: Exception) {
        // Cleanup on failure (specifically catch IllegalArgumentException from bad inserts)
        e.printStackTrace()
        SaveResult.ERROR
    }
}

/* ---------- (Old) DownloadManager helper — kept if you still want Downloads folder ---------- */
@Suppress("unused")
private fun enqueueDownload(context: Context, url: String, fileName: String?): Boolean {
    return try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(url)
        val rawName = (fileName?.takeIf { it.isNotBlank() }) ?: uri.lastPathSegment ?: "download"
        // Sanitize: allow alphanumeric, dot, underscore, dash. Replace others with underscore.
        val cleanName = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        
        val req = DownloadManager.Request(uri)
            .setTitle(cleanName)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, cleanName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverRoaming(true)
        dm.enqueue(req)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
