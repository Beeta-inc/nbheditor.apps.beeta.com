package com.beeta.nbheditor

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
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
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.beeta.nbheditor.databinding.ActivityMainBinding
import com.beeta.nbheditor.databinding.FragmentAiChatBinding
import com.beeta.nbheditor.databinding.FragmentEditorBinding
import com.google.android.material.chip.Chip
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

open class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var editorBinding: FragmentEditorBinding
    private lateinit var aiChatBinding: FragmentAiChatBinding
    private lateinit var prefs: SharedPreferences

    private var currentFileUri: Uri? = null
    private var textChanged = false
    private var isTyping = false
    private var aiEnabled = true

    private val handler = Handler(Looper.getMainLooper())
    private val typingDelayRunnable = Runnable {
        isTyping = false
        if (textChanged) performAutoSave()
    }

    private var aiJob: Job? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // ── AI Backend ────────────────────────────────────────────────────────────
    // Priority: OpenRouter free models (1→6) then Google Gemini as final fallback
    // Each model is skipped on 429 rate-limit and the next one is tried.

    private val OPENROUTER_API_KEY = ""

    // 6 free OpenRouter models tried in order
    private val OR_MODELS = listOf(
        "stepfun/step-3.5-flash:free",            // 1 — fast, generous limits
        "mistralai/mistral-7b-instruct:free",     // 2 — reliable
        "meta-llama/llama-3.2-3b-instruct:free",  // 3 — Meta Llama free
        "google/gemma-3-4b-it:free",              // 4 — Google Gemma free
        "qwen/qwen3-0.6b:free",                   // 5 — Qwen tiny, very fast
        "microsoft/phi-3-mini-128k-instruct:free" // 6 — Microsoft Phi-3 free
    )

    // Google AI Studio — final fallback (1500 req/day free)
    // Get your key at: https://aistudio.google.com/apikey
    private val GEMINI_API_KEY = "AIzaSyAjO1m3x5uh5oqKt05nPRsS_H4MlgkUqN0"
    private val GEMINI_MODEL = "gemini-2.0-flash"

    private lateinit var chatAdapter: ChatAdapter

    data class ChatMessage(val role: String, val content: String)
    // OpenRouter
    data class ChatRequest(val model: String, val messages: List<ChatMessage>, val max_tokens: Int = 512)
    data class ChatResponse(val choices: List<Choice>)
    data class Choice(val message: ChatMessage)
    // Gemini
    data class GeminiPart(val text: String)
    data class GeminiContent(val parts: List<GeminiPart>)
    data class GeminiRequest(val contents: List<GeminiContent>)
    data class GeminiCandidate(val content: GeminiContent)
    data class GeminiResponse(val candidates: List<GeminiCandidate>)

    // Broad file type support
    private val SUPPORTED_MIME_TYPES = arrayOf(
        "text/*", "application/json", "application/xml",
        "application/javascript", "application/typescript",
        "application/x-python", "application/x-sh",
        "application/x-kotlin", "application/x-java",
        "application/octet-stream"
    )

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                openFileFromUri(uri)
            }
        }
    }

    private val saveFileAsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                currentFileUri = uri
                saveToFile(uri)
                updateToolbarTitle()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("nbheditor_prefs", MODE_PRIVATE)
        isGlassMode = this is GlassMainActivity

        // Apply saved theme before setContentView
        if (!isGlassMode) {
            applyThemeModeNoRecreate(prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
        }
        aiEnabled = prefs.getBoolean("ai_enabled", true)

        // Edge-to-edge only in glass mode
        if (isGlassMode) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.appBarMain.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

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

        // Reflect AI toggle state in drawer
        binding.navView.menu.findItem(R.id.nav_toggle_ai)?.let {
            it.isChecked = !aiEnabled
            it.title = if (aiEnabled) "Disable AI" else "Enable AI"
        }

        val container = binding.appBarMain.contentMain.fragmentContainer
        editorBinding = FragmentEditorBinding.inflate(layoutInflater, container, false)
        aiChatBinding = FragmentAiChatBinding.inflate(layoutInflater, container, false)
        container.addView(editorBinding.root)
        container.addView(aiChatBinding.root)
        aiChatBinding.root.visibility = View.GONE

        setupEditor()
        setupAiChat()
        setupBottomNav()
        checkForRecovery()

        if (isGlassMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            applyGlassColors()
            // Run blur on background thread to avoid blocking main thread (ANR)
            window.decorView.post {
                GlassBlurHelper.enableWindowBlur(window, this, blurRadius = 60)
            }
        }
    }

    private var isGlassMode = false

    private fun applyThemeModeNoRecreate(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(mode)
        prefs.edit().putInt("theme_mode", mode).apply()
    }

    private fun applyThemeMode(mode: Int) {
        isGlassMode = false
        prefs.edit().putInt("theme_mode", mode).putBoolean("glass_mode", false).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
        // If currently in glass activity, switch back to normal MainActivity
        if (this is GlassMainActivity) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        } else {
            recreate()
        }
    }

    private fun applyGlassMode() {
        isGlassMode = true
        prefs.edit().putBoolean("glass_mode", true).apply()
        // Launch GlassMainActivity which has the Glass theme in manifest
        if (this !is GlassMainActivity) {
            startActivity(Intent(this, GlassMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        } else {
            recreate()
        }
    }

    private fun applyGlassColors() {
        val r = resources
        val t = theme
        val glassEditorSurface  = r.getColor(R.color.glass_editor_surface,  t)
        val glassLineNumBg      = r.getColor(R.color.glass_line_numbers_bg,  t)
        val glassLineNumText    = r.getColor(R.color.glass_line_number_text,  t)
        val glassEditorText     = r.getColor(R.color.glass_editor_text,       t)
        val glassEditorHint     = r.getColor(R.color.glass_editor_hint,       t)
        val glassToolbarBg      = r.getColor(R.color.glass_toolbar_bg,        t)
        val glassDivider        = r.getColor(R.color.glass_divider,           t)
        val glassInputBg        = r.getColor(R.color.glass_input_bg,          t)
        val glassAiBubble       = r.getColor(R.color.glass_chat_ai_bubble,    t)
        val glassAiText         = r.getColor(R.color.glass_chat_ai_text,      t)
        val accentPrimary       = r.getColor(R.color.accent_primary,          t)

        // Root backgrounds — transparent so blur shows through
        binding.activityContainer.setBackgroundColor(Color.TRANSPARENT)
        binding.drawerLayout.setBackgroundColor(Color.TRANSPARENT)

        // Toolbar
        binding.appBarMain.toolbar.setBackgroundColor(glassToolbarBg)
        binding.appBarMain.toolbarTitle?.setTextColor(glassEditorText)

        // Bottom nav
        binding.appBarMain.contentMain.bottomNavView.setBackgroundColor(glassToolbarBg)

        // Nav drawer
        binding.navView.setBackgroundColor(glassToolbarBg)

        // Fragment container — transparent
        binding.appBarMain.contentMain.fragmentContainer.setBackgroundColor(Color.TRANSPARENT)

        // Editor
        editorBinding.contentPane.setBackgroundColor(Color.TRANSPARENT)
        editorBinding.root.setBackgroundColor(Color.TRANSPARENT)
        editorBinding.lineNumbersScroll.setBackgroundColor(glassLineNumBg)
        editorBinding.textArea.setBackgroundColor(glassEditorSurface)
        editorBinding.textArea.setTextColor(glassEditorText)
        editorBinding.textArea.setHintTextColor(glassEditorHint)
        editorBinding.textArea.setShadowLayer(3f, 0f, 1f, 0xFFFFFFFF.toInt())
        editorBinding.aiSuggestionContainer.setBackgroundColor(glassToolbarBg)

        // Update line number text colors
        for (i in 0 until editorBinding.lineNumbersVBox.childCount) {
            (editorBinding.lineNumbersVBox.getChildAt(i) as? TextView)?.setTextColor(glassLineNumText)
        }

        // AI Chat
        aiChatBinding.root.setBackgroundColor(Color.TRANSPARENT)
        aiChatBinding.chatHeader.setBackgroundColor(glassToolbarBg)
        aiChatBinding.headerDivider.setBackgroundColor(glassDivider)
        aiChatBinding.inputDivider.setBackgroundColor(glassDivider)
        aiChatBinding.inputBar.setBackgroundColor(glassToolbarBg)
        aiChatBinding.emptyState.setBackgroundColor(Color.TRANSPARENT)
        aiChatBinding.chatRecyclerView.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun setupBottomNav() {
        binding.appBarMain.contentMain.bottomNavView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_editor -> {
                    editorBinding.root.visibility = View.VISIBLE
                    aiChatBinding.root.visibility = View.GONE
                    binding.appBarMain.toolbarTitle?.text = "NBH Editor"
                    true
                }
                R.id.nav_ai_chat -> {
                    editorBinding.root.visibility = View.GONE
                    aiChatBinding.root.visibility = View.VISIBLE
                    binding.appBarMain.toolbarTitle?.text = "Beeta AI"
                    true
                }
                else -> false
            }
        }
    }

    private fun setupAiChat() {
        chatAdapter = ChatAdapter { text -> insertTextIntoEditor(text) }
        aiChatBinding.chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        aiChatBinding.chatRecyclerView.adapter = chatAdapter

        aiChatBinding.clearChatBtn.setOnClickListener {
            chatAdapter.clearMessages()
            aiChatBinding.chatRecyclerView.visibility = View.GONE
            aiChatBinding.emptyState.visibility = View.VISIBLE
        }

        aiChatBinding.sendButton.setOnClickListener { sendChatMessage() }
        aiChatBinding.queryEditText.setOnEditorActionListener { _, _, _ ->
            sendChatMessage(); true
        }
    }

    private fun insertTextIntoEditor(text: String) {
        // Switch to editor tab
        binding.appBarMain.contentMain.bottomNavView.selectedItemId = R.id.nav_editor
        editorBinding.root.visibility = View.VISIBLE
        aiChatBinding.root.visibility = View.GONE
        binding.appBarMain.toolbarTitle?.text = "NBH Editor"

        // Insert at cursor or append
        val editText = editorBinding.textArea
        val cursor = editText.selectionStart.coerceAtLeast(0)
        val current = editText.text ?: return
        val insertion = if (cursor > 0 && current.isNotEmpty() && current[cursor - 1] != '\n')
            "\n\n$text\n" else "$text\n"
        current.insert(cursor, insertion)
        Toast.makeText(this, "✓ Inserted into editor", Toast.LENGTH_SHORT).show()
    }

    private fun sendChatMessage() {
        if (!aiEnabled) {
            Toast.makeText(this, "AI is disabled. Enable it in Settings.", Toast.LENGTH_SHORT).show()
            return
        }
        val query = aiChatBinding.queryEditText.text.toString().trim()
        if (query.isBlank()) return

        chatAdapter.addMessage(ChatMessage("user", query))
        aiChatBinding.queryEditText.text.clear()
        aiChatBinding.emptyState.visibility = View.GONE
        aiChatBinding.chatRecyclerView.visibility = View.VISIBLE
        aiChatBinding.typingRow.visibility = View.VISIBLE
        scrollChatToBottom()

        lifecycleScope.launch {
            try {
                val aiResponse = callAI(query, maxTokens = 1024)
                aiChatBinding.typingRow.visibility = View.GONE
                if (aiResponse != null) {
                    chatAdapter.addMessage(ChatMessage("assistant", aiResponse))
                    scrollChatToBottom()
                } else {
                    showChatError("No response. Try again.")
                }
            } catch (e: Exception) {
                aiChatBinding.typingRow.visibility = View.GONE
                showChatError("Error: ${e.message?.take(60)}")
            }
        }
    }

    private fun showChatError(msg: String) {
        chatAdapter.addMessage(ChatMessage("assistant", "⚠ $msg"))
        scrollChatToBottom()
    }

    private fun scrollChatToBottom() {
        val count = chatAdapter.itemCount
        if (count > 0) aiChatBinding.chatRecyclerView.smoothScrollToPosition(count - 1)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return handleMenuItem(item) || super.onOptionsItemSelected(item)
    }

    private fun setupEditor() {
        editorBinding.textArea.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                textChanged = true
                isTyping = true
                updateLineNumbers()
                updateToolbarTitle()
                handler.removeCallbacks(typingDelayRunnable)
                handler.postDelayed(typingDelayRunnable, 2000)
                if (aiEnabled) triggerAISuggestions()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        editorBinding.textArea.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            editorBinding.lineNumbersScroll.scrollY = scrollY
        }

        updateLineNumbers()
    }

    private fun triggerAISuggestions() {
        aiJob?.cancel()
        val text = editorBinding.textArea.text.toString()
        if (text.length < 10) {
            editorBinding.aiSuggestionContainer.isVisible = false
            return
        }

        aiJob = lifecycleScope.launch {
            delay(60000) // 1 minute delay
            try {
                val context = if (text.length > 300) text.takeLast(300) else text
                val prompt = "Complete this text with 2 short options separated by |. Only return the options, nothing else:\n$context"
                val result = callAI(prompt, maxTokens = 80)
                val suggestions = result?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(2)
                withContext(Dispatchers.Main) {
                    if (!suggestions.isNullOrEmpty()) showAISuggestions(suggestions)
                    else editorBinding.aiSuggestionContainer.isVisible = false
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
            }
        }
    }

    private suspend fun callAI(prompt: String, maxTokens: Int = 512): String? {
        // Try all 6 OpenRouter free models first
        try {
            val result = callOpenRouter(prompt, maxTokens)
            if (result != null) return result
        } catch (e: Exception) {
            Log.w("AI", "OpenRouter failed: ${e.message}")
        }
        // Final fallback: Google Gemini
        try {
            val result = callGemini(prompt)
            if (result != null) return result
        } catch (e: Exception) {
            Log.e("AI", "Gemini failed: ${e.message}")
        }
        return null
    }

    private suspend fun callGemini(prompt: String): String? = withContext(Dispatchers.IO) {
        val body = gson.toJson(GeminiRequest(listOf(GeminiContent(listOf(GeminiPart(prompt))))))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent?key=$GEMINI_API_KEY")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val rb = response.body?.string()
            if (!response.isSuccessful) throw Exception("Gemini ${response.code}")
            gson.fromJson(rb, GeminiResponse::class.java)
                .candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
        }
    }

    private suspend fun callOpenRouter(prompt: String, maxTokens: Int = 512): String? =
        withContext(Dispatchers.IO) {
            for ((index, model) in OR_MODELS.withIndex()) {
                try {
                    Log.d("AI", "Trying model ${index + 1}/${OR_MODELS.size}: $model")
                    val body = gson.toJson(ChatRequest(model, listOf(ChatMessage("user", prompt)), maxTokens))
                        .toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url("https://openrouter.ai/api/v1/chat/completions")
                        .post(body)
                        .addHeader("Authorization", "Bearer $OPENROUTER_API_KEY")
                        .addHeader("HTTP-Referer", "https://nbheditor.apps.beeta.com")
                        .addHeader("X-Title", "NBH Editor")
                        .build()
                    client.newCall(request).execute().use { response ->
                        val rb = response.body?.string()
                        if (response.isSuccessful) {
                            val result = gson.fromJson(rb, ChatResponse::class.java)
                                .choices.firstOrNull()?.message?.content?.trim()
                            if (result != null) {
                                Log.d("AI", "✓ Success with $model")
                                return@withContext result
                            }
                        }
                        Log.w("AI", "Model $model failed (${response.code}), trying next")
                    }
                } catch (e: Exception) {
                    Log.w("AI", "Model $model error: ${e.message}")
                }
            }
            null
        }

    private fun showAISuggestions(suggestions: List<String>) {
        val list = editorBinding.aiSuggestionList
        // Keep the sparkle icon (first child), remove old chips
        while (list.childCount > 1) list.removeViewAt(1)

        val chipColors = listOf("#FF89B4FA", "#FFA6E3A1", "#FFCBA6F7", "#FFFAB387")
        suggestions.forEachIndexed { i, s ->
            val chip = Chip(this).apply {
                text = s
                isClickable = true
                isCheckable = false
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    Color.parseColor(chipColors[i % chipColors.size] + "33")
                )
                setTextColor(Color.parseColor(chipColors[i % chipColors.size]))
                chipStrokeColor = android.content.res.ColorStateList.valueOf(
                    Color.parseColor(chipColors[i % chipColors.size] + "88")
                )
                chipStrokeWidth = 1f
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 6; marginEnd = 6 }
                setOnClickListener { applySuggestion(s) }
            }
            list.addView(chip)
        }
        editorBinding.aiSuggestionContainer.isVisible = true
    }

    private fun applySuggestion(suggestion: String) {
        val pos = editorBinding.textArea.selectionStart.coerceAtLeast(0)
        editorBinding.textArea.text.insert(pos, suggestion)
        editorBinding.aiSuggestionContainer.isVisible = false
    }

    private fun improveWithAI() {
        if (!aiEnabled) {
            Toast.makeText(this, "AI is disabled. Enable it in Settings.", Toast.LENGTH_SHORT).show()
            return
        }
        val editText = editorBinding.textArea
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(0)
        val isSelection = start < end
        val selectedText = if (isSelection) editText.text.substring(start, end)
                           else editText.text.toString()

        if (selectedText.isBlank()) {
            Toast.makeText(this, "Nothing to improve", Toast.LENGTH_SHORT).show()
            return
        }

        // Snapshot the text to improve before async call
        val textSnapshot = selectedText

        editorBinding.overlayText.text = "✦ Improving with AI..."
        editorBinding.overlayView.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val prompt = if (isSelection)
                    "Fix and complete this text precisely. Fill in any missing values at exactly the right position (e.g. a math answer after =, a missing word, incomplete sentence). Return ONLY the corrected text, no explanation:\n\n$textSnapshot"
                else
                    "Review and improve this entire text. Fix errors, complete incomplete parts, improve clarity. Return ONLY the improved text, no explanation:\n\n$textSnapshot"

                val result = callAI(prompt, maxTokens = (textSnapshot.length * 2).coerceAtLeast(300))

                withContext(Dispatchers.Main) {
                    if (result.isNullOrBlank()) {
                        Toast.makeText(this@MainActivity, "AI returned no result. Try again.", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    try {
                        if (isSelection) {
                            // Re-validate positions are still valid
                            val len = editText.text.length
                            val safeStart = start.coerceIn(0, len)
                            val safeEnd = end.coerceIn(safeStart, len)
                            editText.text.replace(safeStart, safeEnd, result)
                        } else {
                            // Preserve cursor position after full-text replace
                            val cursorPos = editText.selectionStart
                            editText.text.replace(0, editText.text.length, result)
                            editText.setSelection(cursorPos.coerceIn(0, editText.text.length))
                        }
                        Toast.makeText(this@MainActivity, "✓ Improved", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        // Fallback if Editable replace fails
                        editText.setText(result)
                        Toast.makeText(this@MainActivity, "✓ Improved", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "AI error: ${e.message?.take(80)}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { editorBinding.overlayView.visibility = View.GONE }
            }
        }
    }

    private fun updateLineNumbers() {
        val lineCount = editorBinding.textArea.lineCount.coerceAtLeast(1)
        val current = editorBinding.lineNumbersVBox.childCount
        if (current == lineCount) return
        // Add missing lines
        if (lineCount > current) {
            for (i in (current + 1)..lineCount) {
                val tv = TextView(this).apply {
                    text = i.toString()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    gravity = Gravity.END
                    setPadding(0, 0, 8, 0)
                    typeface = Typeface.MONOSPACE
                    val lineNumColor = if (isGlassMode)
                        resources.getColor(R.color.glass_line_number_text, theme)
                    else
                        resources.getColor(R.color.editor_line_number_text, theme)
                    setTextColor(lineNumColor)
                    textSize = 12f
                }
                editorBinding.lineNumbersVBox.addView(tv)
            }
        } else {
            // Remove extra lines
            editorBinding.lineNumbersVBox.removeViews(lineCount, current - lineCount)
        }
    }

    private fun performAutoSave() {
        currentFileUri?.let { 
            saveToFile(it)
            // Save the file URI so we know user has an active file
            prefs.edit().putString("last_file_uri", it.toString()).apply()
        } ?: run {
            // Only save recovery text if there's actual content and no file is open
            val text = editorBinding.textArea.text.toString()
            if (text.isNotBlank()) {
                prefs.edit()
                    .putString("recovery_text", text)
                    .remove("last_file_uri")
                    .apply()
            }
        }
        textChanged = false
    }

    private fun getFileName(uri: Uri): String {
        // Try content resolver display name first (most reliable)
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {}
        // Fallback: decode the last path segment
        return android.net.Uri.decode(uri.lastPathSegment)?.substringAfterLast('/')?.substringAfterLast(':') ?: "untitled"
    }

    private fun updateToolbarTitle() {
        val name = if (currentFileUri != null) getFileName(currentFileUri!!) else "NBH Editor"
        val dot = if (textChanged) " ●" else ""
        binding.appBarMain.toolbarTitle?.text = "$name$dot"
        binding.appBarMain.toolbarTitle?.setTextColor(
            when {
                textChanged -> resources.getColor(R.color.accent_primary, theme)
                isGlassMode -> resources.getColor(R.color.glass_editor_text, theme)
                else        -> resources.getColor(R.color.editor_text, theme)
            }
        )
    }

    private fun openFileFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use {
                val content = BufferedReader(InputStreamReader(it)).readText()
                editorBinding.textArea.setText(content)
                currentFileUri = uri
                textChanged = false
                // Clear recovery text and save this file as the active one
                prefs.edit()
                    .remove("recovery_text")
                    .putString("last_file_uri", uri.toString())
                    .apply()
                updateLineNumbers()
                updateToolbarTitle()
                Toast.makeText(this, "Opened", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToFile(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use {
                OutputStreamWriter(it).use { w -> w.write(editorBinding.textArea.text.toString()) }
                textChanged = false
                updateToolbarTitle()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleMenuItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_new_file -> {
                editorBinding.textArea.setText("")
                currentFileUri = null
                textChanged = false
                // Clear recovery and last file URI so new file doesn't restore old content
                prefs.edit()
                    .remove("recovery_text")
                    .remove("last_file_uri")
                    .apply()
                updateLineNumbers()
                updateToolbarTitle()
            }
            R.id.nav_open_file -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, SUPPORTED_MIME_TYPES)
                }
                openFileLauncher.launch(intent)
            }
            R.id.nav_save_file, R.id.saveMenuItem -> {
                currentFileUri?.let {
                    saveToFile(it)
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                } ?: saveFileAsLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "untitled.txt")
                })
                updateToolbarTitle()
            }
            R.id.nav_save_as -> {
                saveFileAsLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "untitled.txt")
                })
            }
            R.id.nav_theme_auto -> applyThemeMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            R.id.nav_theme_dark -> applyThemeMode(AppCompatDelegate.MODE_NIGHT_YES)
            R.id.nav_theme_light -> applyThemeMode(AppCompatDelegate.MODE_NIGHT_NO)
            R.id.nav_theme_glass -> applyGlassMode()
            R.id.nav_toggle_ai -> {
                aiEnabled = !aiEnabled
                prefs.edit().putBoolean("ai_enabled", aiEnabled).apply()
                item.isChecked = !aiEnabled
                item.title = if (aiEnabled) "Disable AI" else "Enable AI"
                if (!aiEnabled) editorBinding.aiSuggestionContainer.isVisible = false
                Toast.makeText(this, if (aiEnabled) "AI Enabled" else "AI Disabled", Toast.LENGTH_SHORT).show()
            }
            R.id.improveMenuItem -> improveWithAI()
            R.id.quitMenuItem -> finish()
            else -> return false
        }
        return true
    }

    private fun checkForRecovery() {
        // Only recover if there's no saved file URI (user didn't open a file)
        val recovered = prefs.getString("recovery_text", null)
        val savedUri = prefs.getString("last_file_uri", null)
        
        if (!recovered.isNullOrBlank() && editorBinding.textArea.text.isBlank() && savedUri == null) {
            editorBinding.textArea.setText(recovered)
            updateLineNumbers()
            Toast.makeText(this, "Recovered unsaved text", Toast.LENGTH_SHORT).show()
        }
    }
}
