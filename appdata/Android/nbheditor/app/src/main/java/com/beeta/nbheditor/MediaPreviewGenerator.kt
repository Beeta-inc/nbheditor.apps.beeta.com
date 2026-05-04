package com.beeta.nbheditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object MediaPreviewGenerator {

    private const val PREVIEW_WIDTH = 400
    private const val PREVIEW_HEIGHT = 400
    private const val THUMBNAIL_QUALITY = 85

    /**
     * Generate preview for any media type
     */
    suspend fun generatePreview(context: Context, uri: Uri, mimeType: String?): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                when {
                    mimeType?.startsWith("image/") == true -> generateImagePreview(context, uri)
                    mimeType?.startsWith("video/") == true -> generateVideoPreview(context, uri)
                    mimeType == "application/pdf" -> generatePdfPreview(context, uri)
                    isDocumentType(mimeType) -> generateDocumentPreview(context, mimeType)
                    else -> generateGenericPreview(mimeType)
                }
            } catch (e: Exception) {
                Log.e("MediaPreview", "Error generating preview", e)
                generateGenericPreview(mimeType)
            }
        }
    }

    /**
     * Generate preview for image files
     */
    private fun generateImagePreview(context: Context, uri: Uri): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            // Calculate sample size
            options.inSampleSize = calculateInSampleSize(options, PREVIEW_WIDTH, PREVIEW_HEIGHT)
            options.inJustDecodeBounds = false

            val newInputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(newInputStream, null, options)
            newInputStream?.close()

            bitmap?.let { scaleBitmap(it, PREVIEW_WIDTH, PREVIEW_HEIGHT) }
        } catch (e: Exception) {
            Log.e("MediaPreview", "Error generating image preview", e)
            null
        }
    }

    /**
     * Generate preview for video files - extracts first frame
     */
    private fun generateVideoPreview(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            
            // Try to get frame at 1 second, fallback to first frame
            var bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            
            if (bitmap == null) {
                bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
            }

            bitmap?.let { 
                val scaled = scaleBitmap(it, PREVIEW_WIDTH, PREVIEW_HEIGHT)
                // Add play icon overlay
                addPlayIconOverlay(scaled)
            }
        } catch (e: Exception) {
            Log.e("MediaPreview", "Error generating video preview", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.e("MediaPreview", "Error releasing retriever", e)
            }
        }
    }

    /**
     * Generate preview for PDF files - renders first page
     */
    private fun generatePdfPreview(context: Context, uri: Uri): Bitmap? {
        var fileDescriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        
        return try {
            fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            if (fileDescriptor == null) return null

            renderer = PdfRenderer(fileDescriptor)
            if (renderer.pageCount == 0) return null

            val page = renderer.openPage(0)
            
            // Calculate dimensions maintaining aspect ratio
            val aspectRatio = page.width.toFloat() / page.height.toFloat()
            val width: Int
            val height: Int
            
            if (aspectRatio > 1) {
                width = PREVIEW_WIDTH
                height = (PREVIEW_WIDTH / aspectRatio).toInt()
            } else {
                height = PREVIEW_HEIGHT
                width = (PREVIEW_HEIGHT * aspectRatio).toInt()
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            bitmap
        } catch (e: Exception) {
            Log.e("MediaPreview", "Error generating PDF preview", e)
            null
        } finally {
            try {
                renderer?.close()
                fileDescriptor?.close()
            } catch (e: Exception) {
                Log.e("MediaPreview", "Error closing PDF resources", e)
            }
        }
    }

    /**
     * Generate preview for document files (Word, Excel, etc.)
     */
    private fun generateDocumentPreview(context: Context, mimeType: String?): Bitmap {
        val bitmap = Bitmap.createBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Background
        canvas.drawColor(Color.parseColor("#2C2C2E"))
        
        val paint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        // Icon
        val icon = when {
            mimeType?.contains("word") == true || mimeType?.contains("document") == true -> "📄"
            mimeType?.contains("sheet") == true || mimeType?.contains("excel") == true -> "📊"
            mimeType?.contains("presentation") == true || mimeType?.contains("powerpoint") == true -> "📽️"
            mimeType?.contains("text") == true -> "📝"
            mimeType?.contains("zip") == true || mimeType?.contains("compressed") == true -> "🗜️"
            else -> "📎"
        }
        
        paint.textSize = 120f
        canvas.drawText(icon, PREVIEW_WIDTH / 2f, PREVIEW_HEIGHT / 2f + 40, paint)
        
        // File type label
        val label = when {
            mimeType?.contains("word") == true -> "Word"
            mimeType?.contains("sheet") == true -> "Excel"
            mimeType?.contains("presentation") == true -> "PowerPoint"
            mimeType?.contains("text") == true -> "Text"
            mimeType?.contains("zip") == true -> "Archive"
            else -> "Document"
        }
        
        paint.textSize = 32f
        paint.color = Color.WHITE
        canvas.drawText(label, PREVIEW_WIDTH / 2f, PREVIEW_HEIGHT / 2f + 120, paint)

        return bitmap
    }

    /**
     * Generate generic preview for unknown file types
     */
    private fun generateGenericPreview(mimeType: String?): Bitmap {
        val bitmap = Bitmap.createBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        canvas.drawColor(Color.parseColor("#3A3A3C"))
        
        val paint = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        // Generic file icon
        paint.textSize = 120f
        canvas.drawText("📁", PREVIEW_WIDTH / 2f, PREVIEW_HEIGHT / 2f + 40, paint)
        
        // File type
        paint.textSize = 28f
        paint.color = Color.WHITE
        val type = mimeType?.substringAfter("/")?.uppercase() ?: "FILE"
        canvas.drawText(type, PREVIEW_WIDTH / 2f, PREVIEW_HEIGHT / 2f + 120, paint)

        return bitmap
    }

    /**
     * Add play icon overlay for video thumbnails
     */
    private fun addPlayIconOverlay(bitmap: Bitmap): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            alpha = 200
            style = Paint.Style.FILL
        }

        // Draw semi-transparent circle
        val centerX = result.width / 2f
        val centerY = result.height / 2f
        val radius = 60f
        
        paint.color = Color.BLACK
        paint.alpha = 150
        canvas.drawCircle(centerX, centerY, radius, paint)
        
        // Draw play triangle
        paint.color = Color.WHITE
        paint.alpha = 255
        
        val path = android.graphics.Path()
        val triangleSize = 40f
        path.moveTo(centerX - triangleSize / 2, centerY - triangleSize)
        path.lineTo(centerX - triangleSize / 2, centerY + triangleSize)
        path.lineTo(centerX + triangleSize, centerY)
        path.close()
        
        canvas.drawPath(path, paint)

        return result
    }

    /**
     * Scale bitmap to fit within max dimensions while maintaining aspect ratio
     */
    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val aspectRatio = width.toFloat() / height.toFloat()
        val newWidth: Int
        val newHeight: Int

        if (width > height) {
            newWidth = maxWidth
            newHeight = (maxWidth / aspectRatio).toInt()
        } else {
            newHeight = maxHeight
            newWidth = (maxHeight * aspectRatio).toInt()
        }

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Calculate sample size for efficient bitmap loading
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * Check if mime type is a document
     */
    private fun isDocumentType(mimeType: String?): Boolean {
        return mimeType?.let {
            it.contains("word") || it.contains("document") ||
            it.contains("sheet") || it.contains("excel") ||
            it.contains("presentation") || it.contains("powerpoint") ||
            it.contains("text") || it.contains("zip") ||
            it.contains("msword") || it.contains("ms-excel") || it.contains("ms-powerpoint")
        } ?: false
    }

    /**
     * Save preview bitmap to cache
     */
    suspend fun savePreviewToCache(context: Context, bitmap: Bitmap, fileName: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "media_previews")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }

                val file = File(cacheDir, "$fileName.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
                }
                file
            } catch (e: Exception) {
                Log.e("MediaPreview", "Error saving preview to cache", e)
                null
            }
        }
    }

    /**
     * Load preview from cache
     */
    suspend fun loadPreviewFromCache(context: Context, fileName: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "media_previews")
                val file = File(cacheDir, "$fileName.jpg")
                
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.absolutePath)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("MediaPreview", "Error loading preview from cache", e)
                null
            }
        }
    }

    /**
     * Clear old previews from cache
     */
    suspend fun clearOldPreviews(context: Context, daysOld: Int = 7) {
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "media_previews")
                if (!cacheDir.exists()) return@withContext

                val cutoffTime = System.currentTimeMillis() - (daysOld * 24 * 60 * 60 * 1000L)
                
                cacheDir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoffTime) {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e("MediaPreview", "Error clearing old previews", e)
            }
        }
    }

    /**
     * Get file extension from URI
     */
    fun getFileExtension(context: Context, uri: Uri): String? {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get mime type from URI
     */
    fun getMimeType(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
    }
}
