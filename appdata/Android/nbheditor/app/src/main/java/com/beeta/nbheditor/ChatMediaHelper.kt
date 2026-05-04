package com.beeta.nbheditor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.ImageView
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object ChatMediaHelper {

    /**
     * Load and display media preview in chat message
     */
    fun loadMediaPreview(
        context: Context,
        imageView: ImageView,
        mediaUri: String,
        mimeType: String?,
        lifecycleScope: LifecycleCoroutineScope
    ) {
        lifecycleScope.launch {
            try {
                val uri = Uri.parse(mediaUri)
                val cacheKey = generateCacheKey(mediaUri)
                
                // Try to load from cache first
                var preview = MediaPreviewGenerator.loadPreviewFromCache(context, cacheKey)
                
                if (preview == null) {
                    // Generate new preview
                    preview = MediaPreviewGenerator.generatePreview(context, uri, mimeType)
                    
                    // Save to cache
                    preview?.let {
                        MediaPreviewGenerator.savePreviewToCache(context, it, cacheKey)
                    }
                }
                
                // Display preview
                withContext(Dispatchers.Main) {
                    if (preview != null) {
                        imageView.setImageBitmap(preview)
                        imageView.visibility = android.view.View.VISIBLE
                    } else {
                        imageView.visibility = android.view.View.GONE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatMedia", "Error loading media preview", e)
                withContext(Dispatchers.Main) {
                    imageView.visibility = android.view.View.GONE
                }
            }
        }
    }

    /**
     * Load media preview from Firebase Storage URL
     */
    fun loadMediaPreviewFromUrl(
        context: Context,
        imageView: ImageView,
        mediaUrl: String,
        mimeType: String?,
        lifecycleScope: LifecycleCoroutineScope
    ) {
        lifecycleScope.launch {
            try {
                val cacheKey = generateCacheKey(mediaUrl)
                
                // Try to load from cache first
                var preview = MediaPreviewGenerator.loadPreviewFromCache(context, cacheKey)
                
                if (preview == null) {
                    // Download and generate preview
                    preview = downloadAndGeneratePreview(context, mediaUrl, mimeType)
                    
                    // Save to cache
                    preview?.let {
                        MediaPreviewGenerator.savePreviewToCache(context, it, cacheKey)
                    }
                }
                
                // Display preview
                withContext(Dispatchers.Main) {
                    if (preview != null) {
                        imageView.setImageBitmap(preview)
                        imageView.visibility = android.view.View.VISIBLE
                    } else {
                        imageView.visibility = android.view.View.GONE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatMedia", "Error loading media preview from URL", e)
                withContext(Dispatchers.Main) {
                    imageView.visibility = android.view.View.GONE
                }
            }
        }
    }

    /**
     * Download media from URL and generate preview
     */
    private suspend fun downloadAndGeneratePreview(
        context: Context,
        url: String,
        mimeType: String?
    ): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                // Download to temp file
                val tempFile = java.io.File(context.cacheDir, "temp_media_${System.currentTimeMillis()}")
                val connection = java.net.URL(url).openConnection()
                connection.connect()
                
                connection.getInputStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                // Generate preview from temp file
                val uri = Uri.fromFile(tempFile)
                val preview = MediaPreviewGenerator.generatePreview(context, uri, mimeType)
                
                // Clean up temp file
                tempFile.delete()
                
                preview
            } catch (e: Exception) {
                android.util.Log.e("ChatMedia", "Error downloading media", e)
                null
            }
        }
    }

    /**
     * Generate cache key from media URI/URL
     */
    private fun generateCacheKey(mediaPath: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(mediaPath.toByteArray())
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            mediaPath.hashCode().toString()
        }
    }

    /**
     * Determine if media should show preview
     */
    fun shouldShowPreview(mimeType: String?): Boolean {
        return mimeType?.let {
            it.startsWith("image/") ||
            it.startsWith("video/") ||
            it == "application/pdf" ||
            it.contains("document") ||
            it.contains("sheet") ||
            it.contains("presentation")
        } ?: false
    }

    /**
     * Get media icon for attachment card
     */
    fun getMediaIcon(mimeType: String?): String {
        return when {
            mimeType?.startsWith("image/") == true -> "🖼️"
            mimeType?.startsWith("video/") == true -> "🎥"
            mimeType?.startsWith("audio/") == true -> "🎵"
            mimeType == "application/pdf" -> "📄"
            mimeType?.contains("word") == true || mimeType?.contains("document") == true -> "📝"
            mimeType?.contains("sheet") == true || mimeType?.contains("excel") == true -> "📊"
            mimeType?.contains("presentation") == true || mimeType?.contains("powerpoint") == true -> "📽️"
            mimeType?.contains("zip") == true || mimeType?.contains("compressed") == true -> "🗜️"
            mimeType?.contains("text") == true -> "📃"
            else -> "📎"
        }
    }

    /**
     * Get human-readable file type name
     */
    fun getFileTypeName(mimeType: String?): String {
        return when {
            mimeType?.startsWith("image/") == true -> "Image"
            mimeType?.startsWith("video/") == true -> "Video"
            mimeType?.startsWith("audio/") == true -> "Audio"
            mimeType == "application/pdf" -> "PDF Document"
            mimeType?.contains("word") == true -> "Word Document"
            mimeType?.contains("sheet") == true -> "Spreadsheet"
            mimeType?.contains("presentation") == true -> "Presentation"
            mimeType?.contains("zip") == true -> "Archive"
            mimeType?.contains("text") == true -> "Text File"
            else -> "File"
        }
    }

    /**
     * Clear old cached previews
     */
    fun clearOldCache(context: Context, lifecycleScope: LifecycleCoroutineScope) {
        lifecycleScope.launch {
            MediaPreviewGenerator.clearOldPreviews(context, daysOld = 7)
        }
    }
}
