package com.abdallah.cnnct.chat.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ImageCompressor {

    private const val MAX_WIDTH = 1920
    private const val MAX_HEIGHT = 1920
    private const val COMPRESS_QUALITY = 80 // JPEG quality 0-100

    suspend fun compressImage(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        // 1. Decode bounds only to calculate sample size
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }

        // 2. Calculate inSampleSize
        options.inSampleSize = calculateInSampleSize(options, MAX_WIDTH, MAX_HEIGHT)
        options.inJustDecodeBounds = false

        // 3. Decode actual bitmap
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IllegalArgumentException("Failed to decode image")

        // 4. Handle Rotation (Exif)
        val rotatedBitmap = rotateBitmapIfRequired(context, bitmap, uri)

        // 5. Compress to File
        val cacheDir = context.cacheDir
        val compressedFile = File(cacheDir, "compressed_${System.currentTimeMillis()}.jpg")
        
        FileOutputStream(compressedFile).use { out ->
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESS_QUALITY, out)
        }

        // Clean up
        if (rotatedBitmap != bitmap) {
            bitmap.recycle()
        }
        // rotatedBitmap might be recycled by caller or GC, but safer to let GC handle local vars 
        // unless we reuse often. For now, we return it as file, so bitmap object is done.
        
        compressedFile
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun rotateBitmapIfRequired(context: Context, bitmap: Bitmap, uri: Uri): Bitmap {
        val input = context.contentResolver.openInputStream(uri) ?: return bitmap
        val exif = runCatching { ExifInterface(input) }.getOrNull()
        input.close()

        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotateImage(bitmap, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotateImage(bitmap, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotateImage(bitmap, 270f)
            else -> bitmap
        }
    }

    private fun rotateImage(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
