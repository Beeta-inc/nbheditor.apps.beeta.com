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
    private var lastSyncSuccess = false
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

    // Sign-in request code
    private val RC_SIGN_IN = 9001
    private val RC_DRIVE_PERMISSION = 9002

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
    data class ChatHistoryItem(val type: String, val role: String? = null, val content: String? = null, val prompt: String? = null, val base64: String? = null)
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
    private val fullChatHistory = mutableListOf<Any>() // includes images
    private var memoryEnabled = false
    private var currentChatFile: java.io.File? = null

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> insertImageIntoEditor(uri) }
        }
    }

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> analyzeVideo(uri) }
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
                GlassBlurHelper.enableWindowBlur(window, this, blurRadius = 120)
            }
        }

        setupVoiceButtons()
        setupImageButton()
        registerBackHandler()

        // Show first-time login dialog
        showFirstTimeLoginDialog()

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

        // Glass frosted bars - more transparent to show blur
        val barColor    = 0xCC0D1117.toInt()   // 80% opaque
        val surfaceColor = 0xBB0D1117.toInt()  // 73% opaque
        val divColor    = 0x55FFFFFF

        binding.appBarMain.toolbar.setBackgroundColor(barColor)
        binding.appBarMain.contentMain.bottomNavView.setBackgroundColor(barColor)
        editorBinding.editorToolbar.setBackgroundColor(surfaceColor)
        aiChatBinding.chatHeader.setBackgroundColor(barColor)
        aiChatBinding.inputBar.setBackgroundColor(barColor)
        editorBinding.aiSuggestionContainer.setBackgroundColor(surfaceColor)
        editorBinding.autoSaveContainer.setBackgroundColor(surfaceColor)
        binding.navView.setBackgroundColor(0xDD0D1117.toInt())

        // Dividers - brighter for glass effect
        aiChatBinding.headerDivider.setBackgroundColor(divColor)
        aiChatBinding.inputDivider.setBackgroundColor(divColor)

        // Editor area: more transparent for glass effect but ensure text is visible
        editorBinding.lineNumbersScroll.setBackgroundColor(0xCC0A0E14.toInt())
        editorBinding.textArea.setBackgroundColor(0xBB0A0E14.toInt())
        editorBinding.textArea.setTextColor(0xFFFFFFFF.toInt()) // Pure white
        editorBinding.textArea.setHintTextColor(0x88FFFFFF.toInt()) // Semi-transparent white

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
        if (!isGlassMode) return
        
        // Don't use adaptive colors for the editor text - keep it white for visibility
        // The glass effect is achieved through the background transparency
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
            // Glass mode - more transparent
            homeBinding.homeRoot.setBackgroundColor(0xBB0A0E14.toInt())
            homeBinding.homeHeader.setBackgroundColor(0xCC0D1117.toInt())
            homeBinding.searchCard.apply {
                setCardBackgroundColor(0xBB0D1117.toInt())
                cardElevation = 0f
                radius = 24f
            }
            homeBinding.homeTitle.apply {
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(8f, 0f, 3f, 0xAA000000.toInt())
            }
            homeBinding.fileCountLabel.setTextColor(resources.getColor(R.color.accent_primary, theme))
            homeBinding.sortLabel.setTextColor(0xDDFFFFFF.toInt())
            homeBinding.searchBar.apply {
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xAAFFFFFF.toInt())
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
            fullChatHistory.clear()
            currentChatFile = null
            aiChatBinding.chatRecyclerView.visibility = View.GONE
            aiChatBinding.emptyState.visibility = View.VISIBLE
            Toast.makeText(this, "✨ New chat started", Toast.LENGTH_SHORT).show()
        }

        aiChatBinding.clearChatBtn.setOnClickListener {
            if (memoryEnabled) saveCurrentChat()
            chatAdapter.clearMessages()
            chatHistory.clear()
            fullChatHistory.clear()
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

        aiChatBinding.videoAnalysisChip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                aiChatBinding.imageGenChip.isChecked = false
                pickVideo()
            }
        }

        aiChatBinding.imageGenChip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                aiChatBinding.videoAnalysisChip.isChecked = false
            }
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
        if (chatHistory.isEmpty() && fullChatHistory.isEmpty()) return
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
            // Convert all messages to ChatHistoryItem
            val historyItems = chatAdapter.getMessages().map { msg ->
                when (msg) {
                    is ChatMessage -> ChatHistoryItem("text", msg.role, msg.content)
                    is ChatAdapter.ImageMessage -> ChatHistoryItem("image", prompt = msg.prompt, base64 = msg.base64)
                    else -> null
                }
            }.filterNotNull()
            val jsonContent = gson.toJson(historyItems)
            file.writeText(jsonContent)
            
            // Sync to cloud if signed in
            if (GoogleSignInHelper.isSignedIn(this)) {
                lifecycleScope.launch {
                    GoogleSignInHelper.syncChatToCloud(this@MainActivity, jsonContent, file.name)
                }
            }
        } catch (_: Exception) {}
    }

    // ── Memory: load a saved chat ─────────────────────────────────────────────
    private fun loadChat(file: java.io.File) {
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val type = object : com.google.gson.reflect.TypeToken<List<ChatHistoryItem>>() {}.type
                    gson.fromJson<List<ChatHistoryItem>>(file.readText(), type)
                }
                
                chatHistory.clear()
                fullChatHistory.clear()
                chatAdapter.clearMessages()
                
                loaded.forEach { item ->
                    when (item.type) {
                        "text" -> {
                            val msg = ChatMessage(item.role ?: "assistant", item.content ?: "")
                            chatAdapter.addMessage(msg)
                            chatHistory.add(msg)
                            fullChatHistory.add(msg)
                        }
                        "image" -> {
                            val imgMsg = ChatAdapter.ImageMessage(item.prompt ?: "", item.base64 ?: "")
                            chatAdapter.addImageMessage(imgMsg)
                            fullChatHistory.add(imgMsg)
                        }
                    }
                }
                
                currentChatFile = file
                aiChatBinding.chatRecyclerView.visibility = View.VISIBLE
                aiChatBinding.emptyState.visibility = View.GONE
                scrollChatToBottom()
                Toast.makeText(this@MainActivity, "Chat loaded", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to load chat: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
    private var voiceAnimator: android.animation.ObjectAnimator? = null
    private var isRecognizerReady = true
    private var recognizerLock = Any()
    private val voiceHandler = Handler(Looper.getMainLooper())
    private var cleanupRunnable: Runnable? = null
    private var lastVoiceActivityTime = 0L
    private var autoStopRunnable: Runnable? = null
    private val AUTO_STOP_TIMEOUT = 90000L // 1.5 minutes in milliseconds

    private fun setupVoiceButtons() {
        editorBinding.editorVoiceButton.setOnClickListener {
            toggleVoiceInput(editorBinding.textArea)
        }
        aiChatBinding.voiceButton.setOnClickListener {
            toggleVoiceInput(aiChatBinding.queryEditText)
        }
    }

    private fun stopVoiceInput() {
        synchronized(recognizerLock) {
            if (!isListening) return
            
            isListening = false
            cleanupRunnable?.let { voiceHandler.removeCallbacks(it) }
            autoStopRunnable?.let { voiceHandler.removeCallbacks(it) }
            
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
            
            setMicActive(false)
            updateVoiceButtonIcon(false)
            speechTarget?.hint = ""
            stopVoiceAnimation()
            
            // Show "Voice mode turned off" message briefly before hiding
            showVoiceStatusTurnedOff()
            
            voiceHandler.postDelayed({
                hideVoiceStatus()
            }, 1500)
        }
    }

    private fun toggleVoiceInput(target: EditText) {
        if (isListening) {
            stopVoiceInput()
        } else {
            startVoiceInput(target)
        }
    }

    private fun updateVoiceButtonIcon(isActive: Boolean) {
        val iconRes = if (isActive) R.drawable.ic_mic else R.drawable.ic_mic_off
        aiChatBinding.voiceButton.setImageResource(iconRes)
        editorBinding.editorVoiceButton.setImageResource(iconRes)
    }

    private fun showVoiceStatusNoSpeech() {
        if (!isListening) return
        
        aiChatBinding.voiceStatusIndicator.visibility = View.VISIBLE
        aiChatBinding.voiceStatusText.text = "🎤 Listening..."
        aiChatBinding.voiceStatusText.visibility = View.VISIBLE
        aiChatBinding.voiceEqualizer.visibility = View.GONE
        aiChatBinding.voiceEqualizer.stopAnimation()
        
        editorBinding.editorVoiceStatusIndicator.visibility = View.VISIBLE
        editorBinding.editorVoiceStatusText.text = "🎤 Listening..."
        editorBinding.editorVoiceStatusText.visibility = View.VISIBLE
        editorBinding.editorVoiceEqualizer.visibility = View.GONE
        editorBinding.editorVoiceEqualizer.stopAnimation()
    }

    private fun showVoiceStatusSpeaking() {
        if (!isListening) return
        
        aiChatBinding.voiceStatusIndicator.visibility = View.VISIBLE
        aiChatBinding.voiceStatusText.text = "✓ Voice detected"
        aiChatBinding.voiceStatusText.visibility = View.VISIBLE
        aiChatBinding.voiceEqualizer.visibility = View.VISIBLE
        aiChatBinding.voiceEqualizer.startAnimation()
        
        editorBinding.editorVoiceStatusIndicator.visibility = View.VISIBLE
        editorBinding.editorVoiceStatusText.text = "✓ Voice detected"
        editorBinding.editorVoiceStatusText.visibility = View.VISIBLE
        editorBinding.editorVoiceEqualizer.visibility = View.VISIBLE
        editorBinding.editorVoiceEqualizer.startAnimation()
    }
    
    private fun showVoiceStatusTurnedOff() {
        aiChatBinding.voiceStatusIndicator.visibility = View.VISIBLE
        aiChatBinding.voiceStatusText.text = "Voice mode turned off"
        aiChatBinding.voiceStatusText.visibility = View.VISIBLE
        aiChatBinding.voiceEqualizer.visibility = View.GONE
        aiChatBinding.voiceEqualizer.stopAnimation()
        
        editorBinding.editorVoiceStatusIndicator.visibility = View.VISIBLE
        editorBinding.editorVoiceStatusText.text = "Voice mode turned off"
        editorBinding.editorVoiceStatusText.visibility = View.VISIBLE
        editorBinding.editorVoiceEqualizer.visibility = View.GONE
        editorBinding.editorVoiceEqualizer.stopAnimation()
    }

    private fun hideVoiceStatus() {
        aiChatBinding.voiceStatusIndicator.visibility = View.GONE
        aiChatBinding.voiceEqualizer.stopAnimation()
        editorBinding.editorVoiceStatusIndicator.visibility = View.GONE
        editorBinding.editorVoiceEqualizer.stopAnimation()
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
        synchronized(recognizerLock) {
            isRecognizerReady = false
            cleanupRunnable?.let { voiceHandler.removeCallbacks(it) }
            
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
            
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
            
            voiceHandler.postDelayed({
                try {
                    speechRecognizer?.destroy()
                } catch (_: Exception) {}
                speechRecognizer = null
            }, 100)
            
            voiceHandler.postDelayed({
                synchronized(recognizerLock) {
                    isRecognizerReady = true
                }
            }, 700)
        }
    }

    private fun startVoiceInput(target: EditText) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }
        
        // Warn if running on emulator (audio may be unreliable)
        if (Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator")) {
            val isFirstTime = prefs.getBoolean("emulator_audio_warned", false)
            if (!isFirstTime) {
                Toast.makeText(this, "⚠ Emulator audio may be unreliable. Test on real device for best results.", Toast.LENGTH_LONG).show()
                prefs.edit().putBoolean("emulator_audio_warned", true).apply()
            }
        }
        
        synchronized(recognizerLock) {
            if (isListening) {
                stopVoiceInput()
                return
            }
            
            if (!isRecognizerReady) {
                Toast.makeText(this, "Please wait...", Toast.LENGTH_SHORT).show()
                return
            }
            
            speechTarget = target
            destroySpeechRecognizer()
            
            voiceHandler.postDelayed({
                if (isRecognizerReady) {
                    doStartListening()
                }
            }, 800)
        }
    }

    private fun doStartListening() {
        synchronized(recognizerLock) {
            if (!isRecognizerReady || isListening) return
            
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_LONG).show()
                return
            }
            
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not initialize voice", Toast.LENGTH_SHORT).show()
                isRecognizerReady = true
                return
            }
            
            // Initialize last activity time and schedule auto-stop
            lastVoiceActivityTime = System.currentTimeMillis()
            scheduleAutoStop()
            
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    runOnUiThread {
                        isListening = true
                        setMicActive(true)
                        updateVoiceButtonIcon(true)
                        showVoiceStatusNoSpeech()
                        updateVoiceActivity()
                    }
                }
                
                override fun onBeginningOfSpeech() {
                    runOnUiThread {
                        showVoiceStatusSpeaking()
                        updateVoiceActivity()
                    }
                }
                
                override fun onRmsChanged(rmsdB: Float) {
                    // Update visual feedback based on volume
                    runOnUiThread {
                        if (!isListening) return@runOnUiThread
                        
                        if (rmsdB > 3.0f) {
                            showVoiceStatusSpeaking()
                            updateVoiceActivity()
                        } else {
                            showVoiceStatusNoSpeech()
                        }
                    }
                }
                
                override fun onBufferReceived(buffer: ByteArray?) {}
                
                override fun onEndOfSpeech() {
                    runOnUiThread {
                        // Keep listening - don't restart, just ignore this callback
                        if (isListening) {
                            showVoiceStatusNoSpeech()
                        }
                    }
                }
                
                override fun onResults(results: android.os.Bundle) {
                    runOnUiThread {
                        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim()
                        
                        if (!text.isNullOrBlank()) {
                            updateVoiceActivity()
                            val et = speechTarget
                            if (et != null) {
                                val pos = et.selectionStart.coerceAtLeast(0)
                                val textToInsert = if (pos > 0 && et.text?.getOrNull(pos - 1)?.isWhitespace() == false) {
                                    " $text"
                                } else {
                                    text
                                }
                                
                                try {
                                    et.text?.insert(pos, textToInsert) ?: et.setText(textToInsert)
                                } catch (_: Exception) {}
                            }
                        }
                        
                        // Restart immediately - no delay
                        if (isListening) {
                            showVoiceStatusNoSpeech()
                            restartListening()
                        }
                    }
                }
                
                override fun onPartialResults(partial: android.os.Bundle?) {
                    runOnUiThread {
                        val text = partial
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim()
                        if (!text.isNullOrBlank()) {
                            showVoiceStatusSpeaking()
                            updateVoiceActivity()
                            // Show partial text in hint
                            speechTarget?.hint = "Hearing: ${text.take(40)}..."
                        }
                    }
                }
                
                override fun onError(error: Int) {
                    runOnUiThread {
                        when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> {
                                Toast.makeText(this@MainActivity, "Audio error", Toast.LENGTH_SHORT).show()
                                stopVoiceInput()
                                return@runOnUiThread
                            }
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                                Toast.makeText(this@MainActivity, "Microphone permission required", Toast.LENGTH_SHORT).show()
                                stopVoiceInput()
                                return@runOnUiThread
                            }
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                                // Normal errors - restart with small delay to avoid rapid loops
                            }
                        }
                        
                        // Small delay only on errors to prevent rapid error loops
                        if (isListening) {
                            showVoiceStatusNoSpeech()
                            voiceHandler.postDelayed({
                                if (isListening) {
                                    restartListening()
                                }
                            }, 100)
                        }
                    }
                }
                
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 90000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 90000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 90000)
                putExtra("android.speech.extra.DICTATION_MODE", true)
            }
            
            try {
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not start voice input: ${e.message}", Toast.LENGTH_SHORT).show()
                scheduleCleanup()
            }
        }
    }
    
    private fun restartListening() {
        if (!isListening) return
        
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 90000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 90000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 90000)
                putExtra("android.speech.extra.DICTATION_MODE", true)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            if (isListening) {
                voiceHandler.postDelayed({
                    if (isListening) {
                        try {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 90000)
                                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 90000)
                                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 90000)
                                putExtra("android.speech.extra.DICTATION_MODE", true)
                            }
                            speechRecognizer?.startListening(intent)
                        } catch (_: Exception) {
                            stopVoiceInput()
                        }
                    }
                }, 1000)
            }
        }
    }
    
    private fun updateVoiceActivity() {
        lastVoiceActivityTime = System.currentTimeMillis()
        scheduleAutoStop()
    }
    
    private fun scheduleAutoStop() {
        autoStopRunnable?.let { voiceHandler.removeCallbacks(it) }
        autoStopRunnable = Runnable {
            if (isListening) {
                val elapsed = System.currentTimeMillis() - lastVoiceActivityTime
                if (elapsed >= AUTO_STOP_TIMEOUT) {
                    Toast.makeText(this, "Voice mode auto-stopped (no activity)", Toast.LENGTH_SHORT).show()
                    stopVoiceInput()
                } else {
                    // Check again after remaining time
                    scheduleAutoStop()
                }
            }
        }
        voiceHandler.postDelayed(autoStopRunnable!!, AUTO_STOP_TIMEOUT)
    }
    
    private fun scheduleCleanup() {
        cleanupRunnable?.let { voiceHandler.removeCallbacks(it) }
        cleanupRunnable = Runnable { destroySpeechRecognizer() }
        voiceHandler.postDelayed(cleanupRunnable!!, 300)
    }
    
    private fun scheduleRetry() {
        cleanupRunnable?.let { voiceHandler.removeCallbacks(it) }
        destroySpeechRecognizer()
        voiceHandler.postDelayed({
            if (!isListening && isRecognizerReady) {
                Toast.makeText(this, "Retrying...", Toast.LENGTH_SHORT).show()
                doStartListening()
            }
        }, 1000)
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
        if (memoryEnabled) {
            chatHistory.add(userMsg)
            fullChatHistory.add(userMsg)
        }
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
                        val imgMsg = ChatAdapter.ImageMessage(query, base64)
                        chatAdapter.addImageMessage(imgMsg)
                        if (memoryEnabled) {
                            fullChatHistory.add(imgMsg)
                            saveCurrentChat()
                        }
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
                            fullChatHistory.add(aiMsg)
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
        
        // Add sign-in button dynamically
        val signInItem = menu.add(0, R.id.nav_sign_in, 0, "Sign In")
        signInItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        
        if (GoogleSignInHelper.isSignedIn(this)) {
            // Load and show profile picture if signed in
            val photoUrl = GoogleSignInHelper.getUserPhotoUrl(this)
            if (photoUrl != null) {
                lifecycleScope.launch {
                    try {
                        val bitmap = loadProfilePicture(photoUrl)
                        if (bitmap != null) {
                            val circularBitmap = getCircularBitmap(bitmap)
                            val drawable = android.graphics.drawable.BitmapDrawable(resources, circularBitmap)
                            signInItem.icon = drawable
                        } else {
                            signInItem.setIcon(R.drawable.ic_account_circle)
                        }
                    } catch (e: Exception) {
                        signInItem.setIcon(R.drawable.ic_account_circle)
                    }
                }
            } else {
                signInItem.setIcon(R.drawable.ic_account_circle)
            }
            signInItem.title = "Account"
        } else {
            signInItem.setIcon(R.drawable.ic_account_circle)
        }
        
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
        updateToolbarTitle() // Update cloud icon after save
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
        val cloudIcon = if (GoogleSignInHelper.isSignedIn(this)) " ☁" else ""
        val tv = binding.appBarMain.toolbarTitle ?: return
        
        val displayText = "$name$dot$cloudIcon"
        val spannable = android.text.SpannableString(displayText)
        
        // Color the cloud icon based on sync status
        val cloudStart = displayText.indexOf("☁")
        if (cloudStart >= 0) {
            val cloudColor = if (GoogleSignInHelper.isSignedIn(this)) {
                if (lastSyncSuccess) {
                    0xFF4CAF50.toInt() // Green - synced successfully
                } else {
                    0xFFFF9800.toInt() // Orange - signed in but not synced yet
                }
            } else {
                0xFFF44336.toInt() // Red - not signed in
            }
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(cloudColor),
                cloudStart, cloudStart + 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        
        tv.text = spannable
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
        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use {
                        BufferedReader(InputStreamReader(it)).readText()
                    }
                }
                
                if (content == null) {
                    Toast.makeText(this@MainActivity, "Failed to read file", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                editorBinding.textArea.setText(content)
                currentFileUri = uri
                textChanged = false
                prefs.edit()
                    .remove("recovery_text")
                    .putString("last_file_uri", uri.toString())
                    .apply()
                updateLineNumbers()
                updateToolbarTitle()
                deserializeImagesInText()
                addToRecents(uri)
                Toast.makeText(this@MainActivity, "Opened", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to open file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
            
            // Auto-sync to cloud if signed in
            if (GoogleSignInHelper.isSignedIn(this)) {
                val fileName = getFileName(uri)
                lifecycleScope.launch {
                    try {
                        val success = GoogleSignInHelper.syncFileToCloud(this@MainActivity, text, fileName)
                        if (!success) {
                            // Check if we need Drive permission
                            val authException = GoogleSignInHelper.getLastAuthException()
                            if (authException != null) {
                                withContext(Dispatchers.Main) {
                                    startActivityForResult(authException.intent, RC_DRIVE_PERMISSION)
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            lastSyncSuccess = success
                            updateToolbarTitle()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            lastSyncSuccess = false
                            updateToolbarTitle()
                        }
                    }
                }
            }
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
            R.id.nav_settings -> {
                // Navigate to settings fragment
                val settingsFragment = com.beeta.nbheditor.ui.settings.SettingsFragment()
                supportFragmentManager.beginTransaction()
                    .replace(binding.appBarMain.contentMain.fragmentContainer.id, settingsFragment)
                    .addToBackStack(null)
                    .commit()
                binding.appBarMain.contentMain.fragmentContainer.visibility = View.VISIBLE
                binding.appBarMain.contentMain.homeContainer?.visibility = View.GONE
                binding.appBarMain.contentMain.bottomNavView?.visibility = View.GONE
                binding.appBarMain.toolbarTitle?.text = "Settings"
            }
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
            R.id.nav_sign_in -> showAccountDialog()
            R.id.nav_about -> showAboutDialog()
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

    // ── Video Analysis ────────────────────────────────────────────────────────
    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "video/*"
        }
        videoPickerLauncher.launch(intent)
    }

    private fun analyzeVideo(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                aiChatBinding.typingRow.visibility = View.VISIBLE
                val query = aiChatBinding.queryEditText.text.toString().trim()
                    .ifBlank { "Analyze this video in detail and describe what you see" }
                
                val userMsg = ChatMessage("user", "$query [Video attached]")
                chatAdapter.addMessage(userMsg)
                if (memoryEnabled) {
                    chatHistory.add(userMsg)
                    fullChatHistory.add(userMsg)
                }
                aiChatBinding.queryEditText.text.clear()
                aiChatBinding.emptyState.visibility = View.GONE
                aiChatBinding.chatRecyclerView.visibility = View.VISIBLE
                scrollChatToBottom()

                // Extract frames from video (5 frames for better analysis)
                val frames = extractVideoFrames(uri, maxFrames = 5)
                if (frames.isEmpty()) {
                    aiChatBinding.typingRow.visibility = View.GONE
                    showChatError("Could not extract video frames. Please try a different video.")
                    return@launch
                }

                Log.d("VideoAnalysis", "Analyzing ${frames.size} frames")

                // Try Gemini first (best for video/image analysis)
                var response = callVideoAnalysisGemini(query, frames)
                
                // Fallback to Hugging Face
                if (response == null) {
                    Log.d("VideoAnalysis", "Gemini failed, trying HuggingFace")
                    response = callVideoAnalysisHF(query, frames)
                }
                
                // Final fallback to OpenRouter
                if (response == null) {
                    Log.d("VideoAnalysis", "HuggingFace failed, trying OpenRouter")
                    response = callVideoAnalysisOR(query, frames)
                }

                aiChatBinding.typingRow.visibility = View.GONE
                aiChatBinding.videoAnalysisChip.isChecked = false

                if (response != null) {
                    val aiMsg = ChatMessage("assistant", response)
                    chatAdapter.addMessage(aiMsg)
                    if (memoryEnabled) {
                        chatHistory.add(aiMsg)
                        fullChatHistory.add(aiMsg)
                        saveCurrentChat()
                    }
                    scrollChatToBottom()
                } else {
                    showChatError("Video analysis failed. Please try again or use a shorter video.")
                }
            } catch (e: Exception) {
                Log.e("VideoAnalysis", "Analysis error: ${e.message}", e)
                aiChatBinding.typingRow.visibility = View.GONE
                aiChatBinding.videoAnalysisChip.isChecked = false
                showChatError("Error: ${e.message?.take(60)}")
            }
        }
    }

    private suspend fun extractVideoFrames(uri: android.net.Uri, maxFrames: Int = 5): List<String> = withContext(Dispatchers.IO) {
        val frames = mutableListOf<String>()
        var retriever: android.media.MediaMetadataRetriever? = null
        try {
            retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(this@MainActivity, uri)
            
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            
            Log.d("VideoAnalysis", "Video duration: ${duration}ms, size: ${width}x${height}")
            
            if (duration > 0) {
                // Extract frames at strategic points: start, middle sections, and end
                val interval = duration / (maxFrames + 1)
                val extractionPoints = mutableListOf<Long>()
                
                // Add start frame (skip first 500ms to avoid black frames)
                extractionPoints.add(500L)
                
                // Add evenly distributed frames
                for (i in 1..maxFrames - 2) {
                    extractionPoints.add(interval * i)
                }
                
                // Add end frame (500ms before end)
                extractionPoints.add((duration - 500).coerceAtLeast(1000L))
                
                extractionPoints.take(maxFrames).forEach { timeMs ->
                    try {
                        val timeUs = timeMs * 1000
                        val bitmap = retriever.getFrameAtTime(
                            timeUs, 
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                        
                        if (bitmap != null) {
                            // Resize to max 1024px on longest side for better quality
                            val maxDim = 1024
                            val scale = maxDim.toFloat() / Math.max(bitmap.width, bitmap.height)
                            val newWidth = (bitmap.width * scale).toInt()
                            val newHeight = (bitmap.height * scale).toInt()
                            
                            val resized = android.graphics.Bitmap.createScaledBitmap(
                                bitmap, newWidth, newHeight, true
                            )
                            
                            val stream = java.io.ByteArrayOutputStream()
                            resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
                            val base64 = android.util.Base64.encodeToString(
                                stream.toByteArray(), 
                                android.util.Base64.NO_WRAP
                            )
                            
                            frames.add(base64)
                            Log.d("VideoAnalysis", "Extracted frame at ${timeMs}ms (${newWidth}x${newHeight})")
                            
                            bitmap.recycle()
                            resized.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e("VideoAnalysis", "Failed to extract frame at ${timeMs}ms: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VideoAnalysis", "Frame extraction error: ${e.message}", e)
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {}
        }
        
        Log.d("VideoAnalysis", "Extracted ${frames.size} frames")
        frames
    }

    private suspend fun callVideoAnalysisGemini(query: String, frames: List<String>): String? = withContext(Dispatchers.IO) {
        try {
            Log.d("VideoAnalysisGemini", "Analyzing ${frames.size} frames with Gemini")
            
            // Build parts array with text and images
            val parts = mutableListOf<Map<String, Any>>()
            parts.add(mapOf("text" to "$query\n\nAnalyze these video frames in sequence and provide a detailed description:"))
            
            frames.forEachIndexed { idx, frame ->
                parts.add(mapOf(
                    "inline_data" to mapOf(
                        "mime_type" to "image/jpeg",
                        "data" to frame
                    )
                ))
            }
            
            val requestBody = mapOf(
                "contents" to listOf(
                    mapOf("parts" to parts)
                )
            )
            
            val body = gson.toJson(requestBody)
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent?key=$GEMINI_API_KEY")
                .post(body)
                .build()
            
            val geminiClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            
            geminiClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d("VideoAnalysisGemini", "Response code: ${response.code}")
                
                if (!response.isSuccessful) {
                    Log.e("VideoAnalysisGemini", "Error: $responseBody")
                    return@withContext null
                }
                
                val result = gson.fromJson(responseBody, GeminiResponse::class.java)
                val text = result.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                
                if (text != null) {
                    Log.d("VideoAnalysisGemini", "Success: ${text.take(100)}")
                }
                
                text
            }
        } catch (e: Exception) {
            Log.e("VideoAnalysisGemini", "Error: ${e.message}", e)
            null
        }
    }

    private suspend fun callVideoAnalysisHF(query: String, frames: List<String>): String? = withContext(Dispatchers.IO) {
        try {
            Log.d("VideoAnalysisHF", "Analyzing ${frames.size} frames with HuggingFace")
            val frameDesc = mutableListOf<String>()
            val hfClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()
            
            frames.forEachIndexed { idx, frame ->
                try {
                    val body = gson.toJson(mapOf(
                        "inputs" to frame,
                        "parameters" to mapOf("max_length" to 200)
                    )).toRequestBody("application/json".toMediaType())
                    
                    val request = Request.Builder()
                        .url("https://api-inference.huggingface.co/models/Salesforce/blip-image-captioning-large")
                        .post(body)
                        .addHeader("Authorization", "Bearer $HF_API_KEY")
                        .build()
                    
                    hfClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = response.body?.string()
                            val listType = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
                            val result: List<Map<String, Any>>? = gson.fromJson(json, listType)
                            val caption = result?.firstOrNull()?.get("generated_text") as? String
                            if (!caption.isNullOrBlank()) {
                                frameDesc.add("Frame ${idx + 1}: $caption")
                                Log.d("VideoAnalysisHF", "Frame $idx: $caption")
                            }
                        } else {
                            Log.e("VideoAnalysisHF", "Frame $idx failed: ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VideoAnalysisHF", "Frame $idx error: ${e.message}")
                }
            }

            if (frameDesc.isNotEmpty()) {
                val summary = "Video Analysis (${frames.size} frames):\n\n${frameDesc.joinToString("\n\n")}\n\nBased on the frames, this video shows: ${frameDesc.joinToString(", ") { it.substringAfter(": ") }}"
                Log.d("VideoAnalysisHF", "Success: ${frameDesc.size} frames analyzed")
                summary
            } else {
                Log.e("VideoAnalysisHF", "No frames analyzed")
                null
            }
        } catch (e: Exception) {
            Log.e("VideoAnalysisHF", "Error: ${e.message}", e)
            null
        }
    }

    private suspend fun callVideoAnalysisOR(query: String, frames: List<String>): String? = withContext(Dispatchers.IO) {
        try {
            Log.d("VideoAnalysisOR", "Analyzing ${frames.size} frames with OpenRouter")
            
            // Using free vision model on OpenRouter
            val content = mutableListOf<Map<String, Any>>()
            content.add(mapOf("type" to "text", "text" to "$query\n\nAnalyze these video frames:"))
            
            frames.forEach { frame ->
                content.add(mapOf(
                    "type" to "image_url",
                    "image_url" to mapOf("url" to "data:image/jpeg;base64,$frame")
                ))
            }
            
            val messages = listOf(
                mapOf("role" to "user", "content" to content)
            )

            val body = gson.toJson(mapOf(
                "model" to "google/gemini-flash-1.5-8b:free",
                "messages" to messages,
                "max_tokens" to 1024
            )).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .post(body)
                .addHeader("Authorization", "Bearer $OPENROUTER_API_KEY")
                .addHeader("HTTP-Referer", "https://nbheditor.beeta.com")
                .addHeader("X-Title", "NBH Editor")
                .build()

            val orClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            orClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d("VideoAnalysisOR", "Response code: ${response.code}")
                
                if (response.isSuccessful) {
                    val json = gson.fromJson(responseBody, Map::class.java)
                    val text = (json["choices"] as? List<*>)?.firstOrNull()
                        ?.let { it as? Map<*, *> }
                        ?.get("message")
                        ?.let { it as? Map<*, *> }
                        ?.get("content") as? String
                    
                    if (text != null) {
                        Log.d("VideoAnalysisOR", "Success: ${text.take(100)}")
                    }
                    
                    text
                } else {
                    Log.e("VideoAnalysisOR", "Error: $responseBody")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("VideoAnalysisOR", "Error: ${e.message}", e)
            null
        }
    }
    
    // ── Google Sign-In ────────────────────────────────────────────────────────
    
    private fun showFirstTimeLoginDialog() {
        val hasAsked = prefs.getBoolean("login_dialog_shown", false)
        if (hasAsked) return
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_first_time_login, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.signInButton)
            .setOnClickListener {
                dialog.dismiss()
                startGoogleSignIn()
            }
        
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.skipButton)
            .setOnClickListener {
                dialog.dismiss()
                prefs.edit().putBoolean("login_dialog_shown", true).apply()
                Toast.makeText(this, "You can sign in later from the menu", Toast.LENGTH_LONG).show()
            }
        
        dialog.show()
    }
    
    private fun startGoogleSignIn() {
        val signInIntent = GoogleSignInHelper.getSignInClient(this).signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == RC_DRIVE_PERMISSION) {
            if (resultCode == RESULT_OK) {
                GoogleSignInHelper.clearAuthException()
                Toast.makeText(this, "Drive permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Drive permission denied", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        if (requestCode == RC_SIGN_IN) {
            val task = com.google.android.gms.auth.api.signin.GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(Exception::class.java)
                
                lifecycleScope.launch {
                    try {
                        GoogleSignInHelper.saveSignInState(this@MainActivity, account)
                        
                        // Check if Drive permission is needed
                        val authException = GoogleSignInHelper.getLastAuthException()
                        if (authException != null) {
                            startActivityForResult(authException.intent, RC_DRIVE_PERMISSION)
                        }
                        
                        prefs.edit().putBoolean("login_dialog_shown", true).apply()
                        Toast.makeText(this@MainActivity, "✓ Signed in as ${account.email}", Toast.LENGTH_LONG).show()
                        invalidateOptionsMenu() // Refresh toolbar
                        
                        // Start syncing chats and files
                        syncAllDataToCloud()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Firebase auth failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                val errorMsg = when {
                    e.message?.contains("10:") == true || e.message?.contains("10") == true -> {
                        "Sign-in not configured. Please set up Firebase.\n\nSee FIREBASE_SETUP.md for instructions."
                    }
                    e.message?.contains("12:") == true || e.message?.contains("12") == true -> {
                        "Sign-in cancelled"
                    }
                    e.message?.contains("7:") == true || e.message?.contains("7") == true -> {
                        "Network error. Check your internet connection."
                    }
                    else -> "Sign in failed: ${e.message}"
                }
                
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Sign-In Error")
                    .setMessage(errorMsg)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Setup Guide") { _, _ ->
                        showSetupGuide()
                    }
                    .show()
            }
        }
    }
    
    private suspend fun syncAllDataToCloud() {
        withContext(Dispatchers.IO) {
            try {
                // Sync all chat files
                val chatsDir = java.io.File(filesDir, "nbh_chats")
                chatsDir.listFiles()?.forEach { file ->
                    val content = file.readText()
                    GoogleSignInHelper.syncChatToCloud(this@MainActivity, content, file.name)
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "✓ Data synced to cloud", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Sync error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showAccountDialog() {
        if (!GoogleSignInHelper.isSignedIn(this)) {
            startGoogleSignIn()
            return
        }
        
        val email = GoogleSignInHelper.getUserEmail(this)
        val name = GoogleSignInHelper.getUserName(this)
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Account")
            .setMessage("Signed in as:\n$name\n$email")
            .setPositiveButton("Sync Now") { _, _ ->
                lifecycleScope.launch {
                    Toast.makeText(this@MainActivity, "Syncing...", Toast.LENGTH_SHORT).show()
                    syncAllDataToCloud()
                }
            }
            .setNegativeButton("Sign Out") { _, _ ->
                GoogleSignInHelper.signOut(this)
                Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
                invalidateOptionsMenu()
            }
            .setNeutralButton("Close", null)
            .show()
    }
    
    private fun showSetupGuide() {
        val guide = """
            📋 Firebase Setup Required (100% FREE)
            
            Error code 10 means Firebase needs to be configured.
            
            Quick Steps:
            1. Go to console.firebase.google.com
            2. Create project "NBH Editor"
            3. Add Android app
            4. Package: com.beeta.nbheditor
            5. Add SHA-1 fingerprints (tap to copy):
            
            Debug SHA-1 (with colons)
            Release SHA-1 (with colons)
            
            6. Download google-services.json
            7. Place in app/ folder
            8. Enable Authentication (Google)
            9. Enable Firestore Database
            
            ✅ Completely FREE - No credit card needed!
            
            See FIREBASE_SETUP.md for detailed guide.
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Firebase Setup (FREE)")
            .setMessage(guide)
            .setPositiveButton("Copy Debug SHA-1") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("SHA-1", "71:BC:F6:67:E9:8A:B7:3C:7A:81:D1:93:73:82:2F:F3:9D:7D:12:86")
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "✓ Debug SHA-1 copied (with colons)", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Copy Release SHA-1") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("SHA-1", "E5:99:68:01:45:96:26:EE:65:91:15:9B:C3:3E:87:26:A7:85:7E:B3")
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "✓ Release SHA-1 copied (with colons)", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Close", null)
            .show()
    }
    
    private suspend fun loadProfilePicture(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val connection = java.net.URL(url).openConnection()
            connection.doInput = true
            connection.connect()
            val input = connection.getInputStream()
            BitmapFactory.decodeStream(input)
        } catch (e: Exception) {
            Log.e("ProfilePic", "Failed to load profile picture", e)
            null
        }
    }
    
    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = 96 // Size in pixels for toolbar icon
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
        }
        
        // Draw circle
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        
        // Apply bitmap with circular mask
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        
        return output
    }
    
    private fun showAboutDialog() {
        val message = """
            📝 NBH Editor v3.1.0
            
            A blazing-fast, modern text editor built for Linux users.
            
            ✨ Features:
            • AI-Assisted Editing (Beeta AI)
            • Auto-sync to Google Cloud
            • Dark & Light Themes
            • Voice Input
            • Image Support
            • Chat Memory
            
            🔒 Privacy:
            All data synced to your personal Google account.
            No third-party access.
            
            Made with ❤️ by Beeta
            "Made For Human"
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("About NBH Editor")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
