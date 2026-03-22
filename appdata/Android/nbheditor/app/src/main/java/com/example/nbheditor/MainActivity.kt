package com.example.nbheditor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.nbheditor.databinding.ActivityMainBinding
import com.example.nbheditor.databinding.FragmentEditorBinding
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

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
        apiKey = "AIzaSyAjO1m3x5uh5oqKt05nPRsS_H4MlgkUqN0",
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

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.appBarMain.toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navView.setNavigationItemSelectedListener { item ->
            handleMenuItem(item)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

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
            delay(500)
            try {
                if (generativeModel.apiKey.startsWith("YOUR_")) return@launch

                val context = if (text.length > 500) text.substring(text.length - 500) else text
                val prompt = "Continue this text briefly. Separate 2-3 short options with | symbol. Text: $context"
                
                val response = generativeModel.generateContent(prompt)
                val suggestions = response.text?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }
                
                withContext(Dispatchers.Main) {
                    if (!suggestions.isNullOrEmpty()) {
                        showAISuggestions(suggestions)
                    } else {
                        editorBinding.aiSuggestionContainer.isVisible = false
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                Log.e("AI_ERROR", "Check internet or API key", e)
                withContext(Dispatchers.Main) { editorBinding.aiSuggestionContainer.isVisible = false }
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
            try {
                showOverlay("AI Improving...", Color.MAGENTA)
            } catch (e: Exception) {
                Log.e("UI_ERROR", "Overlay views missing in layout")
            }
            
            try {
                val response = generativeModel.generateContent("Fix and improve this text: $selectedText")
                response.text?.let { fixedText ->
                    withContext(Dispatchers.Main) {
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
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "AI failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { hideOverlay() }
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
                    setTextColor(if (isDarkTheme) Color.GRAY else Color.LTGRAY)
                    textSize = 12f
                }
                editorBinding.lineNumbersVBox.addView(tv)
            }
        }
    }

    private fun setupAutoSave() {
    }

    private fun triggerAutoSaveDelay() {
        handler.removeCallbacks(typingDelayRunnable)
        handler.postDelayed(typingDelayRunnable, 2000)
    }

    private fun performAutoSave() {
        currentFileUri?.let { saveToFile(it) } ?: run {
            prefs.edit().putString("recovery_text", editorBinding.textArea.text.toString()).apply()
        }
        textChanged = false
    }

    private fun openFileFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val content = reader.readText()
                    editorBinding.textArea.setText(content)
                    currentFileUri = uri
                    textChanged = false
                    updateLineNumbers()
                    showNotification("File Opened", Color.parseColor("#4CAF50"))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToFile(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(editorBinding.textArea.text.toString())
                    textChanged = false
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save file", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Stub methods to satisfy calls in the provided code
    private fun detectAndApplySystemTheme() {}
    private fun checkForRecovery() {}
    private fun handleMenuItem(item: MenuItem) {}
    private fun saveToFileWithAnimation(uri: Uri) {}
    private fun showOverlay(msg: String, color: Int) {}
    private fun hideOverlay() {}
    private fun showNotification(msg: String, color: Int) {}
}
