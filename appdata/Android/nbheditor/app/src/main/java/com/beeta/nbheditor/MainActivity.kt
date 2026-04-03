package com.beeta.nbheditor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ImageSpan
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import java.util.Locale
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
    
    // Undo stack
    private val undoStack = mutableListOf<String>()
    private var isUndoing = false

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

    // Image generation data classes
    data class ImageGenRequest(val model: String, val prompt: String)
    data class ImageGenChoice(val message: ImageGenMessage)
    data class ImageGenMessage(val content: List<ImageGenContent>)
    data class ImageGenContent(val type: String, val image_url: ImageGenUrl? = null)
    data class ImageGenUrl(val url: String)

    private val FLUX_MODEL = "black-forest-labs/flux-1-schnell"

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechTarget: EditText? = null // which EditText receives speech
    private var voiceModeDialog: android.app.Dialog? = null
    private var voiceWaveformView: VoiceWaveformView? = null
    private var voiceCountdownTimer: android.os.CountDownTimer? = null
    private var lastVoiceDetectedTime = 0L
    private var voiceTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ── Chat Memory ───────────────────────────────────────────────────────────
    private val chatHistory = mutableListOf<ChatMessage>() // in-memory conversation history
    private var memoryEnabled = false
    private var currentChatFile: java.io.File? = null

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> insertImageIntoEditor(uri) }
        }
    }

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
                // Take persistable permission so the URI survives app restarts
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
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
        handleOpenIntent(intent)

        if (isGlassMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            applyGlassColors()
            window.decorView.post {
                GlassBlurHelper.enableWindowBlur(window, this, blurRadius = 60)
            }
        }

        setupVoiceButtons()
        setupImageButton()
        registerBackHandler()

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102
                )
            }
        }

        // Show home screen unless launched via "Open with"
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_EDIT) {
            showEditor()
        } else {
            showHome()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        destroySpeechRecognizer()
        if (memoryEnabled) saveCurrentChat()
        if (isGlassMode) GlassTextAdapter.stop()
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
        // Transparent containers so window blur shows through
        listOf(
            binding.activityContainer,
            binding.drawerLayout,
            binding.appBarMain.contentMain.fragmentContainer,
            editorBinding.contentPane,
            editorBinding.root,
            aiChatBinding.root,
            aiChatBinding.emptyState,
            aiChatBinding.chatRecyclerView
        ).forEach { it.setBackgroundColor(Color.TRANSPARENT) }

        // Premium frosted glass bars
        val barColor    = 0xF5121826.toInt()   // 96% opaque dark
        val surfaceColor = 0xF0121826.toInt()  // 94% opaque
        val divColor    = 0x33FFFFFF

        binding.appBarMain.toolbar.setBackgroundColor(barColor)
        binding.appBarMain.contentMain.bottomNavView.setBackgroundColor(barColor)
        editorBinding.editorToolbar.setBackgroundColor(surfaceColor)
        aiChatBinding.chatHeader.setBackgroundColor(barColor)
        aiChatBinding.inputBar.setBackgroundColor(barColor)
        editorBinding.aiSuggestionContainer.setBackgroundColor(surfaceColor)
        editorBinding.autoSaveContainer.setBackgroundColor(surfaceColor)
        binding.navView.setBackgroundColor(0xF8121826.toInt())

        // Dividers
        aiChatBinding.headerDivider.setBackgroundColor(divColor)
        aiChatBinding.inputDivider.setBackgroundColor(divColor)

        // Editor area: solid enough to read text comfortably
        editorBinding.lineNumbersScroll.setBackgroundColor(0xF5101520.toInt())
        editorBinding.textArea.setBackgroundColor(0xF0101520.toInt())
        editorBinding.textArea.setTextColor(0xFFFFFFFF.toInt())
        editorBinding.textArea.setHintTextColor(0x99FFFFFF.toInt())

        // All text/icons in bars: white, always visible
        val white = 0xFFFFFFFF.toInt()
        val whiteSecondary = 0xCCFFFFFF.toInt()
        val accentBlue = resources.getColor(R.color.accent_primary, theme)

        // Toolbar title
        binding.appBarMain.toolbarTitle?.apply {
            setTextColor(white)
            setShadowLayer(0f, 0f, 0f, 0)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        }

        // Toolbar hamburger + overflow + action icons
        binding.appBarMain.toolbar.post {
            binding.appBarMain.toolbar.navigationIcon?.setTint(white)
            binding.appBarMain.toolbar.overflowIcon?.setTint(white)
            binding.appBarMain.toolbar.menu?.let { menu ->
                for (i in 0 until menu.size()) menu.getItem(i).icon?.setTint(white)
            }
        }

        // Bottom nav: white icons/text, accent for selected
        val navTintList = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(accentBlue, whiteSecondary)
        )
        binding.appBarMain.contentMain.bottomNavView.itemIconTintList = navTintList
        binding.appBarMain.contentMain.bottomNavView.itemTextColor = navTintList

        // Nav drawer items
        val drawerTintList = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(accentBlue, white)
        )
        binding.navView.itemIconTintList = drawerTintList
        binding.navView.itemTextColor = drawerTintList

        // Editor toolbar icon buttons
        editorBinding.editorVoiceButton.setColorFilter(accentBlue)
        editorBinding.insertImageButton.setColorFilter(
            resources.getColor(R.color.accent_secondary, theme)
        )

        // AI chat header texts already use accent colors from XML
        // Voice button in chat
        aiChatBinding.voiceButton.setColorFilter(accentBlue)

        // Line numbers
        for (i in 0 until editorBinding.lineNumbersVBox.childCount) {
            (editorBinding.lineNumbersVBox.getChildAt(i) as? TextView)
                ?.setTextColor(0x99FFFFFF.toInt())
        }

        // Editor text area: adaptive color only here (text sits on variable bg)
        editorBinding.textArea.paintFlags =
            editorBinding.textArea.paintFlags or android.graphics.Paint.FAKE_BOLD_TEXT_FLAG
        binding.root.post { startAdaptiveColorLoop() }
    }

    private fun startAdaptiveColorLoop() {
        GlassTextAdapter.start(this)
        // Only the editor text area needs adaptive color — everything else
        // sits on the dark semi-opaque bar and is always white
        GlassTextAdapter.watch(editorBinding.textArea) { fg, sh ->
            editorBinding.textArea.setTextColor(fg)
            editorBinding.textArea.setShadowLayer(5f, 0f, 1f, sh)
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    // ── Home screen ───────────────────────────────────────────────────────────────

    private lateinit var homeBinding: com.beeta.nbheditor.databinding.FragmentHomeBinding
    private lateinit var fileCardAdapter: FileCardAdapter
    private var isHomeVisible = false

    private fun setupHome() {
        val homeContainer = binding.appBarMain.contentMain.homeContainer ?: return
        homeBinding = com.beeta.nbheditor.databinding.FragmentHomeBinding.inflate(
            layoutInflater, homeContainer, false
        )
        homeContainer.addView(homeBinding.root)

        // Responsive grid: 2 columns up to 10 files, 3 columns for 11+
        val spanCount = 2
        val gridManager = androidx.recyclerview.widget.GridLayoutManager(this, spanCount)
        fileCardAdapter = FileCardAdapter(
            onOpen = { entry ->
                openFileFromUri(entry.uri)
                showEditor()
            },
            onLongClick = { entry ->
                // Long click: delete from recents
                removeFromRecents(entry.uri)
                refreshHomeFiles()
            }
        )
        homeBinding.fileGrid.layoutManager = gridManager
        homeBinding.fileGrid.adapter = fileCardAdapter
        homeBinding.fileGrid.addItemDecoration(
            androidx.recyclerview.widget.DividerItemDecoration(this,
                androidx.recyclerview.widget.DividerItemDecoration.VERTICAL).also {
                it.setDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
        )

        homeBinding.btnNewFile.setOnClickListener {
            editorBinding.textArea.setText("")
            currentFileUri = null
            textChanged = false
            prefs.edit().remove("recovery_text").remove("last_file_uri").apply()
            updateLineNumbers()
            updateToolbarTitle()
            showEditor()
        }

        // Glass toggle
        val glassOn = isGlassMode
        fileCardAdapter.isGlassMode = glassOn
        applyHomeGlass(glassOn)
        updateGlassToggleButton(glassOn)
        homeBinding.btnGlassToggle.setOnClickListener {
            val newGlass = !fileCardAdapter.isGlassMode
            fileCardAdapter.isGlassMode = newGlass
            
            // Smooth transition animation
            homeBinding.fileGrid.animate()
                .alpha(0.7f)
                .setDuration(150)
                .withEndAction {
                    fileCardAdapter.notifyDataSetChanged()
                    applyHomeGlass(newGlass)
                    updateGlassToggleButton(newGlass)
                    homeBinding.fileGrid.animate().alpha(1f).setDuration(150).start()
                }
                .start()
        }
    }

    private fun updateGlassToggleButton(glass: Boolean) {
        homeBinding.btnGlassToggle.apply {
            text = if (glass) "✦ Glass ON" else "Glass"
            // Subtle scale animation
            animate().scaleX(0.95f).scaleY(0.95f).setDuration(100)
                .withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }
                .start()
        }
    }

    private fun applyHomeGlass(glass: Boolean) {
        if (!::homeBinding.isInitialized) return
        val surfaceColor = resources.getColor(R.color.editor_surface, theme)
        val bgColor = resources.getColor(R.color.editor_bg, theme)
        val editorText = resources.getColor(R.color.editor_text, theme)
        val editorHint = resources.getColor(R.color.editor_hint, theme)

        if (glass) {
            // Premium glass colors
            homeBinding.homeRoot.setBackgroundColor(0xF5101520.toInt())
            homeBinding.homeHeader.setBackgroundColor(0xF5121826.toInt())
            homeBinding.searchCard.apply {
                setCardBackgroundColor(0xF0121826.toInt())
                cardElevation = 0f
                radius = 24f
            }
            homeBinding.homeTitle.apply {
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(6f, 0f, 2f, 0x88000000.toInt())
            }
            homeBinding.fileCountLabel.setTextColor(resources.getColor(R.color.accent_primary, theme))
            homeBinding.sortLabel.setTextColor(0xCCFFFFFF.toInt())
            homeBinding.searchBar.apply {
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0x99FFFFFF.toInt())
            }
            homeBinding.btnGlassToggle.apply {
                setTextColor(0xFFFFFFFF.toInt())
                alpha = 1f
            }
        } else {
            homeBinding.homeRoot.setBackgroundColor(bgColor)
            homeBinding.homeHeader.setBackgroundColor(surfaceColor)
            homeBinding.searchCard.apply {
                setCardBackgroundColor(surfaceColor)
                cardElevation = 2f
                radius = 16f
            }
            homeBinding.homeTitle.apply {
                setTextColor(editorText)
                setShadowLayer(0f, 0f, 0f, 0)
            }
            homeBinding.fileCountLabel.setTextColor(resources.getColor(R.color.accent_primary, theme))
            homeBinding.sortLabel.setTextColor(resources.getColor(R.color.editor_line_number_text, theme))
            homeBinding.searchBar.apply {
                setTextColor(editorText)
                setHintTextColor(editorHint)
            }
            homeBinding.btnGlassToggle.apply {
                setTextColor(editorText)
                alpha = 0.9f
            }
        }
    }

    private fun showHome() {
        isHomeVisible = true
        if (!::homeBinding.isInitialized) setupHome()
        refreshHomeFiles()
        binding.appBarMain.contentMain.homeContainer?.visibility = View.VISIBLE
        binding.appBarMain.contentMain.fragmentContainer?.visibility = View.GONE
        binding.appBarMain.contentMain.bottomNavView?.visibility = View.GONE
        binding.appBarMain.toolbarTitle?.text = "NBH Editor"
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    private fun showEditor() {
        isHomeVisible = false
        binding.appBarMain.contentMain.homeContainer?.visibility = View.GONE
        binding.appBarMain.contentMain.fragmentContainer?.visibility = View.VISIBLE
        binding.appBarMain.contentMain.bottomNavView?.visibility = View.VISIBLE
        // Select editor tab
        binding.appBarMain.contentMain.bottomNavView.selectedItemId = R.id.nav_editor
        editorBinding.root.visibility = View.VISIBLE
        aiChatBinding.root.visibility = View.GONE
        updateToolbarTitle()
    }

    private fun refreshHomeFiles() {
        val uriStrings = prefs.getStringSet("recent_files", emptySet()) ?: emptySet()
        if (uriStrings.isEmpty()) {
            homeBinding.emptyHomeState.visibility = View.VISIBLE
            homeBinding.fileGrid.visibility = View.GONE
            return
        }

        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val list = mutableListOf<FileCardAdapter.FileEntry>()
                for (uriStr in uriStrings) {
                    try {
                        val uri = android.net.Uri.parse(uriStr)
                        // Verify we still have permission
                        val hasPerm = contentResolver.persistedUriPermissions
                            .any { it.uri == uri && it.isReadPermission }
                        if (!hasPerm) continue

                        val name = getFileName(uri)
                        if (name == "untitled" || name.isBlank()) continue

                        val preview = try {
                            contentResolver.openInputStream(uri)?.use {
                                BufferedReader(InputStreamReader(it)).readText()
                                    .replace(Regex("\\[img:[^\\]]+\\]"), "[image]")
                                    .take(150).trim()
                            } ?: ""
                        } catch (e: Exception) { "" }

                        list.add(FileCardAdapter.FileEntry(uri, name, preview))
                    } catch (e: Exception) { /* skip */ }
                }
                list.sortedBy { it.name.lowercase() }
            }

            val cols = when {
                entries.size <= 2  -> 1
                entries.size <= 10 -> 2
                else               -> 3
            }
            (homeBinding.fileGrid.layoutManager as? androidx.recyclerview.widget.GridLayoutManager)
                ?.spanCount = cols

            if (entries.isEmpty()) {
                homeBinding.emptyHomeState.visibility = View.VISIBLE
                homeBinding.fileGrid.visibility = View.GONE
            } else {
                homeBinding.emptyHomeState.visibility = View.GONE
                homeBinding.fileGrid.visibility = View.VISIBLE
                fileCardAdapter.setFiles(entries)
            }
        }
    }

    private fun addToRecents(uri: Uri) {
        val set = prefs.getStringSet("recent_files", mutableSetOf())!!.toMutableSet()
        set.add(uri.toString())
        prefs.edit().putStringSet("recent_files", set).apply()
    }

    private fun removeFromRecents(uri: Uri) {
        val set = prefs.getStringSet("recent_files", mutableSetOf())!!.toMutableSet()
        set.remove(uri.toString())
        prefs.edit().putStringSet("recent_files", set).apply()
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
        chatAdapter = ChatAdapter(
            onInsert = { text -> insertTextIntoEditor(text) },
            onInsertImage = { base64 -> insertBase64ImageIntoEditor(base64) }
        )
        aiChatBinding.chatRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        aiChatBinding.chatRecyclerView.adapter = chatAdapter

        memoryEnabled = prefs.getBoolean("memory_enabled", false)
        val memoryAsked = prefs.getBoolean("memory_asked", false)
        if (!memoryAsked) showMemoryOnboardingDialog()

        aiChatBinding.newChatBtn.setOnClickListener {
            if (memoryEnabled && chatHistory.isNotEmpty()) saveCurrentChat()
            chatAdapter.clearMessages()
            chatHistory.clear()
            currentChatFile = null
            aiChatBinding.chatRecyclerView.visibility = View.GONE
            aiChatBinding.emptyState.visibility = View.VISIBLE
            Toast.makeText(this, "✨ New chat started", Toast.LENGTH_SHORT).show()
        }

        aiChatBinding.clearChatBtn.setOnClickListener {
            if (memoryEnabled) saveCurrentChat()
            chatAdapter.clearMessages()
            chatHistory.clear()
            currentChatFile = null
            aiChatBinding.chatRecyclerView.visibility = View.GONE
            aiChatBinding.emptyState.visibility = View.VISIBLE
        }

        aiChatBinding.historyBtn.setOnClickListener { showChatHistoryDialog() }

        aiChatBinding.sendButton.setOnClickListener { sendChatMessage() }
        aiChatBinding.queryEditText.setOnEditorActionListener { _, _, _ ->
            sendChatMessage(); true
        }

        // Quick action chips
        aiChatBinding.quickCodeChip.setOnClickListener {
            aiChatBinding.queryEditText.setText("Help me write code for ")
            aiChatBinding.queryEditText.setSelection(aiChatBinding.queryEditText.text.length)
        }

        aiChatBinding.quickExplainChip.setOnClickListener {
            aiChatBinding.queryEditText.setText("Explain this concept: ")
            aiChatBinding.queryEditText.setSelection(aiChatBinding.queryEditText.text.length)
        }

        aiChatBinding.quickDebugChip.setOnClickListener {
            aiChatBinding.queryEditText.setText("Help me debug this issue: ")
            aiChatBinding.queryEditText.setSelection(aiChatBinding.queryEditText.text.length)
        }
    }

    // ── Memory: onboarding dialog ─────────────────────────────────────────────
    private fun showMemoryOnboardingDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("✦ Enable Chat Memory?")
            .setMessage("Beeta AI can remember your conversations on this device for context.\n\nAll data stays private — stored locally only.")
            .setPositiveButton("Enable Memory") { _, _ ->
                memoryEnabled = true
                prefs.edit().putBoolean("memory_enabled", true).putBoolean("memory_asked", true).apply()
                Toast.makeText(this, "Memory enabled", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("No Thanks") { _, _ ->
                memoryEnabled = false
                prefs.edit().putBoolean("memory_enabled", false).putBoolean("memory_asked", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    // ── Memory: save current chat to file ─────────────────────────────────────
    private fun saveCurrentChat() {
        if (chatHistory.isEmpty()) return
        val chatsDir = java.io.File(filesDir, "nbh_chats").also { it.mkdirs() }
        val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val file = currentChatFile ?: run {
            // Count existing files for this date to get next index
            val existing = chatsDir.listFiles { f ->
                f.name.startsWith(date) && f.name.endsWith("nbhheditorchat")
            }?.size ?: 0
            val idx = String.format("%02d", existing + 1)
            java.io.File(chatsDir, "${date}${idx}nbhheditorchat").also { currentChatFile = it }
        }
        try {
            val gson = com.google.gson.Gson()
            file.writeText(gson.toJson(chatHistory))
        } catch (_: Exception) {}
    }

    // ── Memory: load a saved chat ─────────────────────────────────────────────
    private fun loadChat(file: java.io.File) {
        try {
            val type = object : com.google.gson.reflect.TypeToken<List<ChatMessage>>() {}.type
            val loaded: List<ChatMessage> = gson.fromJson(file.readText(), type)
            chatHistory.clear()
            chatHistory.addAll(loaded)
            chatAdapter.clearMessages()
            loaded.forEach { chatAdapter.addMessage(it) }
            currentChatFile = file
            aiChatBinding.chatRecyclerView.visibility = View.VISIBLE
            aiChatBinding.emptyState.visibility = View.GONE
            scrollChatToBottom()
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to load chat", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Memory: history dialog with delete ────────────────────────────────────
    private fun showChatHistoryDialog() {
        val chatsDir = java.io.File(filesDir, "nbh_chats")
        val files = chatsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, "No saved chats", Toast.LENGTH_SHORT).show()
            return
        }
        val names = files.map { f ->
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<ChatMessage>>() {}.type
                val msgs: List<ChatMessage> = gson.fromJson(f.readText(), type)
                val firstUser = msgs.firstOrNull { it.role == "user" }?.content?.take(40) ?: "Empty chat"
                firstUser.replace("\n", " ").trim()
            } catch (_: Exception) {
                "Chat"
            }
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Chat History")
            .setItems(names) { _, which -> loadChat(files[which]) }
            .setNeutralButton("Delete All") { _, _ ->
                files.forEach { it.delete() }
                Toast.makeText(this, "All chats deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
            .also { dialog ->
                // Long-press on item to delete individual chat
                (dialog as? androidx.appcompat.app.AlertDialog)
                    ?.listView?.setOnItemLongClickListener { _, _, pos, _ ->
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setMessage("Delete ${names[pos]}?")
                            .setPositiveButton("Delete") { _, _ ->
                                files[pos].delete()
                                dialog.dismiss()
                                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }
            }
    }

    // ── Voice-to-text ─────────────────────────────────────────────────────────

    private var isListening = false
    private var voiceRetryCount = 0
    private var voiceAnimator: android.animation.ObjectAnimator? = null

    private fun setupVoiceButtons() {
        editorBinding.editorVoiceButton.setOnClickListener {
            startVoiceInput(editorBinding.textArea)
        }
        aiChatBinding.voiceButton.setOnClickListener {
            startVoiceInput(aiChatBinding.queryEditText)
        }
    }

    private fun stopVoiceInput() {
        isListening = false
        voiceRetryCount = 0
        speechRecognizer?.apply { stopListening(); cancel() }
        setMicActive(false)
        speechTarget?.hint = ""
        stopVoiceAnimation()
    }

    private fun setMicActive(active: Boolean) {
        val tint = if (active)
            resources.getColor(R.color.accent_peach, theme)
        else
            resources.getColor(R.color.accent_primary, theme)
        
        editorBinding.editorVoiceButton.apply {
            setColorFilter(tint)
            if (active) startVoiceAnimation(this) else stopVoiceAnimation()
        }
        aiChatBinding.voiceButton.apply {
            setColorFilter(tint)
            if (active) startVoiceAnimation(this) else stopVoiceAnimation()
        }
    }

    private fun startVoiceAnimation(view: android.view.View) {
        stopVoiceAnimation()
        voiceAnimator = android.animation.ObjectAnimator.ofFloat(view, "alpha", 1f, 0.4f).apply {
            duration = 600
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode = android.animation.ObjectAnimator.REVERSE
            start()
        }
    }

    private fun stopVoiceAnimation() {
        voiceAnimator?.cancel()
        voiceAnimator = null
        editorBinding.editorVoiceButton.alpha = 1f
        aiChatBinding.voiceButton.alpha = 1f
    }

    private fun destroySpeechRecognizer() {
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun startVoiceInput(target: EditText) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        if (isListening) { stopVoiceInput(); return }
        speechTarget = target
        voiceRetryCount = 0
        destroySpeechRecognizer()
        doStartListening()
    }

    private fun doStartListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_LONG).show()
            return
        }
        // Create fresh recognizer on the main thread (required by Android)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                isListening = true
                voiceRetryCount = 0
                setMicActive(true)
                speechTarget?.hint = "🎤 Listening..."
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity, "Speak now", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setMicActive(false) }
            override fun onResults(results: android.os.Bundle) {
                isListening = false
                voiceRetryCount = 0
                setMicActive(false)
                speechTarget?.hint = ""
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: return
                val et = speechTarget ?: return
                val pos = et.selectionStart.coerceAtLeast(0)
                
                // Add space before if needed
                val textToInsert = if (pos > 0 && et.text?.getOrNull(pos - 1)?.isWhitespace() == false) {
                    " $text"
                } else {
                    text
                }
                
                et.text?.insert(pos, textToInsert) ?: et.setText(textToInsert)
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity, "✓ Voice inserted", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            override fun onPartialResults(partial: android.os.Bundle?) {
                val text = partial
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: return
                speechTarget?.hint = "🎤 $text"
            }
            override fun onError(error: Int) {
                isListening = false
                setMicActive(false)
                speechTarget?.hint = ""
                when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Recognizer busy: destroy and retry once after delay
                        destroySpeechRecognizer()
                        if (voiceRetryCount < 2) {
                            voiceRetryCount++
                            handler.postDelayed({ doStartListening() }, 600L)
                        } else {
                            voiceRetryCount = 0
                            Toast.makeText(this@MainActivity, "Mic busy — try again", Toast.LENGTH_SHORT).show()
                        }
                    }
                    SpeechRecognizer.ERROR_CLIENT -> {
                        // Client error: recreate and retry once
                        destroySpeechRecognizer()
                        if (voiceRetryCount < 1) {
                            voiceRetryCount++
                            handler.postDelayed({ doStartListening() }, 400L)
                        } else {
                            voiceRetryCount = 0
                            Toast.makeText(this@MainActivity, "Voice error — try again", Toast.LENGTH_SHORT).show()
                        }
                    }
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        // No speech detected — silently reset, user can tap again
                        Toast.makeText(this@MainActivity, "🎤 No speech detected — try again", Toast.LENGTH_SHORT).show()
                    }
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        Toast.makeText(this@MainActivity, "⏱ Timed out — tap mic and speak clearly", Toast.LENGTH_SHORT).show()
                    }
                    SpeechRecognizer.ERROR_AUDIO -> {
                        Toast.makeText(this@MainActivity, "Audio error — check mic permissions", Toast.LENGTH_SHORT).show()
                    }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        // On physical devices, some OEMs require network for the default recognizer.
                        // Inform user clearly.
                        Toast.makeText(this@MainActivity, "Voice needs network on this device", Toast.LENGTH_LONG).show()
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Toast.makeText(this@MainActivity, "Microphone permission denied", Toast.LENGTH_SHORT).show()
                    }
                    else -> Toast.makeText(this@MainActivity, "Voice error ($error)", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        try {
            speechRecognizer?.startListening(intent)
            Toast.makeText(this, "🎙 Listening...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("Voice", "startListening failed: ${e.message}")
            Toast.makeText(this, "Could not start voice input", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Image insertion in editor ─────────────────────────────────────────────

    private fun setupImageButton() {
        editorBinding.insertImageButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
            imagePickerLauncher.launch(intent)
        }
    }

    private fun insertBase64ImageIntoEditor(base64: String) {
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } ?: run {
                    Toast.makeText(this@MainActivity, "Could not decode image", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // Switch to editor
                binding.appBarMain.contentMain.bottomNavView.selectedItemId = R.id.nav_editor
                editorBinding.root.visibility = View.VISIBLE
                aiChatBinding.root.visibility = View.GONE
                binding.appBarMain.toolbarTitle?.text = "NBH Editor"

                val richEdit = editorBinding.textArea as? RichEditText ?: return@launch
                val cursor = richEdit.selectionStart.coerceAtLeast(0)
                richEdit.insertImageAtCursor(bitmap, cursor)
                Toast.makeText(this@MainActivity, "✓ Image inserted into editor", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to insert image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun insertImageIntoEditor(uri: Uri) {
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(uri) ?: return@withContext null
                    BitmapFactory.decodeStream(stream).also { stream.close() }
                } ?: run {
                    Toast.makeText(this@MainActivity, "Could not load image", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val richEdit = editorBinding.textArea as? RichEditText ?: return@launch
                val cursor = richEdit.selectionStart.coerceAtLeast(0)
                richEdit.insertImageAtCursor(bitmap, cursor)
                Toast.makeText(this@MainActivity, "Image inserted", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to insert image", Toast.LENGTH_SHORT).show()
            }
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

        val imageKeywords = listOf("make image", "generate image", "create image", "draw", "make a picture", "generate a picture", "show image", "make photo", "create photo")
        val isImageRequest = aiChatBinding.imageGenChip.isChecked || imageKeywords.any { query.lowercase().contains(it) }

        val userMsg = ChatMessage("user", query)
        chatAdapter.addMessage(userMsg)
        if (memoryEnabled) chatHistory.add(userMsg)
        aiChatBinding.queryEditText.text.clear()
        aiChatBinding.emptyState.visibility = View.GONE
        aiChatBinding.chatRecyclerView.visibility = View.VISIBLE
        aiChatBinding.typingRow.visibility = View.VISIBLE
        scrollChatToBottom()

        lifecycleScope.launch {
            try {
                if (isImageRequest) {
                    val base64 = callImageGen(query)
                    aiChatBinding.typingRow.visibility = View.GONE
                    if (base64 != null) {
                        chatAdapter.addImageMessage(ChatAdapter.ImageMessage(query, base64))
                        scrollChatToBottom()
                    } else {
                        showChatError("Image generation failed. Try again.")
                    }
                } else {
                    val aiResponse = callAIWithHistory(query, maxTokens = 1024)
                    aiChatBinding.typingRow.visibility = View.GONE
                    if (aiResponse != null) {
                        val aiMsg = ChatMessage("assistant", aiResponse)
                        chatAdapter.addMessage(aiMsg)
                        if (memoryEnabled) {
                            chatHistory.add(aiMsg)
                            saveCurrentChat()
                        }
                        scrollChatToBottom()
                    } else {
                        showChatError("No response. Try again.")
                    }
                }
            } catch (e: Exception) {
                aiChatBinding.typingRow.visibility = View.GONE
                showChatError("Error: ${e.message?.take(60)}")
            }
        }
    }

    private val HF_API_KEY = ""

    private suspend fun callImageGen(prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf("inputs" to prompt))
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://router.huggingface.co/hf-inference/models/black-forest-labs/FLUX.1-schnell")
                .post(body)
                .addHeader("Authorization", "Bearer $HF_API_KEY")
                .build()
            val hfClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            hfClient.newCall(request).execute().use { response ->
                Log.d("ImageGen", "HF code=${response.code} type=${response.body?.contentType()}")
                if (!response.isSuccessful) {
                    Log.e("ImageGen", "HF error: ${response.body?.string()?.take(200)}")
                    return@withContext null
                }
                val bytes = response.body?.bytes() ?: return@withContext null
                if (bytes.size < 100) return@withContext null
                android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
            }
        } catch (e: Exception) {
            Log.e("ImageGen", "Error: ${e.message}")
            null
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
        editorBinding.undoButton.setOnClickListener {
            performUndo()
        }
        
        editorBinding.textArea.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!isUndoing && s != null) {
                    undoStack.add(s.toString())
                    if (undoStack.size > 50) undoStack.removeAt(0)
                }
            }
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

        editorBinding.textArea.setOnLongClickListener { v ->
            val et = v as android.widget.EditText
            val offset = getOffsetForPosition(et, lastTouchX, lastTouchY)
            if (offset >= 0) {
                val spans = et.text.getSpans(offset, offset + 1, android.text.style.ImageSpan::class.java)
                if (spans.isNotEmpty()) {
                    startImageDrag(et, spans[0])
                    return@setOnLongClickListener true
                }
            }
            false
        }

        editorBinding.textArea.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                lastTouchX = event.x
                lastTouchY = event.y
                
                // Check for double-tap on image
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime < 300 && 
                    Math.abs(event.x - lastTapX) < 50 && 
                    Math.abs(event.y - lastTapY) < 50) {
                    val et = v as android.widget.EditText
                    val offset = getOffsetForPosition(et, event.x, event.y)
                    if (offset >= 0) {
                        val spans = et.text.getSpans(offset, offset + 1, android.text.style.ImageSpan::class.java)
                        if (spans.isNotEmpty()) {
                            openImageEditor(et, spans[0])
                            return@setOnTouchListener true
                        }
                    }
                }
                lastTapTime = currentTime
                lastTapX = event.x
                lastTapY = event.y
            }
            if (event.action == android.view.MotionEvent.ACTION_MOVE) {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            // If dragging an image, handle move and release
            if (isDraggingImage) {
                handleImageDragTouch(v as android.widget.EditText, event)
                return@setOnTouchListener true
            }
            false
        }

        updateLineNumbers()
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDraggingImage = false
    private var dragSpan: android.text.style.ImageSpan? = null
    private var dragSpanStart = -1
    private var dragSpanEnd = -1
    private var dragThumb: android.widget.ImageView? = null
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private fun startImageDrag(et: android.widget.EditText, span: android.text.style.ImageSpan) {
        val drawable = span.drawable as? android.graphics.drawable.BitmapDrawable ?: return
        dragSpan = span
        dragSpanStart = et.text.getSpanStart(span)
        dragSpanEnd = et.text.getSpanEnd(span)
        isDraggingImage = true

        // Temporarily replace the image span with a placeholder to prevent overlap
        val placeholder = "◆"
        et.text.replace(dragSpanStart, dragSpanEnd, placeholder)
        dragSpanEnd = dragSpanStart + placeholder.length

        // Show floating thumbnail following the finger
        val thumb = android.widget.ImageView(this).apply {
            setImageBitmap(drawable.bitmap)
            alpha = 0.85f
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        val size = (resources.displayMetrics.density * 80).toInt()
        val params = android.widget.FrameLayout.LayoutParams(size, size).apply {
            leftMargin = lastTouchX.toInt() - size / 2
            topMargin = lastTouchY.toInt() - size / 2
        }
        val overlay = editorBinding.overlayView.parent as? android.view.ViewGroup ?: return
        overlay.addView(thumb, params)
        dragThumb = thumb

        android.os.Vibrator::class.java.let {
            @Suppress("DEPRECATION")
            (getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(40)
        }
        Toast.makeText(this, "Drag to move image", Toast.LENGTH_SHORT).show()
    }

    private fun handleImageDragTouch(et: android.widget.EditText, event: android.view.MotionEvent) {
        val size = (resources.displayMetrics.density * 80).toInt()
        when (event.action) {
            android.view.MotionEvent.ACTION_MOVE -> {
                lastTouchX = event.x
                lastTouchY = event.y
                dragThumb?.let { thumb ->
                    val lp = thumb.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
                    lp.leftMargin = event.rawX.toInt() - size / 2
                    lp.topMargin = event.rawY.toInt() - size / 2
                    thumb.layoutParams = lp
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                isDraggingImage = false
                // Remove thumbnail
                val overlay = editorBinding.overlayView.parent as? android.view.ViewGroup
                dragThumb?.let { overlay?.removeView(it) }
                dragThumb = null

                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    val span = dragSpan ?: return
                    val newOffset = getOffsetForPosition(et, event.x, event.y)
                        .coerceIn(0, et.text.length)

                    // Create new image span
                    val ss = android.text.SpannableString(" ")
                    ss.setSpan(
                        android.text.style.ImageSpan(span.drawable, android.text.style.ImageSpan.ALIGN_BOTTOM),
                        0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    // Delete placeholder from old position
                    val safeStart = dragSpanStart.coerceIn(0, et.text.length)
                    val safeEnd = dragSpanEnd.coerceIn(safeStart, et.text.length)
                    et.text.delete(safeStart, safeEnd)
                    // Insert at new position
                    val insertAt = if (newOffset > safeStart) (newOffset - (safeEnd - safeStart)).coerceAtLeast(0)
                                   else newOffset
                    et.text.insert(insertAt.coerceIn(0, et.text.length), ss)
                } else {
                    // Cancelled - restore the image at original position
                    val span = dragSpan ?: return
                    val ss = android.text.SpannableString(" ")
                    ss.setSpan(
                        android.text.style.ImageSpan(span.drawable, android.text.style.ImageSpan.ALIGN_BOTTOM),
                        0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    val safeStart = dragSpanStart.coerceIn(0, et.text.length)
                    val safeEnd = dragSpanEnd.coerceIn(safeStart, et.text.length)
                    et.text.replace(safeStart, safeEnd, ss)
                }
                dragSpan = null
                dragSpanStart = -1
                dragSpanEnd = -1
            }
        }
    }

    private fun getOffsetForPosition(et: android.widget.EditText, x: Float, y: Float): Int {
        val layout = et.layout ?: return -1
        val line = layout.getLineForVertical((y + et.scrollY - et.paddingTop).toInt())
        return layout.getOffsetForHorizontal(line, x - et.paddingLeft + et.scrollX)
    }
    
    private fun performUndo() {
        if (undoStack.isEmpty()) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            return
        }
        isUndoing = true
        val previousText = undoStack.removeAt(undoStack.size - 1)
        editorBinding.textArea.setText(previousText)
        editorBinding.textArea.setSelection(previousText.length.coerceAtMost(previousText.length))
        isUndoing = false
        Toast.makeText(this, "Undo", Toast.LENGTH_SHORT).show()
    }

    private fun openImageEditor(et: android.widget.EditText, span: android.text.style.ImageSpan) {
        val drawable = span.drawable as? android.graphics.drawable.BitmapDrawable ?: return
        val bmp = drawable.bitmap ?: return
        val spanStart = et.text.getSpanStart(span)
        val spanEnd   = et.text.getSpanEnd(span)

        ImageEditBottomSheet(bmp) { edited, caption ->
            lifecycleScope.launch(Dispatchers.IO) {
                val newDrawable = android.graphics.drawable.BitmapDrawable(resources, edited).apply {
                    setBounds(0, 0, edited.width, edited.height)
                }
                val newSpan = android.text.style.ImageSpan(newDrawable, android.text.style.ImageSpan.ALIGN_BOTTOM)
                withContext(Dispatchers.Main) {
                    // Replace old span
                    val ss = android.text.SpannableString(" ")
                    ss.setSpan(newSpan, 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    et.text.replace(spanStart, spanEnd, ss)
                    // Insert caption below if provided
                    if (caption.isNotEmpty()) {
                        val insertAt = (spanStart + 1).coerceAtMost(et.text.length)
                        et.text.insert(insertAt, "\n_${caption}_\n")
                    }
                }
            }
        }.show(supportFragmentManager, "img_edit")
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

    // Builds a prompt with conversation history for context-aware replies
    private suspend fun callAIWithHistory(prompt: String, maxTokens: Int = 512): String? {
        if (!memoryEnabled || chatHistory.size <= 1) return callAI(prompt, maxTokens)
        // Use last 10 turns max to avoid token overflow
        val context = chatHistory.takeLast(10).joinToString("\n") {
            "${if (it.role == "user") "User" else "Assistant"}: ${it.content}"
        }
        val fullPrompt = "Previous conversation:\n$context\n\nUser: $prompt"
        return callAI(fullPrompt, maxTokens)
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
        editorBinding.textArea.text?.insert(pos, suggestion)
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
        val selectedText = if (isSelection) editText.text?.substring(start, end) ?: ""
                           else editText.text?.toString() ?: ""

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
                        val editable = editText.text ?: throw Exception("null editable")
                        if (isSelection) {
                            val len = editable.length
                            val safeStart = start.coerceIn(0, len)
                            val safeEnd = end.coerceIn(safeStart, len)
                            editable.replace(safeStart, safeEnd, result)
                        } else {
                            val cursorPos = editText.selectionStart
                            editable.replace(0, editable.length, result)
                            editText.setSelection(cursorPos.coerceIn(0, editable.length))
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
        val et = editorBinding.textArea
        val lineCount = et.lineCount.coerceAtLeast(1)
        val current = editorBinding.lineNumbersVBox.childCount
        if (current == lineCount) return
        if (lineCount > current) {
            for (i in (current + 1)..lineCount) {
                val tv = TextView(this).apply {
                    // Check if this line contains only an ImageSpan — hide number if so
                    val layout = et.layout
                    val lineIdx = i - 1
                    val hasImageOnly = if (layout != null && lineIdx < layout.lineCount) {
                        val ls = layout.getLineStart(lineIdx)
                        val le = layout.getLineEnd(lineIdx)
                        val spans = et.text?.getSpans(ls, le, android.text.style.ImageSpan::class.java) ?: emptyArray()
                        spans.isNotEmpty() && (le - ls) <= 2
                    } else false

                    text = if (hasImageOnly) "" else i.toString()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    gravity = Gravity.END
                    setPadding(0, 0, 8, 0)
                    typeface = Typeface.MONOSPACE
                    val lineNumColor = if (isGlassMode)
                        0x88AABBFF.toInt()
                    else
                        resources.getColor(R.color.editor_line_number_text, theme)
                    setTextColor(lineNumColor)
                    if (isGlassMode) paintFlags = paintFlags or android.graphics.Paint.FAKE_BOLD_TEXT_FLAG
                    textSize = 12f
                }
                editorBinding.lineNumbersVBox.addView(tv)
            }
        } else {
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
        // Primary: content resolver DISPLAY_NAME column
        try {
            contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {}
        // Fallback: last path segment, strip everything before the last '/' or ':'
        // but only keep it if it looks like a filename (contains a dot or is non-numeric)
        val raw = android.net.Uri.decode(uri.lastPathSegment) ?: return "untitled"
        val candidate = raw.substringAfterLast('/').substringAfterLast(':')
        return if (candidate.isNotBlank() && (candidate.contains('.') || !candidate.all { it.isDigit() }))
            candidate else "untitled"
    }

    private fun updateToolbarTitle() {
        val name = if (currentFileUri != null) getFileName(currentFileUri!!) else "NBH Editor"
        val dot = if (textChanged) " ●" else ""
        val tv = binding.appBarMain.toolbarTitle ?: return
        tv.text = "$name$dot"
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.paintFlags = tv.paintFlags or android.graphics.Paint.FAKE_BOLD_TEXT_FLAG
        tv.textSize = 19f
        tv.letterSpacing = 0.02f
        when {
            isGlassMode -> { /* adaptive color applied by applyAdaptiveGlassUI */ }
            textChanged -> {
                tv.setTextColor(resources.getColor(R.color.accent_primary, theme))
                tv.setShadowLayer(0f, 0f, 0f, 0)
            }
            else -> {
                tv.setTextColor(resources.getColor(R.color.editor_text, theme))
                tv.setShadowLayer(0f, 0f, 0f, 0)
            }
        }
    }

    private fun openFileFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use {
                val content = BufferedReader(InputStreamReader(it)).readText()
                editorBinding.textArea.setText(content)
                currentFileUri = uri
                textChanged = false
                prefs.edit()
                    .remove("recovery_text")
                    .putString("last_file_uri", uri.toString())
                    .apply()
                updateLineNumbers()
                updateToolbarTitle()
                // Restore any embedded image tags back to ImageSpans
                deserializeImagesInText()
                addToRecents(uri)
                Toast.makeText(this, "Opened", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToFile(uri: Uri) {
        try {
            val text = serializeSpansToText()
            contentResolver.openOutputStream(uri, "wt")?.use {
                OutputStreamWriter(it).use { w -> w.write(text) }
                textChanged = false
                updateToolbarTitle()
            }
            addToRecents(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save", Toast.LENGTH_SHORT).show()
        }
    }

    /** Replaces ImageSpans with [img:uuid.png] tags and saves the bitmaps to cache. */
    private fun serializeSpansToText(): String {
        val et = editorBinding.textArea
        val editable = et.text ?: return et.text.toString()
        val spans = editable.getSpans(0, editable.length, android.text.style.ImageSpan::class.java)
        if (spans.isEmpty()) return editable.toString()

        val sb = android.text.SpannableStringBuilder(editable)
        for (span in spans.sortedByDescending { editable.getSpanStart(it) }) {
            val bmp = (span.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: continue
            val name = "img_${System.currentTimeMillis()}.png"
            val file = java.io.File(cacheDir, name)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val start = sb.getSpanStart(span)
            val end   = sb.getSpanEnd(span)
            if (start >= 0 && end > start) sb.replace(start, end, "[img:$name]")
        }
        return sb.toString()
    }

    /** Restores [img:filename] tags back to ImageSpans after loading. */
    private fun deserializeImagesInText() {
        val et = editorBinding.textArea
        val raw = et.text.toString()
        val pattern = Regex("\\[img:([^\\]]+)\\]")
        val matches = pattern.findAll(raw).toList().reversed() // reverse to preserve indices
        if (matches.isEmpty()) return

        for (match in matches) {
            val name = match.groupValues[1]
            val file = java.io.File(cacheDir, name)
            if (!file.exists()) continue
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: continue
            val drawable = android.graphics.drawable.BitmapDrawable(resources, bmp).apply {
                setBounds(0, 0, bmp.width, bmp.height)
            }
            val span = android.text.style.ImageSpan(drawable, android.text.style.ImageSpan.ALIGN_BOTTOM)
            val ss = android.text.SpannableString(" ")
            ss.setSpan(span, 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            et.text?.replace(match.range.first, match.range.last + 1, ss)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.drawerLayout.isDrawerOpen(GravityCompat.START) ->
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    !isHomeVisible -> showHome()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun handleOpenIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW && intent?.action != Intent.ACTION_EDIT) return
        val uri = intent.data ?: return
        try {
            // Request persistent permission if it's a content URI
            if (uri.scheme == "content") {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
            openFileFromUri(uri)
            // Switch to editor tab
            binding.appBarMain.contentMain.bottomNavView.selectedItemId = R.id.nav_editor
            editorBinding.root.visibility = View.VISIBLE
            aiChatBinding.root.visibility = View.GONE
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkForRecovery() {
        // Only recover if there's no saved file URI (user didn't open a file)
        val recovered = prefs.getString("recovery_text", null)
        val savedUri = prefs.getString("last_file_uri", null)
        
        if (!recovered.isNullOrBlank() && editorBinding.textArea.text?.isBlank() == true && savedUri == null) {
            editorBinding.textArea.setText(recovered)
            updateLineNumbers()
            Toast.makeText(this, "Recovered unsaved text", Toast.LENGTH_SHORT).show()
        }
    }
}
