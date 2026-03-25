package com.example.nbheditor

import android.app.Activity
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
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nbheditor.databinding.ActivityMainBinding
import com.example.nbheditor.databinding.FragmentAiChatBinding
import com.example.nbheditor.databinding.FragmentEditorBinding
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var editorBinding: FragmentEditorBinding
    private lateinit var aiChatBinding: FragmentAiChatBinding
    private lateinit var prefs: SharedPreferences

    private var currentFileUri: Uri? = null
    private var textChanged = false
    private var isTyping = false

    private val handler = Handler(Looper.getMainLooper())
    private val typingDelayRunnable = Runnable {
        isTyping = false
        if (textChanged) performAutoSave()
    }

    // AI
    private var aiJob: Job? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val OPENROUTER_API_KEY = "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"
    // Fast free model for suggestions; reliable model for chat/improve
    private val FAST_MODEL = "mistralai/mistral-7b-instruct:free"
    private val CHAT_MODEL = "mistralai/mistral-7b-instruct:free"

    private lateinit var chatAdapter: ChatAdapter

    data class ChatMessage(val role: String, val content: String)
    data class ChatRequest(val model: String, val messages: List<ChatMessage>, val max_tokens: Int = 512)
    data class ChatResponse(val choices: List<Choice>)
    data class Choice(val message: ChatMessage)

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
                saveToFile(uri)
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        val container = binding.appBarMain.contentMain.fragmentContainer
        editorBinding = FragmentEditorBinding.inflate(layoutInflater, container, false)
        aiChatBinding = FragmentAiChatBinding.inflate(layoutInflater, container, false)
        container.addView(editorBinding.root)
        container.addView(aiChatBinding.root)
        aiChatBinding.root.visibility = View.GONE

        prefs = getPreferences(MODE_PRIVATE)

        setupEditor()
        setupAiChat()
        setupBottomNav()
        checkForRecovery()
    }

    private fun setupBottomNav() {
        binding.appBarMain.contentMain.bottomNavView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_editor -> {
                    editorBinding.root.visibility = View.VISIBLE
                    aiChatBinding.root.visibility = View.GONE
                    binding.appBarMain.toolbarTitle.text = "NBH Editor"
                    true
                }
                R.id.nav_ai_chat -> {
                    editorBinding.root.visibility = View.GONE
                    aiChatBinding.root.visibility = View.VISIBLE
                    binding.appBarMain.toolbarTitle.text = "Beeta AI"
                    true
                }
                else -> false
            }
        }
    }

    private fun setupAiChat() {
        chatAdapter = ChatAdapter()
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
            sendChatMessage()
            true
        }
    }

    private fun sendChatMessage() {
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
                val aiResponse = callOpenRouter(query, CHAT_MODEL, maxTokens = 1024)
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
                handler.removeCallbacks(typingDelayRunnable)
                handler.postDelayed(typingDelayRunnable, 2000)
                triggerAISuggestions()
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
            delay(1200)
            try {
                val context = if (text.length > 300) text.takeLast(300) else text
                val prompt = "Complete this text with 2 short options separated by |. Only return the options, nothing else:\n$context"
                val result = callOpenRouter(prompt, FAST_MODEL, maxTokens = 80)
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

    private suspend fun callOpenRouter(prompt: String, model: String, maxTokens: Int = 512): String? =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(
                ChatRequest(model, listOf(ChatMessage("user", prompt)), maxTokens)
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .post(body)
                .addHeader("Authorization", "Bearer $OPENROUTER_API_KEY")
                .addHeader("HTTP-Referer", "https://nbheditor.apps.beeta.com")
                .addHeader("X-Title", "NBH Editor")
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    Log.e("AI", "HTTP ${response.code}: $responseBody")
                    throw Exception("Server error ${response.code}")
                }
                gson.fromJson(responseBody, ChatResponse::class.java)
                    .choices.firstOrNull()?.message?.content?.trim()
            }
        }

    private fun showAISuggestions(suggestions: List<String>) {
        editorBinding.aiSuggestionList.removeAllViews()
        for (s in suggestions) {
            val btn = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = s
                isAllCaps = false
                textSize = 12f
                setOnClickListener { applySuggestion(s) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 8; marginEnd = 8 }
            }
            editorBinding.aiSuggestionList.addView(btn)
        }
        editorBinding.aiSuggestionContainer.isVisible = true
    }

    private fun applySuggestion(suggestion: String) {
        editorBinding.textArea.text.insert(editorBinding.textArea.selectionStart, suggestion)
        editorBinding.aiSuggestionContainer.isVisible = false
    }

    private fun improveWithAI() {
        val start = editorBinding.textArea.selectionStart
        val end = editorBinding.textArea.selectionEnd
        val selectedText = if (start < end) editorBinding.textArea.text.substring(start, end)
                           else editorBinding.textArea.text.toString()

        if (selectedText.isBlank()) {
            Toast.makeText(this, "Nothing to improve", Toast.LENGTH_SHORT).show()
            return
        }

        editorBinding.overlayText.text = "Improving with AI..."
        editorBinding.overlayView.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val prompt = "Rewrite the following text to be clearer and better. Return ONLY the rewritten text with no explanation:\n\n$selectedText"
                val result = callOpenRouter(prompt, CHAT_MODEL, maxTokens = selectedText.length.coerceAtLeast(200))
                withContext(Dispatchers.Main) {
                    result?.let {
                        if (start < end) editorBinding.textArea.text.replace(start, end, it)
                        else editorBinding.textArea.setText(it)
                        Toast.makeText(this@MainActivity, "✓ Improved", Toast.LENGTH_SHORT).show()
                    } ?: Toast.makeText(this@MainActivity, "No result from AI", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "AI error: ${e.message?.take(50)}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { editorBinding.overlayView.visibility = View.GONE }
            }
        }
    }

    private fun updateLineNumbers() {
        val lineCount = editorBinding.textArea.lineCount.coerceAtLeast(1)
        if (editorBinding.lineNumbersVBox.childCount == lineCount) return
        editorBinding.lineNumbersVBox.removeAllViews()
        for (i in 1..lineCount) {
            val tv = TextView(this).apply {
                text = i.toString()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.END
                setPadding(0, 0, 10, 0)
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#FF6C7086"))
                textSize = 12f
            }
            editorBinding.lineNumbersVBox.addView(tv)
        }
    }

    private fun performAutoSave() {
        currentFileUri?.let { saveToFile(it) } ?: prefs.edit()
            .putString("recovery_text", editorBinding.textArea.text.toString()).apply()
        textChanged = false
    }

    private fun openFileFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use {
                val content = BufferedReader(InputStreamReader(it)).readText()
                editorBinding.textArea.setText(content)
                currentFileUri = uri
                textChanged = false
                updateLineNumbers()
                Toast.makeText(this, "File opened", Toast.LENGTH_SHORT).show()
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
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleMenuItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_new_file, R.id.newMenuItem -> {
                editorBinding.textArea.setText("")
                currentFileUri = null
                textChanged = false
                updateLineNumbers()
                binding.appBarMain.toolbarTitle.text = "NBH Editor"
            }
            R.id.nav_open_file, R.id.openMenuItem -> {
                openFileLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/*"
                })
            }
            R.id.nav_save_file, R.id.saveMenuItem -> {
                currentFileUri?.let { saveToFile(it); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() }
                    ?: saveFileAsLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "untitled.txt")
                    })
            }
            R.id.nav_save_as, R.id.saveAsMenuItem -> {
                saveFileAsLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "untitled.txt")
                })
            }
            R.id.improveMenuItem -> improveWithAI()
            R.id.quitMenuItem -> finish()
            else -> return false
        }
        return true
    }

    private fun checkForRecovery() {
        val recovered = prefs.getString("recovery_text", null)
        if (!recovered.isNullOrBlank() && editorBinding.textArea.text.isBlank()) {
            editorBinding.textArea.setText(recovered)
            updateLineNumbers()
            Toast.makeText(this, "Recovered unsaved text", Toast.LENGTH_SHORT).show()
        }
    }
}
