package com.abdallah.cnnct.chat.core.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object VideoThumbnailUtils {
    suspend fun generateThumbnail(context: Context, videoUri: Uri): File = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            // Retrieve frame at 0ms
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) 
                ?: throw IOException("Failed to extract video frame")
                
            val cacheFile = File(context.cacheDir, "thumb_${System.currentTimeMillis()}.jpg")
            FileOutputStream(cacheFile).use { out ->
                // Compress to JPEG 70% quality (thumbnails don't need to be perfect)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
            
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            cacheFile
        } catch (e: Exception) {
            throw IOException("Thumbnail generation failed: ${e.message}", e)
        } finally {
            retriever.release()
        }
    }
}
