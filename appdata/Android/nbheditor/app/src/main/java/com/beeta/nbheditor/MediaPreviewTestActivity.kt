package com.beeta.nbheditor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Test Activity to demonstrate MediaPreviewGenerator functionality
 * This shows how to extract and display previews for images, videos, and documents
 */
class MediaPreviewTestActivity : AppCompatActivity() {

    private lateinit var previewImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var btnSelectImage: Button
    private lateinit var btnSelectVideo: Button
    private lateinit var btnSelectDocument: Button

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                loadPreview(uri, "image/*")
            }
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                loadPreview(uri, "video/*")
            }
        }
    }

    private val documentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                val mimeType = contentResolver.getType(uri)
                loadPreview(uri, mimeType)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_preview_test)

        previewImage = findViewById(R.id.previewImage)
        statusText = findViewById(R.id.statusText)
        btnSelectImage = findViewById(R.id.btnSelectImage)
        btnSelectVideo = findViewById(R.id.btnSelectVideo)
        btnSelectDocument = findViewById(R.id.btnSelectDocument)

        btnSelectImage.setOnClickListener {
            selectImage()
        }

        btnSelectVideo.setOnClickListener {
            selectVideo()
        }

        btnSelectDocument.setOnClickListener {
            selectDocument()
        }

        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }

    private fun selectImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        imagePickerLauncher.launch(intent)
    }

    private fun selectVideo() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        videoPickerLauncher.launch(intent)
    }

    private fun selectDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            ))
        }
        documentPickerLauncher.launch(intent)
    }

    private fun loadPreview(uri: Uri, mimeType: String?) {
        statusText.text = "Generating preview..."
        previewImage.setImageDrawable(null)

        lifecycleScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                
                // Generate preview
                val preview = MediaPreviewGenerator.generatePreview(this@MediaPreviewTestActivity, uri, mimeType)
                
                val duration = System.currentTimeMillis() - startTime

                if (preview != null) {
                    previewImage.setImageBitmap(preview)
                    statusText.text = """
                        ✓ Preview generated successfully!
                        Type: ${MediaPreviewGenerator.getMimeType(this@MediaPreviewTestActivity, uri)}
                        Size: ${preview.width}x${preview.height}
                        Time: ${duration}ms
                    """.trimIndent()
                    
                    // Test caching
                    val cacheKey = uri.toString().hashCode().toString()
                    MediaPreviewGenerator.savePreviewToCache(this@MediaPreviewTestActivity, preview, cacheKey)
                    
                    Toast.makeText(this@MediaPreviewTestActivity, "Preview cached successfully", Toast.LENGTH_SHORT).show()
                } else {
                    statusText.text = "✗ Failed to generate preview"
                    Toast.makeText(this@MediaPreviewTestActivity, "Preview generation failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                statusText.text = "✗ Error: ${e.message}"
                Toast.makeText(this@MediaPreviewTestActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                android.util.Log.e("MediaPreviewTest", "Error generating preview", e)
            }
        }
    }
}
