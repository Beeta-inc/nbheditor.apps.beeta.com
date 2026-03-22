package com.example.nbheditor

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.nbheditor.R
import com.example.nbheditor.databinding.ActivityMainBinding
import com.example.nbheditor.databinding.FragmentEditorBinding
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var editorBinding: FragmentEditorBinding
    private lateinit var prefs: SharedPreferences
    
    private var currentFileUri: Uri? = null
    private var textChanged = false
    private var isTyping = false
    private var isDarkTheme = false
    
    private val handler = Handler(Looper.getMainLooper())
    private val typingDelayRunnable = Runnable { 
        isTyping = false
        if (textChanged) performAutoSave()
    }

    // AI related
    private var aiJob: Job? = null
    private val generativeModel = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = "AIzaSyAjO1m3x5uh5oqKt05nPRsS_H4MlgkUqN0", // Replace with your valid key
        generationConfig = generationConfig {
            temperature = 0.7f
            topK = 40
            topP = 0.95f
            maxOutputTokens = 100
        }
    )

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                openFileFromUri(uri)
            }
        }
    }

    private val saveFileAsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                currentFileUri = uri
                saveToFileWithAnimation(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.appBarMain.toolbar)

        // Inflate editor layout into content_main's root
        val contentRoot = binding.appBarMain.contentMain.root as ViewGroup
        contentRoot.removeAllViews()
        editorBinding = FragmentEditorBinding.inflate(layoutInflater, contentRoot, true)
        
        prefs = getPreferences(MODE_PRIVATE)
        
        setupEditor()
        setupAutoSave()
        detectAndApplySystemTheme()
        checkForRecovery()
        
        // Hide unused UI elements from the template
        binding.appBarMain.fab?.isVisible = false
        binding.appBarMain.contentMain.bottomNavView?.isVisible = false
        binding.appBarMain.contentMain.navHostFragmentContentMain.isVisible = false
    }

    private fun setupEditor() {
        editorBinding.textArea.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                textChanged = true
                isTyping = true
                updateLineNumbers()
                triggerAutoSaveDelay()
                triggerAISuggestions()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        editorBinding.textArea.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            editorBinding.lineNumbersScroll.scrollY = scrollY
        }
    }

    private fun triggerAISuggestions() {
        aiJob?.cancel()
        val text = editorBinding.textArea.text.toString()
        if (text.length < 5) {
            editorBinding.aiSuggestionContainer.isVisible = false
            return
        }

        aiJob = lifecycleScope.launch {
            delay(500) // Reduced delay for faster "popping out"
            try {
                if (generativeModel.apiKey.contains("YOUR_API_KEY")) return@launch

                val context = if (text.length > 500) text.substring(text.length - 500) else text
                val prompt = "Continue this text briefly. Separate 2-3 short options with | symbol. Text: $context"
                
                val response = generativeModel.generateContent(prompt)
                val suggestions = response.text?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }
                
                runOnUiThread {
                    if (!suggestions.isNullOrEmpty()) {
                        showAISuggestions(suggestions)
                    } else {
                        editorBinding.aiSuggestionContainer.isVisible = false
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                Log.e("AI_ERROR", "Check internet or API key", e)
                runOnUiThread { editorBinding.aiSuggestionContainer.isVisible = false }
            }
        }
    }

    private fun showAISuggestions(suggestions: List<String>) {
        editorBinding.aiSuggestionList.removeAllViews()
        for (suggestion in suggestions) {
            val btn = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = suggestion
                setOnClickListener { applySuggestion(suggestion) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 8
                    marginEnd = 8
                }
                isAllCaps = false
                textSize = 12f
            }
            editorBinding.aiSuggestionList.addView(btn)
        }
        editorBinding.aiSuggestionContainer.isVisible = true
    }

    private fun applySuggestion(suggestion: String) {
        val currentText = editorBinding.textArea.text
        val cursorPosition = editorBinding.textArea.selectionStart
        currentText.insert(cursorPosition, suggestion)
        editorBinding.aiSuggestionContainer.isVisible = false
    }

    private fun improveWithAI() {
        val start = editorBinding.textArea.selectionStart
        val end = editorBinding.textArea.selectionEnd
        val selectedText = if (start < end) {
            editorBinding.textArea.text.substring(start, end)
        } else {
            editorBinding.textArea.text.toString()
        }

        if (selectedText.isBlank()) {
            Toast.makeText(this, "Text is empty", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            showOverlay("AI Improving...", Color.MAGENTA)
            try {
                val response = generativeModel.generateContent("Fix and improve this text: $selectedText")
                response.text?.let { fixedText ->
                    runOnUiThread {
                        if (start < end) {
                            editorBinding.textArea.text.replace(start, end, fixedText)
                        } else {
                            editorBinding.textArea.setText(fixedText)
                        }
                        showNotification("✓ Improved with AI", Color.parseColor("#4CAF50"))
                    }
                }
            } catch (e: Exception) {
                Log.e("AI_ERROR", "Improvement failed", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "AI failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { hideOverlay() }
            }
        }
    }

    private fun updateLineNumbers() {
        val lineCount = editorBinding.textArea.lineCount.coerceAtLeast(1)
        if (editorBinding.lineNumbersVBox.childCount != lineCount) {
            editorBinding.lineNumbersVBox.removeAllViews()
            for (i in 1..lineCount) {
                val tv = TextView(this).apply {
                    text = i.toString()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    gravity = Gravity.END
                    setPadding(0, 0, 8, 0)
                    typeface = Typeface.MONOSPACE
                    textSize = 13f
                    setTextColor(if (isDarkTheme) Color.GRAY else Color.DKGRAY)
                }
                editorBinding.lineNumbersVBox.addView(tv)
            }
        }
    }

    private fun setupAutoSave() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                saveRecoveryFile()
                handler.postDelayed(this, 10000)
            }
        }, 10000)
    }

    private fun triggerAutoSaveDelay() {
        handler.removeCallbacks(typingDelayRunnable)
        handler.postDelayed(typingDelayRunnable, 2000)
    }

    private fun performAutoSave() {
        if (textChanged) {
            showAutoSaveProgress()
            currentFileUri?.let { saveToUri(it) } ?: saveRecoveryFile()
            textChanged = false
        }
    }

    private fun showAutoSaveProgress() {
        editorBinding.autoSaveContainer.apply {
            isVisible = true
            alpha = 1f
            editorBinding.autoSaveProgressBar.progress = 0
            ObjectAnimator.ofInt(editorBinding.autoSaveProgressBar, "progress", 0, 100).apply {
                duration = 800
                start()
            }
            handler.postDelayed({
                animate().alpha(0f).setDuration(500).withEndAction { isVisible = false }.start()
            }, 1800)
        }
    }

    private fun saveRecoveryFile() {
        try {
            val recoveryFile = File(filesDir, ".texteditor_recovery.txt")
            recoveryFile.writeText(editorBinding.textArea.text.toString())
            currentFileUri?.let {
                val infoFile = File(filesDir, ".texteditor_recovery_info.txt")
                infoFile.writeText(it.toString())
            }
        } catch (e: Exception) { Log.e("RECOVERY", "Failed", e) }
    }

    private fun openFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }
        openFileLauncher.launch(intent)
    }

    private fun openFileFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                val content = reader.readText()
                editorBinding.textArea.setText(content)
                currentFileUri = uri
                title = "NBH Editor - ${getFileName(uri)}"
                textChanged = false
                updateLineNumbers()
                prefs.edit { putString("lastOpenedFile", uri.toString()) }
            }
        } catch (e: Exception) { showError("Open Error", "Can't open the file", e.message ?: "") }
    }

    private fun saveFile() {
        currentFileUri?.let { saveToFileWithAnimation(it) } ?: saveFileAs()
    }

    private fun saveFileAs() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "untitled.txt")
        }
        saveFileAsLauncher.launch(intent)
    }

    private fun saveToFileWithAnimation(uri: Uri) {
        showOverlay("Saving...", Color.parseColor("#2196F3"))
        handler.postDelayed({
            if (saveToUri(uri)) {
                hideOverlay()
                showNotification("✓ Saved", Color.parseColor("#4CAF50"))
            } else {
                hideOverlay()
                showNotification("✗ Failed", Color.RED)
            }
        }, 500)
    }

    private fun saveToUri(uri: Uri): Boolean {
        return try {
            contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                outputStream.write(editorBinding.textArea.text.toString().toByteArray())
                textChanged = false
                runOnUiThread { title = "NBH Editor - ${getFileName(uri)}" }
                true
            } ?: false
        } catch (e: Exception) { false }
    }

    private fun showOverlay(text: String, color: Int) {
        editorBinding.overlayLabel.text = text
        editorBinding.overlayLabel.setTextColor(color)
        editorBinding.overlay.apply {
            isVisible = true
            alpha = 0f
            animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun hideOverlay() {
        editorBinding.overlay.animate().alpha(0f).setDuration(200).withEndAction { 
            editorBinding.overlay.isVisible = false 
        }.start()
    }

    private fun showNotification(message: String, bgColor: Int) {
        val tv = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            setBackgroundColor(bgColor)
            setPadding(50, 30, 50, 30)
            gravity = Gravity.CENTER
            elevation = 10f
        }
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 100
        }
        editorBinding.contentPane.addView(tv, params)
        tv.translationY = -200f
        tv.animate().translationY(0f).setDuration(400).setInterpolator(DecelerateInterpolator()).withEndAction {
            handler.postDelayed({
                tv.animate().alpha(0f).setDuration(500).withEndAction { 
                    editorBinding.contentPane.removeView(tv)
                }.start()
            }, 2000)
        }.start()
    }

    private fun deleteFile() {
        currentFileUri?.let { uri ->
            AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage("Delete this file?")
                .setPositiveButton("Delete") { _, _ ->
                    try {
                        android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                        showNotification("✓ Deleted", Color.parseColor("#FF5722"))
                        editorBinding.textArea.text.clear()
                        currentFileUri = null
                        title = "NBH Editor"
                        updateLineNumbers()
                    } catch (e: Exception) { showNotification("✗ Failed", Color.RED) }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } ?: Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show()
    }

    private fun detectAndApplySystemTheme() {
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isDarkTheme = nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        editorBinding.root.setBackgroundColor(if (isDarkTheme) Color.parseColor("#121212") else Color.WHITE)
        editorBinding.textArea.setTextColor(if (isDarkTheme) Color.WHITE else Color.BLACK)
        editorBinding.textArea.setHintTextColor(if (isDarkTheme) Color.GRAY else Color.LTGRAY)
        updateLineNumbers()
    }

    private fun checkForRecovery() {
        val recoveryFile = File(filesDir, ".texteditor_recovery.txt")
        if (recoveryFile.exists() && recoveryFile.length() > 0) {
            val content = recoveryFile.readText()
            AlertDialog.Builder(this)
                .setTitle("Recovery")
                .setMessage("Restore unsaved changes?")
                .setPositiveButton("Restore") { _, _ ->
                    editorBinding.textArea.setText(content)
                    updateLineNumbers()
                }
                .setNegativeButton("Discard") { _, _ -> recoveryFile.delete() }
                .show()
        } else {
            val lastUri = prefs.getString("lastOpenedFile", null)
            lastUri?.let { openFileFromUri(it.toUri()) }
        }
    }

    private fun getFileName(uri: Uri): String = uri.path?.substringAfterLast('/') ?: "unknown"

    private fun showError(title: String, message: String, details: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage("$message\n$details").setPositiveButton("OK", null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.newMenuItem -> {
                editorBinding.textArea.text.clear()
                currentFileUri = null
                title = "NBH Editor"
                updateLineNumbers()
                true
            }
            R.id.openMenuItem -> { openFile(); true }
            R.id.saveMenuItem -> { saveFile(); true }
            R.id.saveAsMenuItem -> { saveFileAs(); true }
            R.id.deleteMenuItem -> { deleteFile(); true }
            R.id.improveMenuItem -> { improveWithAI(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
