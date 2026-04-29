package com.beeta.nbheditor

import android.Manifest
import android.app.Activity
import android.content.Context
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
import android.text.Spannable
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
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
    private var hasSyncedOnce = false
    
    // Text formatting for new text
    private var currentTypeface: Typeface = Typeface.MONOSPACE
    private var currentTextSize: Float = 16f
    
    // Undo stack
    private val undoStack = mutableListOf<String>()
    private var isUndoing = false

    private val handler = Handler(Looper.getMainLooper())
    private val typingDelayRunnable = Runnable {
        isTyping = false
        if (textChanged) performAutoSave()
    }
    private val formattingRunnable = Runnable {
        // Only apply formatting if user has stopped typing
        if (!isTyping) {
            val richEdit = editorBinding.textArea as? RichEditText
            if (richEdit?.isRichTextMode == true) {
                richEdit.applyRichTextFormatting()
            }
        }
    }

    private var aiJob: Job? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    
    // MediaPlayer for collaborative session join sound
    private var joinSoundPlayer: android.media.MediaPlayer? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    // ── AI Backend ────────────────────────────────────────────────────────────


    private val OPENROUTER_API_KEY = ""

    // Sign-in request code
    private val RC_SIGN_IN = 9001
    private val RC_DRIVE_PERMISSION = 9002

    // 6 free OpenRouter models tried in order
    private val OR_MODELS = listOf(
        "stepfun/step-3.5-flash:free",
        "mistralai/mistral-7b-instruct:free",
        "meta-llama/llama-3.2-3b-instruct:free",
        "google/gemma-3-4b-it:free",
        "qwen/qwen3-0.6b:free",
        "microsoft/phi-3-mini-128k-instruct:free"
    )


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

    // Broad file type support - removed size limitations
    private val SUPPORTED_MIME_TYPES = arrayOf(
        "text/*", "application/json", "application/xml",
        "application/javascript", "application/typescript",
        "application/x-python", "application/x-sh",
        "application/x-kotlin", "application/x-java",
        "application/rtf", "text/rtf",
        "application/octet-stream"
    )
    
    // Maximum file size for instant loading (5MB)
    private val INSTANT_LOAD_SIZE_MB = 5L
    // Maximum file size with progress dialog (no limit, but warn for very large files)
    private val WARN_SIZE_GB = 1L

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
        setupTextTypeButton()
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

        // Show home screen unless launched via "Open with" or deep link
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                // Handle collaborative session deep links
                val sessionId = when {
                    // HTTPS: https://nbheditor.pages.dev/collaborative/NE5SDT2
                    uri.scheme == "https" && uri.host == "nbheditor.pages.dev" && uri.pathSegments.size >= 2 -> {
                        if (uri.pathSegments[0] == "collaborative") uri.pathSegments[1] else null
                    }
                    // Custom scheme: nbheditor://collaborative/NE5SDT2
                    uri.scheme == "nbheditor" && uri.host == "collaborative" && uri.pathSegments.isNotEmpty() -> {
                        uri.pathSegments[0]
                    }
                    else -> null
                }
                
                if (sessionId != null && sessionId.matches(Regex("NE[A-Z0-9]{5}"))) {
                    // Handle collaborative session deep link
                    if (!GoogleSignInHelper.isSignedIn(this)) {
                        showHome()
                        Handler(Looper.getMainLooper()).postDelayed({
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Sign In Required")
                                .setMessage("You need to sign in with Google to join collaborative sessions.")
                                .setPositiveButton("Sign In") { _, _ -> 
                                    startGoogleSignIn()
                                    prefs.edit().putString("pending_session_join", sessionId).apply()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }, 500)
                    } else {
                        showEditor()
                        Handler(Looper.getMainLooper()).postDelayed({
                            joinCollaborativeSession(sessionId)
                        }, 500)
                    }
                } else {
                    // Regular file open
                    showEditor()
                }
            } else if (intent.action == Intent.ACTION_EDIT) {
                showEditor()
            } else {
                showEditor()
            }
        } else {
            showHome()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        destroySpeechRecognizer()
        if (memoryEnabled) saveCurrentChat()
        
        // Save collaborative session if in one
        if (CollaborativeSessionManager.isInSession()) {
            val currentContent = editorBinding.textArea.text.toString()
            if (currentContent.isNotBlank()) {
                lifecycleScope.launch {
                    val currentSession = CollaborativeSessionManager.getCurrentSession()
                    if (currentSession != null) {
                        val creatorName = currentSession.users.values.firstOrNull { it.userId == currentSession.creatorId }?.userName ?: "Unknown"
                        saveCollaborativeSessionFile(currentSession.sessionId, currentContent, creatorName)
                    }
                }
            }
        }
        
        if (isGlassMode) GlassTextAdapter.stop()
        stopJoinSound()
    }

    var isGlassMode = false
        private set

    fun isGlassModePublic() = isGlassMode

    fun getEditorText(): String = editorBinding.textArea.text.toString()

    suspend fun callAIPublic(prompt: String, maxTokens: Int = 512): String? = callAI(prompt, maxTokens)

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
        
        // Show loading state if signed in (cloud sync)
        if (GoogleSignInHelper.isSignedIn(this)) {
            homeBinding.emptyHomeState.visibility = View.GONE
            homeBinding.fileGrid.visibility = View.GONE
            homeBinding.loadingState.visibility = View.VISIBLE
            homeBinding.syncStatusText.text = "Syncing from cloud..."
            homeBinding.syncDetailText.text = "Loading files from all devices"
        }
        
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val list = mutableListOf<FileCardAdapter.FileEntry>()
                val processedNames = mutableSetOf<String>()
                
                // Load local files
                for (uriStr in uriStrings) {
                    try {
                        val uri = android.net.Uri.parse(uriStr)
                        val hasPerm = contentResolver.persistedUriPermissions
                            .any { it.uri == uri && it.isReadPermission }
                        if (!hasPerm) continue

                        val name = getFileName(uri)
                        if (name == "untitled" || name.isBlank()) continue
                        processedNames.add(name)

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
                
                // Load cloud files if signed in
                if (GoogleSignInHelper.isSignedIn(this@MainActivity)) {
                    try {
                        withContext(Dispatchers.Main) {
                            homeBinding.syncDetailText.text = "Checking cloud versions..."
                        }
                        
                        val cloudFiles = GoogleSignInHelper.getAllCloudFiles(this@MainActivity)
                        
                        withContext(Dispatchers.Main) {
                            homeBinding.syncDetailText.text = "Updating files (${cloudFiles.size} found)..."
                        }
                        
                        for ((fileName, content, modifiedTime) in cloudFiles) {
                            if (fileName in processedNames) {
                                // Check if cloud version is newer
                                val localSyncTime = prefs.getLong("cloud_sync_${fileName}", 0L)
                                if (modifiedTime > localSyncTime) {
                                    // Update local file if it exists
                                    val localUri = uriStrings.find { getFileName(android.net.Uri.parse(it)) == fileName }
                                    if (localUri != null) {
                                        try {
                                            val uri = android.net.Uri.parse(localUri)
                                            contentResolver.openOutputStream(uri, "wt")?.use {
                                                OutputStreamWriter(it).use { w -> w.write(content) }
                                            }
                                            prefs.edit().putLong("cloud_sync_${fileName}", modifiedTime).apply()
                                        } catch (e: Exception) {
                                            Log.e("HomeFiles", "Failed to update local file", e)
                                        }
                                    }
                                }
                                continue
                            }
                            
                            val preview = content
                                .replace(Regex("\\[img:[^\\]]+\\]"), "[image]")
                                .take(150).trim()
                            
                            val cloudUri = android.net.Uri.parse("cloud://$fileName")
                            list.add(FileCardAdapter.FileEntry(cloudUri, fileName, preview))
                            prefs.edit().putLong("cloud_time_${fileName}", modifiedTime).apply()
                        }
                    } catch (e: Exception) {
                        Log.e("HomeFiles", "Failed to load cloud files", e)
                    }
                }
                
                list.sortedBy { it.name.lowercase() }
            }

            // Hide loading, show content
            homeBinding.loadingState.visibility = View.GONE
            
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
            
            // Load collaborative sessions
            loadCollaborativeSessions()
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
    
    private lateinit var collabSessionsAdapter: FileCardAdapter
    
    private fun loadCollaborativeSessions() {
        lifecycleScope.launch {
            try {
                // Load collaborative session files from local storage
                val sessionFiles = withContext(Dispatchers.IO) {
                    val sessionsDir = java.io.File(filesDir, "collab_sessions").also { it.mkdirs() }
                    sessionsDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
                }
                
                val localFileNames = sessionFiles.map { it.name }.toMutableSet()
                val allFiles = sessionFiles.toMutableList()
                
                // Load from Google Drive if signed in
                if (GoogleSignInHelper.isSignedIn(this@MainActivity)) {
                    try {
                        val cloudFiles = withContext(Dispatchers.IO) {
                            GoogleSignInHelper.getAllCloudFiles(this@MainActivity)
                        }
                        
                        // Filter for collaborative session files
                        val collabCloudFiles = cloudFiles.filter { (fileName, _, _) ->
                            fileName.startsWith("collab_session_")
                        }
                        
                        for ((cloudFileName, content, modifiedTime) in collabCloudFiles) {
                            val localFileName = cloudFileName.removePrefix("collab_session_")
                            
                            if (localFileName in localFileNames) {
                                // Check if cloud version is newer
                                val localSyncTime = prefs.getLong("collab_sync_${localFileName}", 0L)
                                if (modifiedTime > localSyncTime) {
                                    // Update local file with cloud version
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val sessionsDir = java.io.File(filesDir, "collab_sessions")
                                            val localFile = java.io.File(sessionsDir, localFileName)
                                            localFile.writeText(content)
                                            prefs.edit().putLong("collab_sync_${localFileName}", modifiedTime).apply()
                                            Log.d("CollabSessions", "Updated from cloud: $localFileName")
                                        } catch (e: Exception) {
                                            Log.e("CollabSessions", "Failed to update local file", e)
                                        }
                                    }
                                }
                            } else {
                                // Cloud-only file, download it
                                withContext(Dispatchers.IO) {
                                    try {
                                        val sessionsDir = java.io.File(filesDir, "collab_sessions")
                                        val newFile = java.io.File(sessionsDir, localFileName)
                                        newFile.writeText(content)
                                        allFiles.add(newFile)
                                        localFileNames.add(localFileName)
                                        prefs.edit().putLong("collab_sync_${localFileName}", modifiedTime).apply()
                                        Log.d("CollabSessions", "Downloaded from cloud: $localFileName")
                                    } catch (e: Exception) {
                                        Log.e("CollabSessions", "Failed to download cloud file", e)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CollabSessions", "Failed to load from cloud", e)
                    }
                }
                
                if (allFiles.isEmpty()) {
                    homeBinding.collabSessionsLabel.visibility = View.GONE
                    homeBinding.collabSessionsGrid.visibility = View.GONE
                    homeBinding.root.findViewById<View>(R.id.sectionDivider)?.visibility = View.GONE
                    return@launch
                }
                
                // Convert to FileEntry format
                val entries = allFiles.mapNotNull { file ->
                    try {
                        val content = file.readText()
                        val preview = content.take(150).trim()
                        
                        // Parse filename: format is "sessionId_creatorName_timestamp.txt"
                        val parts = file.nameWithoutExtension.split("_")
                        val sessionId = parts.getOrNull(0) ?: "Unknown"
                        val creatorName = parts.getOrNull(1)?.replace("-", " ") ?: "Unknown"
                        val timestamp = parts.getOrNull(2)?.toLongOrNull() ?: file.lastModified()
                        
                        val date = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                            .format(java.util.Date(timestamp))
                        
                        val displayName = "🔗 $sessionId - by $creatorName ($date)"
                        
                        FileCardAdapter.FileEntry(
                            uri = android.net.Uri.fromFile(file),
                            name = displayName,
                            preview = preview
                        )
                    } catch (e: Exception) {
                        Log.e("CollabSessions", "Failed to parse session file", e)
                        null
                    }
                }
                
                if (entries.isEmpty()) {
                    homeBinding.collabSessionsLabel.visibility = View.GONE
                    homeBinding.collabSessionsGrid.visibility = View.GONE
                    homeBinding.root.findViewById<View>(R.id.sectionDivider)?.visibility = View.GONE
                } else {
                    homeBinding.collabSessionsLabel.visibility = View.VISIBLE
                    homeBinding.collabSessionsGrid.visibility = View.VISIBLE
                    homeBinding.root.findViewById<View>(R.id.sectionDivider)?.visibility = View.VISIBLE
                    
                    // Setup adapter if not initialized
                    if (!::collabSessionsAdapter.isInitialized) {
                        collabSessionsAdapter = FileCardAdapter(
                            onOpen = { entry ->
                                // Open collaborative session file
                                try {
                                    val content = java.io.File(entry.uri.path!!).readText()
                                    editorBinding.textArea.setText(content)
                                    currentFileUri = null
                                    textChanged = false
                                    updateToolbarTitle()
                                    showEditor()
                                    Toast.makeText(this@MainActivity, "Opened session file", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(this@MainActivity, "Failed to open: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onLongClick = { entry ->
                                // Delete session file
                                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Delete Session File?")
                                    .setMessage("This will remove the collaborative session file from your device.")
                                    .setPositiveButton("Delete") { _, _ ->
                                        try {
                                            java.io.File(entry.uri.path!!).delete()
                                            loadCollaborativeSessions()
                                            Toast.makeText(this@MainActivity, "Deleted", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(this@MainActivity, "Failed to delete", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        )
                        collabSessionsAdapter.isGlassMode = fileCardAdapter.isGlassMode
                        homeBinding.collabSessionsGrid.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@MainActivity, 1)
                        homeBinding.collabSessionsGrid.adapter = collabSessionsAdapter
                    }
                    
                    collabSessionsAdapter.setFiles(entries)
                }
            } catch (e: Exception) {
                Log.e("CollabSessions", "Failed to load sessions", e)
                homeBinding.collabSessionsLabel.visibility = View.GONE
                homeBinding.collabSessionsGrid.visibility = View.GONE
            }
        }
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
            val existing = chatsDir.listFiles { f ->
                f.name.startsWith(date) && f.name.endsWith("nbhheditorchat")
            }?.size ?: 0
            val idx = String.format("%02d", existing + 1)
            java.io.File(chatsDir, "${date}${idx}nbhheditorchat").also { currentChatFile = it }
        }
        try {
            val gson = com.google.gson.Gson()
            val historyItems = chatAdapter.getMessages().map { msg ->
                when (msg) {
                    is ChatMessage -> ChatHistoryItem("text", msg.role, msg.content)
                    is ChatAdapter.ImageMessage -> ChatHistoryItem("image", prompt = msg.prompt, base64 = msg.base64)
                    else -> null
                }
            }.filterNotNull()
            val jsonContent = gson.toJson(historyItems)
            file.writeText(jsonContent)
            
            // Sync to cloud in parallel if signed in
            if (GoogleSignInHelper.isSignedIn(this)) {
                lifecycleScope.launch {
                    try {
                        GoogleSignInHelper.syncChatToCloud(this@MainActivity, jsonContent, file.name)
                        prefs.edit().putLong("chat_sync_${file.name}", System.currentTimeMillis()).apply()
                    } catch (e: Exception) {
                        Log.e("ChatSync", "Failed to sync chat", e)
                    }
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
        // Create loading dialog
        val loadingDialog = android.app.AlertDialog.Builder(this)
            .setView(android.view.LayoutInflater.from(this).inflate(
                android.R.layout.simple_list_item_1, null
            ).apply {
                val progressBar = ProgressBar(this@MainActivity).apply {
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(
                        resources.getColor(R.color.accent_primary, theme)
                    )
                }
                val textView = TextView(this@MainActivity).apply {
                    text = "Syncing chats from cloud..."
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setPadding(32, 16, 32, 16)
                    setTextColor(resources.getColor(R.color.editor_text, theme))
                }
                val layout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(48, 48, 48, 48)
                    addView(progressBar, LinearLayout.LayoutParams(120, 120).apply {
                        gravity = android.view.Gravity.CENTER
                    })
                    addView(textView)
                }
                (this as? android.view.ViewGroup)?.removeAllViews()
                (this as? android.view.ViewGroup)?.addView(layout)
            })
            .setCancelable(false)
            .create()
        
        loadingDialog.show()
        
        lifecycleScope.launch {
            try {
                val chatsDir = java.io.File(filesDir, "nbh_chats").also { it.mkdirs() }
                val localFiles = chatsDir.listFiles()?.toMutableList() ?: mutableListOf()
                val processedNames = localFiles.map { it.name }.toMutableSet()
                
                // Load cloud chats if signed in
                if (GoogleSignInHelper.isSignedIn(this@MainActivity)) {
                    try {
                        val cloudChats = withContext(Dispatchers.IO) {
                            GoogleSignInHelper.getAllCloudChats(this@MainActivity)
                        }
                        
                        for ((fileName, content) in cloudChats) {
                            val localFile = java.io.File(chatsDir, fileName)
                            val cloudSyncTime = prefs.getLong("chat_sync_${fileName}", 0L)
                            
                            if (!localFile.exists()) {
                                // Cloud-only chat, download it
                                withContext(Dispatchers.IO) {
                                    localFile.writeText(content)
                                }
                                localFiles.add(localFile)
                                processedNames.add(fileName)
                            } else if (localFile.lastModified() < cloudSyncTime) {
                                // Cloud version is newer, update local
                                withContext(Dispatchers.IO) {
                                    localFile.writeText(content)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatHistory", "Failed to load cloud chats", e)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    
                    val files = localFiles.sortedByDescending { it.lastModified() }
                    if (files.isEmpty()) {
                        Toast.makeText(this@MainActivity, "No saved chats", Toast.LENGTH_SHORT).show()
                        return@withContext
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

                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Chat History")
                        .setItems(names) { _, which -> loadChat(files[which]) }
                        .setNeutralButton("Delete All") { _, _ ->
                            files.forEach { it.delete() }
                            Toast.makeText(this@MainActivity, "All chats deleted", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                        .also { dialog ->
                            (dialog as? androidx.appcompat.app.AlertDialog)
                                ?.listView?.setOnItemLongClickListener { _, _, pos, _ ->
                                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                        .setMessage("Delete ${names[pos]}?")
                                        .setPositiveButton("Delete") { _, _ ->
                                            files[pos].delete()
                                            dialog.dismiss()
                                            Toast.makeText(this@MainActivity, "Deleted", Toast.LENGTH_SHORT).show()
                                        }
                                        .setNegativeButton("Cancel", null)
                                        .show()
                                    true
                                }
                        }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Failed to load chats: ${e.message}", Toast.LENGTH_SHORT).show()
                }
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
            Toast.makeText(this, "Microphone permission is required for voice input", Toast.LENGTH_LONG).show()
            return
        }
        
        // Check if speech recognition is available
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition is not available on this device", Toast.LENGTH_LONG).show()
            return
        }
        
        // Warn if running on emulator (audio may be unreliable)
        if (Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator")) {
            val isFirstTime = !prefs.getBoolean("emulator_audio_warned", false)
            if (isFirstTime) {
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
                Toast.makeText(this, "Please wait, initializing...", Toast.LENGTH_SHORT).show()
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
            
            speechRecognizer?.setRecognitionListener(buildRecognitionListener())
            
            try {
                speechRecognizer?.startListening(buildRecognizerIntent())
            } catch (e: Exception) {
                Toast.makeText(this, "Could not start voice input: ${e.message}", Toast.LENGTH_SHORT).show()
                scheduleCleanup()
            }
        }
    }
    
    private fun restartListening() {
        if (!isListening) return
        // Destroy and recreate to avoid Android's internal state getting stuck
        try { speechRecognizer?.stopListening() } catch (_: Exception) {}
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null

        if (!SpeechRecognizer.isRecognitionAvailable(this)) { stopVoiceInput(); return }
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        } catch (_: Exception) { stopVoiceInput(); return }

        speechRecognizer?.setRecognitionListener(buildRecognitionListener())
        try {
            speechRecognizer?.startListening(buildRecognizerIntent())
        } catch (_: Exception) {
            voiceHandler.postDelayed({ if (isListening) restartListening() }, 500)
        }
    }

    private fun buildRecognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
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

    private fun buildRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            runOnUiThread { isListening = true; setMicActive(true); updateVoiceButtonIcon(true); showVoiceStatusNoSpeech(); updateVoiceActivity() }
        }
        override fun onBeginningOfSpeech() { runOnUiThread { showVoiceStatusSpeaking(); updateVoiceActivity() } }
        override fun onRmsChanged(rmsdB: Float) {
            runOnUiThread {
                if (!isListening) return@runOnUiThread
                if (rmsdB > 3.0f) { showVoiceStatusSpeaking(); updateVoiceActivity() } else showVoiceStatusNoSpeech()
            }
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { runOnUiThread { if (isListening) showVoiceStatusNoSpeech() } }
        override fun onResults(results: android.os.Bundle) {
            runOnUiThread {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (!text.isNullOrBlank()) {
                    updateVoiceActivity()
                    val et = speechTarget
                    if (et != null) {
                        // Ensure the target has focus so selectionStart is valid
                        if (!et.hasFocus()) {
                            et.requestFocus()
                        }
                        val pos = et.selectionStart.coerceAtLeast(et.text?.length ?: 0)
                        val toInsert = if (pos > 0 && et.text?.getOrNull(pos - 1)?.isWhitespace() == false) " $text " else "$text "
                        try {
                            et.text?.insert(pos, toInsert)
                            et.setSelection((pos + toInsert.length).coerceAtMost(et.text?.length ?: 0))
                            if (et == editorBinding.textArea) {
                                textChanged = true
                                handler.removeCallbacks(typingDelayRunnable)
                                handler.postDelayed(typingDelayRunnable, 2000)
                            }
                        } catch (e: Exception) {
                            Log.e("VoiceInput", "Failed to insert text", e)
                        }
                    }
                    speechTarget?.hint = ""
                }
                if (isListening) { showVoiceStatusNoSpeech(); restartListening() }
            }
        }
        override fun onPartialResults(partial: android.os.Bundle?) {
            runOnUiThread {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (!text.isNullOrBlank()) { showVoiceStatusSpeaking(); updateVoiceActivity() }
            }
        }
        override fun onError(error: Int) {
            runOnUiThread {
                when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> { Toast.makeText(this@MainActivity, "Audio error", Toast.LENGTH_SHORT).show(); stopVoiceInput(); return@runOnUiThread }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { Toast.makeText(this@MainActivity, "Microphone permission required", Toast.LENGTH_SHORT).show(); stopVoiceInput(); return@runOnUiThread }
                    else -> {}
                }
                if (isListening) {
                    showVoiceStatusNoSpeech()
                    voiceHandler.postDelayed({ if (isListening) restartListening() }, 100)
                }
            }
        }
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
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

    private fun setupTextTypeButton() {
        editorBinding.textTypeButton.setOnClickListener {
            showTextTypeDialog()
        }
    }

    private fun showTextTypeDialog() {
        // Initialize font manager
        FontManager.initialize()
        val allFonts = FontManager.getAllFonts()
        val fontNames = allFonts.map { it.name }.toTypedArray()
        val sizes = arrayOf("10sp", "12sp", "14sp", "16sp", "18sp", "20sp", "22sp", "24sp", "28sp", "32sp", "36sp", "48sp")
        
        val currentSizeIndex = sizes.indexOfFirst { it.replace("sp", "").toFloat() == currentTextSize }.coerceAtLeast(2)
        
        val dialog = android.app.AlertDialog.Builder(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        
        // Font preview
        val previewLabel = TextView(this).apply {
            text = "Preview"
            textSize = 12f
            setTextColor(resources.getColor(R.color.editor_text, theme))
        }
        layout.addView(previewLabel)
        
        val previewText = TextView(this).apply {
            text = "The quick brown fox jumps over the lazy dog"
            textSize = 18f
            setTextColor(resources.getColor(R.color.editor_text, theme))
            setPadding(16, 16, 16, 16)
            background = resources.getDrawable(R.drawable.bg_glass_card, theme)
        }
        layout.addView(previewText)
        
        val fontLabel = TextView(this).apply {
            text = "Font Style (${fontNames.size} fonts)"
            textSize = 14f
            setTextColor(resources.getColor(R.color.editor_text, theme))
            setPadding(0, 20, 0, 0)
        }
        layout.addView(fontLabel)
        
        val fontSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, fontNames).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    previewText.typeface = allFonts[position].typeface
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        layout.addView(fontSpinner)
        
        val sizeLabel = TextView(this).apply {
            text = "Font Size"
            textSize = 14f
            setTextColor(resources.getColor(R.color.editor_text, theme))
            setPadding(0, 20, 0, 0)
        }
        layout.addView(sizeLabel)
        
        val sizeSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, sizes).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(currentSizeIndex)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    previewText.textSize = sizes[position].replace("sp", "").toFloat()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        layout.addView(sizeSpinner)
        
        dialog.setTitle("Text Type")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                val selectedFont = allFonts[fontSpinner.selectedItemPosition].typeface
                val selectedSize = sizes[sizeSpinner.selectedItemPosition].replace("sp", "").toFloat()
                
                val editText = editorBinding.textArea
                val start = editText.selectionStart
                val end = editText.selectionEnd
                
                if (start != end && start >= 0 && end <= (editText.text?.length ?: 0)) {
                    // Apply to selected text
                    val spannable = editText.text as? android.text.Spannable ?: return@setPositiveButton
                    
                    // Apply typeface span with higher priority
                    spannable.setSpan(
                        CustomTypefaceSpan(selectedFont),
                        start, end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or android.text.Spannable.SPAN_PRIORITY
                    )
                    
                    // Apply size span with higher priority
                    val sizePx = selectedSize * resources.displayMetrics.scaledDensity
                    spannable.setSpan(
                        android.text.style.AbsoluteSizeSpan(sizePx.toInt()),
                        start, end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or android.text.Spannable.SPAN_PRIORITY
                    )
                    
                    // Force refresh
                    editText.setText(spannable)
                    editText.setSelection(start, end)
                    
                    Toast.makeText(this, "Applied to selected text", Toast.LENGTH_SHORT).show()
                } else {
                    // Set for future text
                    currentTypeface = selectedFont
                    currentTextSize = selectedSize
                    Toast.makeText(this, "Will apply to new text", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            private var insertStart = -1
            private var insertEnd = -1
            
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!isUndoing && s != null) {
                    undoStack.add(s.toString())
                    if (undoStack.size > 50) undoStack.removeAt(0)
                }
                // Track where new text will be inserted
                if (after > count) {
                    insertStart = start
                    insertEnd = start + after
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
                
                // Apply current font/size to newly typed text
                if (insertStart >= 0 && insertEnd > insertStart && s is android.text.Spannable) {
                    val actualEnd = insertEnd.coerceAtMost(s.length)
                    if (insertStart < actualEnd) {
                        // Apply typeface with higher priority
                        s.setSpan(
                            CustomTypefaceSpan(currentTypeface),
                            insertStart, actualEnd,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or android.text.Spannable.SPAN_PRIORITY
                        )
                        // Apply size with higher priority
                        val sizePx = currentTextSize * resources.displayMetrics.scaledDensity
                        s.setSpan(
                            android.text.style.AbsoluteSizeSpan(sizePx.toInt()),
                            insertStart, actualEnd,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or android.text.Spannable.SPAN_PRIORITY
                        )
                    }
                }
                insertStart = -1
                insertEnd = -1
                
                // Apply rich text formatting only after user stops typing
                val richTextEnabled = prefs.getBoolean("rich_text_mode", true)
                if (richTextEnabled) {
                    val richEdit = editorBinding.textArea as? RichEditText
                    richEdit?.isRichTextMode = true
                    handler.removeCallbacks(formattingRunnable)
                    handler.postDelayed(formattingRunnable, 1500)
                }
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
        
        // Get line height from editor
        val lineHeight = et.lineHeight
        
        // Always update all line numbers to ensure they match
        if (lineCount != current) {
            if (lineCount > current) {
                // Add new line numbers
                for (i in (current + 1)..lineCount) {
                    val tv = TextView(this).apply {
                        text = i.toString()
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            lineHeight
                        )
                        gravity = Gravity.END or Gravity.TOP
                        setPadding(0, et.paddingTop, 8, 0)
                        typeface = Typeface.MONOSPACE
                        val lineNumColor = if (isGlassMode)
                            0x88AABBFF.toInt()
                        else
                            resources.getColor(R.color.editor_line_number_text, theme)
                        setTextColor(lineNumColor)
                        if (isGlassMode) paintFlags = paintFlags or android.graphics.Paint.FAKE_BOLD_TEXT_FLAG
                        textSize = 12f
                        setLineSpacing(et.lineSpacingExtra, et.lineSpacingMultiplier)
                    }
                    editorBinding.lineNumbersVBox.addView(tv)
                }
            } else {
                // Remove extra line numbers
                editorBinding.lineNumbersVBox.removeViews(lineCount, current - lineCount)
            }
        }
        
        // Update visibility for image-only lines
        val layout = et.layout
        if (layout != null) {
            for (i in 0 until lineCount.coerceAtMost(editorBinding.lineNumbersVBox.childCount)) {
                val tv = editorBinding.lineNumbersVBox.getChildAt(i) as? TextView ?: continue
                val lineIdx = i
                if (lineIdx < layout.lineCount) {
                    val ls = layout.getLineStart(lineIdx)
                    val le = layout.getLineEnd(lineIdx)
                    val spans = et.text?.getSpans(ls, le, android.text.style.ImageSpan::class.java) ?: emptyArray()
                    val hasImageOnly = spans.isNotEmpty() && (le - ls) <= 2
                    tv.text = if (hasImageOnly) "" else (i + 1).toString()
                }
            }
        }
    }

    private fun performAutoSave() {
        currentFileUri?.let { uri ->
            val text = serializeSpansToText()
            
            // Save locally
            try {
                contentResolver.openOutputStream(uri, "wt")?.use {
                    OutputStreamWriter(it).use { w -> w.write(text) }
                }
                prefs.edit().putString("last_file_uri", uri.toString()).apply()
                addToRecents(uri)
            } catch (e: Exception) {
                Log.e("AutoSave", "Failed to save locally", e)
            }
            
            // Sync to cloud in parallel
            if (GoogleSignInHelper.isSignedIn(this)) {
                val fileName = getFileName(uri)
                lifecycleScope.launch {
                    try {
                        val success = GoogleSignInHelper.syncFileToCloud(this@MainActivity, text, fileName)
                        if (!success) {
                            val authException = GoogleSignInHelper.getLastAuthException()
                            if (authException != null) {
                                withContext(Dispatchers.Main) {
                                    startActivityForResult(authException.intent, RC_DRIVE_PERMISSION)
                                }
                            }
                        } else {
                            hasSyncedOnce = true
                            // Store cloud sync timestamp
                            prefs.edit().putLong("cloud_sync_${fileName}", System.currentTimeMillis()).apply()
                        }
                        withContext(Dispatchers.Main) {
                            lastSyncSuccess = success
                            updateToolbarTitle()
                        }
                    } catch (e: Exception) {
                        Log.e("AutoSave", "Cloud sync failed", e)
                    }
                }
            }
        } ?: run {
            val text = editorBinding.textArea.text.toString()
            if (text.isNotBlank()) {
                prefs.edit()
                    .putString("recovery_text", text)
                    .remove("last_file_uri")
                    .apply()
            }
        }
        textChanged = false
        updateToolbarTitle()
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
        
        // Determine cloud icon based on sync status
        val cloudIcon = when {
            !GoogleSignInHelper.isSignedIn(this) -> " ☁✗" // Not signed in
            !hasSyncedOnce -> " ☁⚠" // Signed in but not synced yet
            else -> " ☁" // Successfully synced
        }
        
        val tv = binding.appBarMain.toolbarTitle ?: return
        val displayText = "$name$dot$cloudIcon"
        
        tv.text = displayText
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.textSize = 19f
        tv.letterSpacing = 0.02f
    }

    private fun openFileFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val fileName = getFileName(uri)
                val isRtf = fileName.endsWith(".rtf", ignoreCase = true)
                val isCloudUri = uri.scheme == "cloud"
                
                // Check file size first
                val fileSize = if (!isCloudUri) {
                    withContext(Dispatchers.IO) {
                        try {
                            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                        } catch (e: Exception) { 0L }
                    }
                } else 0L
                
                val fileSizeMB = fileSize / (1024 * 1024)
                val fileSizeGB = fileSize / (1024 * 1024 * 1024)
                Log.d("FileOpen", "Opening file: $fileName, size: ${fileSizeMB}MB")
                
                // Warn for extremely large files (>1GB)
                if (fileSizeGB >= WARN_SIZE_GB) {
                    val shouldContinue = withContext(Dispatchers.Main) {
                        showLargeFileWarningDialog(fileName, fileSizeGB)
                    }
                    if (!shouldContinue) {
                        Toast.makeText(this@MainActivity, "File loading cancelled", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
                
                // Show progress dialog for files larger than 5MB
                val progressDialog = if (fileSizeMB > INSTANT_LOAD_SIZE_MB) {
                    createFileLoadingDialog(fileName, fileSizeMB)
                } else null
                
                progressDialog?.show()
                
                var content: String? = null
                var useCloudVersion = false
                
                if (isCloudUri) {
                    // Cloud-only file, download from Drive
                    content = withContext(Dispatchers.IO) {
                        GoogleSignInHelper.downloadCloudFile(this@MainActivity, fileName)
                    }
                    if (content == null) {
                        progressDialog?.dismiss()
                        Toast.makeText(this@MainActivity, "Failed to download from cloud", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                } else {
                    // Check if cloud has newer version
                    if (GoogleSignInHelper.isSignedIn(this@MainActivity)) {
                        val cloudModifiedTime = withContext(Dispatchers.IO) {
                            GoogleSignInHelper.getFileModifiedTime(this@MainActivity, fileName)
                        }
                        val localSyncTime = prefs.getLong("cloud_sync_${fileName}", 0L)
                        
                        if (cloudModifiedTime != null && cloudModifiedTime > localSyncTime) {
                            // Cloud version is newer, ask user
                            val cloudContent = withContext(Dispatchers.IO) {
                                GoogleSignInHelper.downloadCloudFile(this@MainActivity, fileName)
                            }
                            if (cloudContent != null) {
                                val choice = withContext(Dispatchers.Main) {
                                    progressDialog?.dismiss()
                                    showCloudVersionDialog()
                                }
                                if (choice) {
                                    content = cloudContent
                                    useCloudVersion = true
                                    // Update local file with cloud version
                                    withContext(Dispatchers.IO) {
                                        try {
                                            contentResolver.openOutputStream(uri, "wt")?.use {
                                                OutputStreamWriter(it).use { w -> w.write(cloudContent) }
                                            }
                                            prefs.edit().putLong("cloud_sync_${fileName}", cloudModifiedTime).apply()
                                        } catch (e: Exception) {
                                            Log.e("OpenFile", "Failed to update local file", e)
                                        }
                                    }
                                }
                                progressDialog?.show()
                            }
                        }
                    }
                    
                    // Load local version if not using cloud
                    if (content == null) {
                        content = withContext(Dispatchers.IO) {
                            loadLargeFile(uri, isRtf) { progress ->
                                lifecycleScope.launch(Dispatchers.Main) {
                                    updateProgressDialog(progressDialog, progress)
                                }
                            }
                        }
                    }
                }
                
                progressDialog?.dismiss()
                
                if (content == null) {
                    Toast.makeText(this@MainActivity, "Failed to read file", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                editorBinding.textArea.setText(content)
                currentFileUri = if (isCloudUri) null else uri
                textChanged = false
                prefs.edit()
                    .remove("recovery_text")
                    .apply()
                if (!isCloudUri) {
                    prefs.edit().putString("last_file_uri", uri.toString()).apply()
                }
                updateLineNumbers()
                updateToolbarTitle()
                deserializeImagesInText()
                
                // Auto-enable rich text mode for supported formats
                val richTextEnabled = prefs.getBoolean("rich_text_mode", true)
                val richEdit = editorBinding.textArea as? RichEditText
                val fileExtension = fileName.substringAfterLast('.', "").lowercase()
                val richTextFormats = listOf("md", "markdown", "html", "htm", "txt", "rtf")
                
                if (richTextEnabled && fileExtension in richTextFormats) {
                    richEdit?.isRichTextMode = true
                    richEdit?.applyRichTextFormatting()
                } else {
                    richEdit?.isRichTextMode = false
                }
                
                if (!isCloudUri) {
                    addToRecents(uri)
                }
                val msg = when {
                    useCloudVersion -> "Opened (updated from cloud)"
                    isCloudUri -> "Opened from cloud"
                    isRtf -> "Opened RTF (converted to plain text)"
                    fileSizeMB > 5 -> "Opened large file (${fileSizeMB}MB)"
                    else -> "Opened"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to open file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private suspend fun showLargeFileWarningDialog(fileName: String, fileSizeGB: Long): Boolean = suspendCancellableCoroutine { continuation ->
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Large File Warning")
            .setMessage("The file '$fileName' is ${fileSizeGB}GB in size.\n\nLoading very large files may:\n• Take several minutes\n• Use significant memory\n• Slow down the app\n\nDo you want to continue?")
            .setPositiveButton("Load Anyway") { _, _ ->
                continuation.resume(true, null)
            }
            .setNegativeButton("Cancel") { _, _ ->
                continuation.resume(false, null)
            }
            .setCancelable(false)
            .create()
        dialog.show()
    }
    
    private suspend fun showCloudVersionDialog(): Boolean = suspendCancellableCoroutine { continuation ->
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Newer Version Available")
            .setMessage("A newer version of this file exists in the cloud. Do you want to use the cloud version?")
            .setPositiveButton("Use Cloud Version") { _, _ ->
                continuation.resume(true, null)
            }
            .setNegativeButton("Use Local Version") { _, _ ->
                continuation.resume(false, null)
            }
            .setCancelable(false)
            .create()
        dialog.show()
    }
    
    private fun createFileLoadingDialog(fileName: String, fileSizeMB: Long): androidx.appcompat.app.AlertDialog {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
            gravity = Gravity.CENTER
        }
        
        // Title
        val titleView = TextView(this).apply {
            text = "Loading Large File"
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        
        // File info
        val fileInfoView = TextView(this).apply {
            text = "$fileName (${fileSizeMB}MB)"
            textSize = 14f
            gravity = Gravity.CENTER
            alpha = 0.8f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }
        
        // Progress bar
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            progressTintList = android.content.res.ColorStateList.valueOf(
                resources.getColor(R.color.accent_primary, theme)
            )
        }
        
        // Progress text
        val progressText = TextView(this).apply {
            text = "0%"
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            tag = "progress_text"
        }
        
        dialogView.addView(titleView)
        dialogView.addView(fileInfoView)
        dialogView.addView(progressBar)
        dialogView.addView(progressText)
        dialogView.tag = "progress_bar"
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        // Apply theming
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                
                val bgColor = if (isGlassMode) {
                    0xDD0D1117.toInt()
                } else {
                    val isDark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    if (isDark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt()
                }
                
                val textColor = if (isGlassMode || (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)) {
                    0xFFFFFFFF.toInt()
                } else {
                    0xFF212121.toInt()
                }
                
                dialogView.setBackgroundColor(bgColor)
                dialogView.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = 24f
                    if (isGlassMode) {
                        setStroke(2, 0x33FFFFFF)
                    }
                }
                
                titleView.setTextColor(textColor)
                fileInfoView.setTextColor(textColor)
                progressText.setTextColor(textColor)
            }
            
            // Fade in animation
            dialogView.alpha = 0f
            dialogView.scaleX = 0.9f
            dialogView.scaleY = 0.9f
            dialogView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator())
                .start()
        }
        
        return dialog
    }
    
    private fun updateProgressDialog(dialog: androidx.appcompat.app.AlertDialog?, progress: Int) {
        dialog?.let { d ->
            val dialogView = d.findViewById<View>(android.R.id.custom) ?: d.window?.decorView?.findViewWithTag<LinearLayout>("progress_bar")
            val progressBar = dialogView?.findViewWithTag<ProgressBar>("progress_bar")
            val progressText = dialogView?.findViewWithTag<TextView>("progress_text")
            
            progressBar?.progress = progress
            progressText?.text = "$progress%"
            
            // Add estimated time remaining for large files
            if (progress > 10 && progress < 100) {
                val estimatedSeconds = ((100 - progress) * 2) // Rough estimate
                val timeText = if (estimatedSeconds > 60) {
                    "${estimatedSeconds / 60}m ${estimatedSeconds % 60}s remaining"
                } else {
                    "${estimatedSeconds}s remaining"
                }
                progressText?.text = "$progress% - $timeText"
            } else if (progress == 100) {
                progressText?.text = "100% - Complete!"
            }
        }
    }
    
    private fun loadLargeFile(uri: Uri, isRtf: Boolean, onProgress: (Int) -> Unit): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                if (isRtf) {
                    // For RTF files, read all bytes first then convert
                    val fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                    val totalSize = fileDescriptor?.statSize ?: inputStream.available().toLong()
                    fileDescriptor?.close()
                    
                    val buffer = ByteArray(16384) // 16KB buffer for better performance
                    val outputStream = java.io.ByteArrayOutputStream()
                    var bytesRead = 0L
                    var read: Int
                    var lastProgressUpdate = 0
                    
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                        bytesRead += read
                        if (totalSize > 0) {
                            val progress = ((bytesRead.toFloat() / totalSize) * 100).toInt()
                            // Update progress every 5% to avoid too many UI updates
                            if (progress >= lastProgressUpdate + 5 || progress == 100) {
                                onProgress(progress)
                                lastProgressUpdate = progress
                            }
                        }
                    }
                    
                    onProgress(100)
                    convertRtfToPlainText(outputStream.toByteArray())
                } else {
                    // For text files, read with progress tracking
                    val fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                    val totalSize = fileDescriptor?.statSize ?: inputStream.available().toLong()
                    fileDescriptor?.close()
                    
                    val reader = BufferedReader(InputStreamReader(inputStream), 32768) // 32KB buffer
                    val stringBuilder = StringBuilder()
                    val buffer = CharArray(16384) // 16KB char buffer
                    var bytesRead = 0L
                    var read: Int
                    var lastProgressUpdate = 0
                    
                    while (reader.read(buffer).also { read = it } != -1) {
                        stringBuilder.append(buffer, 0, read)
                        bytesRead += read * 2 // Approximate bytes (char = 2 bytes)
                        if (totalSize > 0) {
                            val progress = ((bytesRead.toFloat() / totalSize) * 100).toInt().coerceAtMost(100)
                            // Update progress every 5% to avoid too many UI updates
                            if (progress >= lastProgressUpdate + 5 || progress == 100) {
                                onProgress(progress)
                                lastProgressUpdate = progress
                            }
                        }
                    }
                    
                    onProgress(100)
                    stringBuilder.toString()
                }
            }
        } catch (e: Exception) {
            Log.e("FileLoad", "Error loading large file", e)
            null
        }
    }

    private fun convertRtfToPlainText(rtfBytes: ByteArray): String {
        return try {
            val rtfText = String(rtfBytes, Charsets.UTF_8)
            val result = StringBuilder()
            var i = 0
            var inGroup = 0
            var skipGroup = false
            val groupStack = mutableListOf<Boolean>()
            var inPict = false
            val pictHex = StringBuilder()
            var pictType = "png" // default

            while (i < rtfText.length) {
                val c = rtfText[i]
                when {
                    c == '{' -> {
                        inGroup++
                        groupStack.add(skipGroup)
                        val ahead = rtfText.substring(i, (i + 20).coerceAtMost(rtfText.length))
                        if (ahead.contains("\\fonttbl") || ahead.contains("\\colortbl") ||
                            ahead.contains("\\stylesheet") || ahead.contains("\\*")) {
                            skipGroup = true
                        }
                        i++
                    }
                    c == '}' -> {
                        if (inPict && pictHex.isNotEmpty()) {
                            // Decode image and save to cache
                            try {
                                val hexStr = pictHex.toString().replace("\n", "").replace(" ", "").replace("\r", "")
                                val bytes = ByteArray(hexStr.length / 2) { idx ->
                                    hexStr.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
                                }
                                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null) {
                                    val name = "rtf_img_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}.$pictType"
                                    val file = java.io.File(cacheDir, name)
                                    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                                    result.append("[img:$name]")
                                }
                            } catch (e: Exception) {
                                Log.e("RTF", "Failed to decode image", e)
                                result.append("[Image]")
                            }
                            pictHex.clear()
                            inPict = false
                        }
                        inGroup--
                        if (groupStack.isNotEmpty()) {
                            skipGroup = groupStack.removeAt(groupStack.size - 1)
                        }
                        i++
                    }
                    c == '\\' && i + 1 < rtfText.length -> {
                        val (controlWord, param, nextPos) = parseRtfControl(rtfText, i)
                        i = nextPos
                        if (!skipGroup) {
                            when (controlWord) {
                                "par" -> result.append("\n")
                                "line" -> result.append("\n")
                                "tab" -> result.append("\t")
                                "bullet" -> result.append("• ")
                                "emdash" -> result.append("—")
                                "endash" -> result.append("–")
                                "lquote" -> result.append("'")
                                "rquote" -> result.append("'")
                                "ldblquote" -> result.append("\"")
                                "rdblquote" -> result.append("\"")
                                "~" -> result.append(" ")
                                "_" -> result.append("-")
                                "'" -> {
                                    if (param.length == 2) {
                                        try { result.append(param.toInt(16).toChar()) } catch (e: Exception) { }
                                    }
                                }
                                "u" -> {
                                    try {
                                        val unicode = param.toIntOrNull()
                                        if (unicode != null) {
                                            val actualCode = if (unicode < 0) 65536 + unicode else unicode
                                            result.append(actualCode.toChar())
                                        }
                                    } catch (e: Exception) { }
                                }
                                "pict" -> { inPict = true; pictHex.clear() }
                                "pngblip" -> { pictType = "png" }
                                "jpegblip" -> { pictType = "jpg" }
                                "emfblip", "wmetafile" -> { pictType = "png" }
                            }
                        }
                    }
                    else -> {
                        if (inPict && !skipGroup) {
                            // Collect hex data for image
                            if (c.isLetterOrDigit()) pictHex.append(c)
                        } else if (!skipGroup && inGroup >= 0 && c != '\r' && c != '\n') {
                            result.append(c)
                        }
                        i++
                    }
                }
            }
            result.toString().replace(Regex("\n{3,}"), "\n\n").replace(Regex(" {2,}"), " ").trim()
        } catch (e: Exception) {
            Log.e("RTF", "Error parsing RTF", e)
            "Error parsing RTF: ${e.message}"
        }
    }
    
    private fun parseRtfControl(rtf: String, startPos: Int): Triple<String, String, Int> {
        var pos = startPos + 1
        if (pos >= rtf.length) return Triple("", "", pos)
        val firstChar = rtf[pos]
        if (firstChar in "\\{}~_-") return Triple(firstChar.toString(), "", pos + 1)
        if (firstChar == '\'' && pos + 2 < rtf.length) {
            return Triple("'", rtf.substring(pos + 1, pos + 3), pos + 3)
        }
        val wordStart = pos
        while (pos < rtf.length && rtf[pos].isLetter()) pos++
        val controlWord = rtf.substring(wordStart, pos)
        val paramStart = pos
        if (pos < rtf.length && (rtf[pos] == '-' || rtf[pos].isDigit())) {
            pos++
            while (pos < rtf.length && rtf[pos].isDigit()) pos++
        }
        val param = rtf.substring(paramStart, pos)
        if (pos < rtf.length && rtf[pos] == ' ') pos++
        return Triple(controlWord, param, pos)
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
            
            // Sync to cloud in parallel if signed in
            if (GoogleSignInHelper.isSignedIn(this)) {
                val fileName = getFileName(uri)
                lifecycleScope.launch {
                    try {
                        val success = GoogleSignInHelper.syncFileToCloud(this@MainActivity, text, fileName)
                        if (!success) {
                            val authException = GoogleSignInHelper.getLastAuthException()
                            if (authException != null) {
                                withContext(Dispatchers.Main) {
                                    startActivityForResult(authException.intent, RC_DRIVE_PERMISSION)
                                }
                            }
                        } else {
                            hasSyncedOnce = true
                            prefs.edit().putLong("cloud_sync_${fileName}", System.currentTimeMillis()).apply()
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
            R.id.nav_collaborative_session -> showCollaborativeSessionDialog()
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
        setIntent(intent)
        
        // Handle deep link for collaborative session
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                // Handle collaborative session deep links
                val sessionId = when {
                    // HTTPS: https://nbheditor.pages.dev/collaborative/NE5SDT2
                    uri.scheme == "https" && uri.host == "nbheditor.pages.dev" && uri.pathSegments.size >= 2 -> {
                        if (uri.pathSegments[0] == "collaborative") uri.pathSegments[1] else null
                    }
                    // Custom scheme: nbheditor://collaborative/NE5SDT2
                    uri.scheme == "nbheditor" && uri.host == "collaborative" && uri.pathSegments.isNotEmpty() -> {
                        uri.pathSegments[0]
                    }
                    else -> null
                }
                
                if (sessionId != null && sessionId.matches(Regex("NE[A-Z0-9]{5}"))) {
                    // Check if user is signed in
                    if (!GoogleSignInHelper.isSignedIn(this)) {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Sign In Required")
                            .setMessage("You need to sign in with Google to join collaborative sessions.")
                            .setPositiveButton("Sign In") { _, _ -> 
                                startGoogleSignIn()
                                prefs.edit().putString("pending_session_join", sessionId).apply()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        return
                    }
                    joinCollaborativeSession(sessionId)
                    return
                }
            }
        }
        
        // Handle notification tap to rejoin session
        val sessionIdFromNotif = intent.getStringExtra(CollabSessionService.EXTRA_SESSION_ID)
        if (!sessionIdFromNotif.isNullOrBlank() && !CollaborativeSessionManager.isInSession()) {
            joinCollaborativeSession(sessionIdFromNotif)
            return
        }
        handleOpenIntent(intent)
    }
    
    override fun onResume() {
        super.onResume()
        checkUnexpectedSessionExit()
    }
    
    private fun checkUnexpectedSessionExit() {
        val savedSessionId = CollabSessionService.getActiveSessionId(this)
        if (!savedSessionId.isNullOrBlank() && !CollaborativeSessionManager.isInSession()) {
            // User was in a session but app was closed unexpectedly
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🔗 Session Interrupted")
                .setMessage("You left the collaborative session \"$savedSessionId\" unexpectedly.\n\nWould you like to rejoin?")
                .setPositiveButton("Rejoin") { _, _ ->
                    joinCollaborativeSession(savedSessionId)
                }
                .setNegativeButton("Leave Session") { _, _ ->
                    CollabSessionService.stop(this)
                }
                .setCancelable(false)
                .show()
        }
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
            var compressedUri: android.net.Uri? = null
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

                // Check video size first
                val videoSize = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                    } catch (e: Exception) { 0L }
                }
                
                Log.d("VideoAnalysis", "Original video size: ${videoSize / 1024 / 1024}MB")
                
                // Compress video if larger than 10MB
                // Skip the fake compress step — compressVideoSimple is just a file copy.
                // Frame extraction with low res/quality handles size reduction instead.
                val videoToAnalyze = uri
                
                // Adjust frame count based on video size
                val maxFrames = when {
                    videoSize > 20 * 1024 * 1024 -> 1
                    videoSize > 5 * 1024 * 1024 -> 2
                    else -> 3
                }

                // Extract frames from video
                val frames = extractVideoFrames(videoToAnalyze, maxFrames)
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
                    showChatError("Video analysis failed. Check that your Gemini API key is valid and has quota remaining.")
                }
            } catch (e: Exception) {
                Log.e("VideoAnalysis", "Analysis error: ${e.message}", e)
                aiChatBinding.typingRow.visibility = View.GONE
                aiChatBinding.videoAnalysisChip.isChecked = false
                val userMsg = when (e.message) {
                    "API_KEY_INVALID" -> "Video analysis failed: Gemini API key is invalid or revoked. Please update it in settings."
                    "API_QUOTA_EXCEEDED" -> "Video analysis failed: Gemini API quota exceeded. Check your billing at ai.google.dev."
                    else -> "Video analysis failed: ${e.message?.take(80)}"
                }
                showChatError(userMsg)
            } finally {
                // Clean up compressed file
                compressedUri?.let { uri ->
                    try {
                        val path = uri.path
                        if (path != null) {
                            java.io.File(path).delete()
                        }
                    } catch (e: Exception) {
                        Log.e("VideoAnalysis", "Failed to delete compressed file", e)
                    }
                }
            }
        }
    }
    
    private suspend fun compressVideo(uri: android.net.Uri): android.net.Uri? = withContext(Dispatchers.IO) {
        var progressDialog: androidx.appcompat.app.AlertDialog? = null
        var progressBar: ProgressBar? = null
        var progressText: TextView? = null
        
        try {
            // Create progress dialog
            withContext(Dispatchers.Main) {
                val dialogView = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(60, 40, 60, 40)
                    gravity = Gravity.CENTER
                }
                
                val titleView = TextView(this@MainActivity).apply {
                    text = "Compressing Video..."
                    textSize = 18f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 24 }
                }
                
                progressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = 0
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 16 }
                    progressTintList = android.content.res.ColorStateList.valueOf(
                        resources.getColor(R.color.accent_primary, theme)
                    )
                }
                
                progressText = TextView(this@MainActivity).apply {
                    text = "0%"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                
                dialogView.addView(titleView)
                dialogView.addView(progressBar)
                dialogView.addView(progressText)
                
                progressDialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setView(dialogView)
                    .setCancelable(false)
                    .create()
                
                progressDialog?.show()
            }
            
            // Get input file path
            val inputPath = withContext(Dispatchers.IO) {
                val tempInput = java.io.File(cacheDir, "temp_input_${System.currentTimeMillis()}.mp4")
                contentResolver.openInputStream(uri)?.use { input ->
                    tempInput.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempInput.absolutePath
            }
            
            // Output file
            val outputFile = java.io.File(cacheDir, "compressed_${System.currentTimeMillis()}.mp4")
            
            // Use MediaCodec for compression
            val success = compressVideoWithMediaCodec(inputPath, outputFile.absolutePath) { progress ->
                lifecycleScope.launch(Dispatchers.Main) {
                    progressBar?.progress = progress
                    progressText?.text = "$progress%"
                }
            }
            
            withContext(Dispatchers.Main) {
                progressDialog?.dismiss()
            }
            
            // Clean up temp input
            java.io.File(inputPath).delete()
            
            if (success && outputFile.exists()) {
                val compressedSize = outputFile.length()
                Log.d("VideoAnalysis", "Compressed video size: ${compressedSize / 1024 / 1024}MB")
                android.net.Uri.fromFile(outputFile)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("VideoAnalysis", "Compression error: ${e.message}", e)
            withContext(Dispatchers.Main) {
                progressDialog?.dismiss()
            }
            null
        }
    }
    
    private fun compressVideoWithMediaCodec(
        inputPath: String,
        outputPath: String,
        onProgress: (Int) -> Unit
    ): Boolean {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(inputPath)
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            
            // Use Android's built-in MediaTranscoder if available (API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For newer Android versions, use simpler approach
                compressVideoSimple(inputPath, outputPath, duration, onProgress)
            } else {
                // For older versions, use basic copy with progress
                compressVideoSimple(inputPath, outputPath, duration, onProgress)
            }
        } catch (e: Exception) {
            Log.e("VideoCompress", "Error: ${e.message}", e)
            false
        }
    }
    
    private fun compressVideoSimple(
        inputPath: String,
        outputPath: String,
        duration: Long,
        onProgress: (Int) -> Unit
    ): Boolean {
        return try {
            val inputFile = java.io.File(inputPath)
            val outputFile = java.io.File(outputPath)
            
            val inputSize = inputFile.length()
            var bytesRead = 0L
            
            inputFile.inputStream().use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        val progress = ((bytesRead.toFloat() / inputSize) * 100).toInt()
                        onProgress(progress)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e("VideoCompress", "Simple compress error: ${e.message}", e)
            false
        }
    }

    private suspend fun extractVideoFrames(uri: android.net.Uri, maxFrames: Int = 3): List<String> = withContext(Dispatchers.IO) {
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
                // Extract frames at strategic points
                val extractionPoints = mutableListOf<Long>()
                
                if (maxFrames == 1) {
                    // Single frame from middle
                    extractionPoints.add(duration / 2)
                } else {
                    // Start frame (skip first 500ms to avoid black frames)
                    extractionPoints.add(500L)
                    
                    // Middle frames
                    val interval = duration / (maxFrames + 1)
                    for (i in 1 until maxFrames - 1) {
                        extractionPoints.add(interval * i)
                    }
                    
                    // End frame (500ms before end)
                    if (maxFrames > 1) {
                        extractionPoints.add((duration - 500).coerceAtLeast(1000L))
                    }
                }
                
                extractionPoints.take(maxFrames).forEach { timeMs ->
                    try {
                        val timeUs = timeMs * 1000
                        val bitmap = retriever.getFrameAtTime(
                            timeUs, 
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                        
                        if (bitmap != null) {
                            // Cap at 320px and 50% quality to keep base64 payload under API limits
                            val maxDim = 320
                            val scale = maxDim.toFloat() / Math.max(bitmap.width, bitmap.height)
                            val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
                            val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
                            
                            val resized = android.graphics.Bitmap.createScaledBitmap(
                                bitmap, newWidth, newHeight, true
                            )
                            
                            val stream = java.io.ByteArrayOutputStream()
                            resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, stream)
                            val base64 = android.util.Base64.encodeToString(
                                stream.toByteArray(), 
                                android.util.Base64.NO_WRAP
                            )
                            
                            frames.add(base64)
                            Log.d("VideoAnalysis", "Extracted frame at ${timeMs}ms (${newWidth}x${newHeight}, ${stream.size() / 1024}KB)")
                            
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
                    Log.e("VideoAnalysisGemini", "Error ${response.code}: $responseBody")
                    if (response.code == 403 || response.code == 401) {
                        throw Exception("API_KEY_INVALID")
                    }
                    if (response.code == 429) {
                        throw Exception("API_QUOTA_EXCEEDED")
                    }
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
                        
                        // Check for pending session join
                        val pendingSessionId = prefs.getString("pending_session_join", null)
                        if (pendingSessionId != null) {
                            prefs.edit().remove("pending_session_join").apply()
                            Handler(Looper.getMainLooper()).postDelayed({
                                joinCollaborativeSession(pendingSessionId)
                            }, 1000)
                        }
                        
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
            📝 NBH Editor v4.5.0
            
            A blazing-fast, modern text editor with rich text support.
            
            ✨ Features:
            • Rich Text Editing (Markdown/HTML)
            • AI-Assisted Editing (Beeta AI)
            • Real-Time Collaboration
            • Auto-sync to Google Cloud
            • Smart Font Control
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
    
    // ── Join Sound Helper Functions ──────────────────────────────────────────
    
    private fun playJoinSound() {
        try {
            Log.d("JoinSound", "playJoinSound() called")
            
            // Stop any existing player
            stopJoinSound()
            
            // Get audio manager and check volume
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            Log.d("JoinSound", "Current volume: $currentVolume/$maxVolume")
            
            // If volume is too low, temporarily increase it
            val originalVolume = currentVolume
            if (currentVolume < maxVolume / 3) {
                val targetVolume = (maxVolume * 0.5f).toInt()
                audioManager.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    targetVolume,
                    0 // No UI flags
                )
                Log.d("JoinSound", "Temporarily increased volume to $targetVolume")
            }
            
            // Request audio focus
            val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .build()
                audioManager.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
            
            Log.d("JoinSound", "Audio focus result: $focusResult")
            
            // Create MediaPlayer with explicit audio stream type
            joinSoundPlayer = android.media.MediaPlayer().apply {
                setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)
                val afd = resources.openRawResourceFd(R.raw.join_sound)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                isLooping = false
                setVolume(1.0f, 1.0f) // Max volume
                setOnCompletionListener {
                    Log.d("JoinSound", "Playback completed")
                    stopJoinSound()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e("JoinSound", "MediaPlayer error: what=$what, extra=$extra")
                    stopJoinSound()
                    true
                }
                start()
                Log.d("JoinSound", "Join sound started playing (duration: ${duration}ms, isPlaying: $isPlaying)")
            }
        } catch (e: Exception) {
            Log.e("JoinSound", "Failed to play join sound", e)
        }
    }
    
    private fun stopJoinSound() {
        try {
            joinSoundPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
            joinSoundPlayer = null
            
            // Abandon audio focus
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            
            Log.d("JoinSound", "Join sound stopped")
        } catch (e: Exception) {
            Log.e("JoinSound", "Failed to stop join sound", e)
        }
    }
    
    // ── Collaborative Session ─────────────────────────────────────────────────
    
    private fun createLoadingDialog(title: String, message: String): androidx.appcompat.app.AlertDialog {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
            gravity = Gravity.CENTER
        }
        
        // Animated progress indicator
        val progressBar = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 24
            }
        }
        
        // Title
        val titleView = TextView(this).apply {
            text = title
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }
        
        // Message with animated steps
        val messageView = TextView(this).apply {
            text = message.split("\n").firstOrNull() ?: message
            textSize = 14f
            gravity = Gravity.CENTER
            alpha = 0.8f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        dialogView.addView(progressBar)
        dialogView.addView(titleView)
        dialogView.addView(messageView)
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        // Animate through steps
        val steps = message.split("\n")
        var currentStep = 0
        val stepHandler = Handler(Looper.getMainLooper())
        val stepRunnable = object : Runnable {
            override fun run() {
                if (currentStep < steps.size && dialog.isShowing) {
                    messageView.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction {
                            messageView.text = "✓ ${steps[currentStep]}"
                            messageView.animate()
                                .alpha(0.8f)
                                .setDuration(200)
                                .start()
                            currentStep++
                            stepHandler.postDelayed(this, 600)
                        }
                        .start()
                } else if (dialog.isShowing) {
                    // Cycle back to first step
                    currentStep = 0
                    stepHandler.postDelayed(this, 600)
                }
            }
        }
        
        // Apply glass mode styling if enabled
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                
                val bgColor = if (isGlassMode) {
                    0xDD0D1117.toInt()
                } else {
                    val isDark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    if (isDark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt()
                }
                
                val textColor = if (isGlassMode || (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)) {
                    0xFFFFFFFF.toInt()
                } else {
                    0xFF212121.toInt()
                }
                
                dialogView.setBackgroundColor(bgColor)
                dialogView.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = 24f
                    if (isGlassMode) {
                        setStroke(2, 0x33FFFFFF)
                    }
                }
                
                titleView.setTextColor(textColor)
                messageView.setTextColor(textColor)
                
                // Tint progress bar
                progressBar.indeterminateTintList = android.content.res.ColorStateList.valueOf(
                    resources.getColor(R.color.accent_primary, theme)
                )
            }
            
            // Fade in animation
            dialogView.alpha = 0f
            dialogView.scaleX = 0.9f
            dialogView.scaleY = 0.9f
            dialogView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator())
                .start()
            
            // Start step animation
            stepHandler.postDelayed(stepRunnable, 600)
        }
        
        dialog.setOnDismissListener {
            stepHandler.removeCallbacks(stepRunnable)
        }
        
        return dialog
    }
    
    fun onNewChatMessage() {
        // Update chat button badge if chat is not open
        val infoBar = editorBinding.root.findViewWithTag<View>("session_info_bar")
        val chatBtn = infoBar?.findViewWithTag<com.google.android.material.button.MaterialButton>("session_chat_btn")
        chatBtn?.text = "💬 Chat •"
    }
    
    private fun showSuccessToast(message: String) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_LONG)
        toast.show()
        
        // Add haptic feedback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(50)
        }
    }
    
    private fun showErrorDialog(title: String, message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("❌ $title")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showSessionInviteDialog(sessionId: String) {
        val creatorName = GoogleSignInHelper.getUserName(this) ?: "Someone"
        val webLink = "https://nbheditor.pages.dev/collaborative/$sessionId"
        val inviteText = """🎉 $creatorName is inviting you to join a collaborative session on NbhEditor!

Click to join the collaborative session:

$webLink

💡 You can also join manually:

🔑 Session Code: $sessionId

Open NbhEditor → Menu → Collaborative Session → Join Session"""
        
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }
        
        // Title
        val titleView = TextView(this).apply {
            text = "✅ Session Created!"
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }
        
        // Session code display
        val codeCard = androidx.cardview.widget.CardView(this).apply {
            radius = 16f
            cardElevation = 4f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
        
        val codeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            gravity = Gravity.CENTER
        }
        
        val codeLabel = TextView(this).apply {
            text = "Session Code:"
            textSize = 14f
            alpha = 0.7f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }
        
        val codeText = TextView(this).apply {
            text = sessionId
            textSize = 28f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(resources.getColor(R.color.accent_primary, theme))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        codeLayout.addView(codeLabel)
        codeLayout.addView(codeText)
        codeCard.addView(codeLayout)
        
        // Link display card
        val linkCard = androidx.cardview.widget.CardView(this).apply {
            radius = 16f
            cardElevation = 4f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24 }
        }
        
        val linkLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            gravity = Gravity.CENTER
        }
        
        val linkLabel = TextView(this).apply {
            text = "Or click the link to join:"
            textSize = 14f
            alpha = 0.7f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }
        
        val linkText = TextView(this).apply {
            text = webLink
            textSize = 12f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(resources.getColor(R.color.accent_secondary, theme))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        linkLayout.addView(linkLabel)
        linkLayout.addView(linkText)
        linkCard.addView(linkLayout)
        
        // Message
        val messageView = TextView(this).apply {
            text = "Share this code or link with others to collaborate in real-time!"
            textSize = 14f
            gravity = Gravity.CENTER
            alpha = 0.8f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 32 }
        }
        
        // Buttons layout
        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Copy button
        val btnCopy = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "📋 Copy"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginEnd = 8 }
            setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Session Invite", inviteText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "✓ Invite copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Share button
        val btnShare = com.google.android.material.button.MaterialButton(this).apply {
            text = "📤 Share"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = 8 }
            setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Join my NbhEditor session!")
                    putExtra(Intent.EXTRA_TEXT, inviteText)
                }
                startActivity(Intent.createChooser(shareIntent, "Share session code via"))
            }
        }
        
        buttonsLayout.addView(btnCopy)
        buttonsLayout.addView(btnShare)
        
        // Close button
        val btnClose = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Close"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        
        dialogView.addView(titleView)
        dialogView.addView(codeCard)
        dialogView.addView(linkCard)
        dialogView.addView(messageView)
        dialogView.addView(buttonsLayout)
        dialogView.addView(btnClose)
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                
                val bgColor = if (isGlassMode) {
                    0xDD0D1117.toInt()
                } else {
                    val isDark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    if (isDark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt()
                }
                
                val textColor = if (isGlassMode || (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)) {
                    0xFFFFFFFF.toInt()
                } else {
                    0xFF212121.toInt()
                }
                
                dialogView.setBackgroundColor(bgColor)
                dialogView.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = 24f
                    if (isGlassMode) {
                        setStroke(2, 0x33FFFFFF)
                    }
                }
                
                titleView.setTextColor(textColor)
                codeLabel.setTextColor(textColor)
                linkLabel.setTextColor(textColor)
                messageView.setTextColor(textColor)
                
                // Card background
                val cardBg = if (isGlassMode) {
                    0xBB1A1F26.toInt()
                } else {
                    val isDark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    if (isDark) 0xFF2A2A2A.toInt() else 0xFFF5F5F5.toInt()
                }
                codeCard.setCardBackgroundColor(cardBg)
                linkCard.setCardBackgroundColor(cardBg)
            }
            
            // Fade in animation
            dialogView.alpha = 0f
            dialogView.scaleX = 0.9f
            dialogView.scaleY = 0.9f
            dialogView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator())
                .start()
        }
        
        dialog.show()
    }
    
    private fun saveCollaborativeSessionFile(sessionId: String, content: String, creatorName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sessionsDir = java.io.File(filesDir, "collab_sessions").also { it.mkdirs() }
                val timestamp = System.currentTimeMillis()
                val sanitizedName = creatorName.replace(" ", "-").replace("[^a-zA-Z0-9-]".toRegex(), "")
                val fileName = "${sessionId}_${sanitizedName}_${timestamp}.txt"
                val file = java.io.File(sessionsDir, fileName)
                file.writeText(content)
                Log.d("CollabSession", "✓ Saved session file: $fileName (${content.length} chars)")
                
                // Sync to Google Drive if signed in
                if (GoogleSignInHelper.isSignedIn(this@MainActivity)) {
                    try {
                        val driveFileName = "collab_session_$fileName"
                        val success = GoogleSignInHelper.syncFileToCloud(this@MainActivity, content, driveFileName)
                        if (success) {
                            prefs.edit().putLong("collab_sync_${fileName}", timestamp).apply()
                            Log.d("CollabSession", "✓ Synced to Drive: $driveFileName")
                        }
                    } catch (e: Exception) {
                        Log.e("CollabSession", "Failed to sync to Drive", e)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "✓ Session saved locally", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("CollabSession", "Failed to save session file", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to save session: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showCollaborativeSessionDialog() {
        // Check if user is signed in
        if (!GoogleSignInHelper.isSignedIn(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sign In Required")
                .setMessage("You need to sign in with Google to use collaborative editing.")
                .setPositiveButton("Sign In") { _, _ -> startGoogleSignIn() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_Nbheditor)
        val dialogView = android.view.LayoutInflater.from(themedContext).inflate(R.layout.dialog_collaborative_session, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCreateSession)
            .setOnClickListener {
                dialog.dismiss()
                createCollaborativeSession()
            }
        
        val etSessionCode = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSessionCode)
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnJoinSession)
            .setOnClickListener {
                val code = etSessionCode.text.toString().trim().uppercase()
                if (code.length == 7 && code.startsWith("NE")) {
                    dialog.dismiss()
                    joinCollaborativeSession(code)
                } else {
                    Toast.makeText(this, "Invalid session code format (e.g., neAB12X)", Toast.LENGTH_SHORT).show()
                }
            }
        
        dialog.show()
    }
    
    private fun createCollaborativeSession() {
        val userName = GoogleSignInHelper.getUserName(this) ?: "Unknown"
        val email = GoogleSignInHelper.getUserEmail(this) ?: ""
        val userId = email // Use email as unique user ID
        val currentContent = editorBinding.textArea.text.toString()
        
        // Show loading dialog with animation
        val loadingDialog = createLoadingDialog(
            title = "Creating Session...",
            message = "Setting up collaborative workspace\nGenerating session code\nInitializing real-time sync"
        )
        loadingDialog.show()
        
        // Play join sound
        playJoinSound()
        
        lifecycleScope.launch {
            try {
                val photoUrl = GoogleSignInHelper.getUserPhotoUrl(this@MainActivity) ?: ""
                val result = CollaborativeSessionManager.createSession(userId, userName, email, currentContent, photoUrl)
                
                // Add slight delay to show the loading animation
                delay(800)
                
                loadingDialog.dismiss()
                stopJoinSound()
                
                result.onSuccess { sessionId ->
                    showSuccessToast("✓ Session created: $sessionId")
                    showActiveSessionUI(sessionId, isCreator = true)
                    // Show invite dialog after session is created
                    showSessionInviteDialog(sessionId)
                }.onFailure { e ->
                    showErrorDialog("Failed to create session", e.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                loadingDialog.dismiss()
                stopJoinSound()
                showErrorDialog("Error", e.message ?: "Unknown error")
            }
        }
    }
    
    private fun joinCollaborativeSession(sessionId: String) {
        val userName = GoogleSignInHelper.getUserName(this) ?: "Unknown"
        val email = GoogleSignInHelper.getUserEmail(this) ?: ""
        val userId = email // Use email as unique user ID
        
        // Show loading dialog
        val loadingDialog = createLoadingDialog(
            title = "Joining Session...",
            message = "Connecting to $sessionId\nSyncing content\nLoading chat history"
        )
        loadingDialog.show()
        
        // Play join sound
        playJoinSound()
        
        lifecycleScope.launch {
            try {
                val photoUrl = GoogleSignInHelper.getUserPhotoUrl(this@MainActivity) ?: ""
                val result = CollaborativeSessionManager.joinSession(sessionId, userId, userName, email, photoUrl)
                
                // Add slight delay to show the loading animation
                delay(800)
                
                loadingDialog.dismiss()
                stopJoinSound()
                
                result.onSuccess { session ->
                    showSuccessToast("✓ Joined session: $sessionId")
                    editorBinding.textArea.setText(session.content)
                    showActiveSessionUI(sessionId, isCreator = false)
                    // startCollaborativeSync is called inside showActiveSessionUI
                }.onFailure { e ->
                    showErrorDialog("Failed to join session", e.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                loadingDialog.dismiss()
                stopJoinSound()
                showErrorDialog("Error", e.message ?: "Unknown error")
            }
        }
    }
    
    // Active session UI
    private var activeSessionDialog: androidx.appcompat.app.AlertDialog? = null
    private var contentSyncJob: Job? = null
    
    private fun showActiveSessionUI(sessionId: String, isCreator: Boolean) {
        showEditor()
        updateToolbarWithSession(sessionId)
        showSessionInfoBar(sessionId, isCreator)
        startCollaborativeSync(sessionId)
        // Start persistent notification
        CollabSessionService.start(this, sessionId)
        Toast.makeText(this, "✓ Connected to session: $sessionId", Toast.LENGTH_SHORT).show()
    }
    
    private fun showSessionInfoBar(sessionId: String, isCreator: Boolean) {
        // Remove any existing session info bar first
        val existingBar = editorBinding.root.findViewWithTag<View>("session_info_bar_wrapper")
        if (existingBar != null) {
            (existingBar.parent as? android.view.ViewGroup)?.removeView(existingBar)
        }
        
        // Create a compact info bar that shows below the editor toolbar
        val infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 12, 24, 12)
            setBackgroundColor(0xFF1976D2.toInt())
            elevation = 4f
            id = View.generateViewId()
            tag = "session_info_bar"
        }
        
        // Session icon and code
        val sessionInfo = TextView(this).apply {
            text = "🔗 $sessionId"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoBar.addView(sessionInfo)
        
        // View Users button
        val btnUsers = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "👥 Users"
            setTextColor(0xFFFFFFFF.toInt())
            strokeColor = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            strokeWidth = 2
            backgroundTintList = android.content.res.ColorStateList.valueOf(0x00000000)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8
            }
            minWidth = 0
            minimumWidth = 0
            textSize = 11f
            setPadding(20, 4, 20, 4)
            setOnClickListener {
                showSessionUsersDialog(sessionId, isCreator)
            }
        }
        infoBar.addView(btnUsers)
        
        // Chat button
        val btnChat = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "💬 Chat"
            tag = "session_chat_btn"
            setTextColor(0xFFFFFFFF.toInt())
            strokeColor = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            strokeWidth = 2
            backgroundTintList = android.content.res.ColorStateList.valueOf(0x00000000)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8
            }
            minWidth = 0
            minimumWidth = 0
            textSize = 11f
            setPadding(20, 4, 20, 4)
            setOnClickListener {
                text = "💬 Chat" // clear badge on open
                showCollabChatDialog()
            }
        }
        infoBar.addView(btnChat)
        
        // Copy code button
        val btnCopy = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "📋"
            setTextColor(0xFFFFFFFF.toInt())
            strokeColor = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            strokeWidth = 2
            backgroundTintList = android.content.res.ColorStateList.valueOf(0x00000000)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8
            }
            minWidth = 0
            minimumWidth = 0
            textSize = 13f
            setPadding(16, 4, 16, 4)
            setOnClickListener {
                val creatorName = GoogleSignInHelper.getUserName(this@MainActivity) ?: "Someone"
                val appLink = "nbheditor://collaborative/$sessionId"
                val webLink = "https://nbheditor.pages.dev/collaborative/$sessionId"
                val inviteText = """🎉 $creatorName is inviting you to join a collaborative session on NbhEditor!

📱 Open in app (tap to join):
$appLink

🌐 Or open in browser:
$webLink

🔑 Session Code: $sessionId

💡 You can also join manually:
Open NbhEditor → Menu → Collaborative Session → Join Session"""
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Session Invite", inviteText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "✓ Invite copied!", Toast.LENGTH_SHORT).show()
            }
        }
        infoBar.addView(btnCopy)
        
        // Leave button
        val btnLeave = com.google.android.material.button.MaterialButton(this).apply {
            text = "Leave"
            setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFD32F2F.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            minWidth = 0
            minimumWidth = 0
            textSize = 11f
            setPadding(20, 4, 20, 4)
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Leave Session?")
                    .setMessage("Are you sure you want to leave this collaborative session?")
                    .setPositiveButton("Leave") { _, _ ->
                        lifecycleScope.launch {
                            // Show loading
                            val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                .setMessage("Leaving session...")
                                .setCancelable(false)
                                .create()
                            progressDialog.show()
                            
                            // Save session content before leaving
                            val currentContent = editorBinding.textArea.text.toString()
                            val currentSession = CollaborativeSessionManager.getCurrentSession()
                            if (currentSession != null && currentContent.isNotBlank()) {
                                val creatorName = currentSession.users.values.firstOrNull { it.userId == currentSession.creatorId }?.userName ?: "Unknown"
                                saveCollaborativeSessionFile(currentSession.sessionId, currentContent, creatorName)
                            }
                            
                            CollaborativeSessionManager.leaveSession()
                            contentSyncJob?.cancel()
                            
                            // Clear all session cache and close dialogs
                            CollaborativeSessionManager.clearSessionCache(this@MainActivity)
                            activeSessionDialog?.dismiss()
                            activeSessionDialog = null
                            
                            progressDialog.dismiss()
                            
                            removeSessionInfoBar()
                            CollabSessionService.stop(this@MainActivity)
                            
                            // Clear editor and go back to home with animation
                            editorBinding.textArea.animate()
                                .alpha(0f)
                                .setDuration(200)
                                .withEndAction {
                                    editorBinding.textArea.setText("")
                                    currentFileUri = null
                                    textChanged = false
                                    updateToolbarTitle()
                                    showHome()
                                    editorBinding.textArea.alpha = 1f
                                }
                                .start()
                            
                            Toast.makeText(this@MainActivity, "✓ Left session", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        infoBar.addView(btnLeave)
        
        val constraintLayout = editorBinding.root as androidx.constraintlayout.widget.ConstraintLayout

        val overlayWrapper = android.widget.FrameLayout(this).apply {
            id = View.generateViewId()
            tag = "session_info_bar_wrapper"
        }
        constraintLayout.addView(overlayWrapper,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT,
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).also { lp ->
                lp.topToBottom = editorBinding.editorToolbarDivider.id
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            }
        )
        overlayWrapper.addView(infoBar)

        // Push editor_container below the info bar
        val cs = androidx.constraintlayout.widget.ConstraintSet()
        cs.clone(constraintLayout)
        cs.connect(editorBinding.editorContainer.id,
            androidx.constraintlayout.widget.ConstraintSet.TOP,
            overlayWrapper.id,
            androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
        cs.applyTo(constraintLayout)
        
        // Animate info bar sliding in from top
        infoBar.alpha = 0f
        infoBar.translationY = -200f
        infoBar.post {
            infoBar.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(400)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun removeSessionInfoBar() {
        val wrapper = editorBinding.root.findViewWithTag<View>("session_info_bar_wrapper") ?: return
        // Restore editor_container top to editorToolbarDivider before removing wrapper
        val constraintLayout = editorBinding.root as androidx.constraintlayout.widget.ConstraintLayout
        val cs = androidx.constraintlayout.widget.ConstraintSet()
        cs.clone(constraintLayout)
        cs.connect(editorBinding.editorContainer.id,
            androidx.constraintlayout.widget.ConstraintSet.TOP,
            editorBinding.editorToolbarDivider.id,
            androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
        cs.applyTo(constraintLayout)
        wrapper.animate()
            .alpha(0f)
            .translationY(-wrapper.height.toFloat())
            .setDuration(300)
            .withEndAction { (wrapper.parent as? android.view.ViewGroup)?.removeView(wrapper) }
            .start()
    }
    
    private fun updateToolbarWithSession(sessionId: String) {
        val name = if (currentFileUri != null) getFileName(currentFileUri!!) else "NBH Editor"
        val dot = if (textChanged) " ●" else ""
        
        // Determine cloud icon based on sync status
        val cloudIcon = when {
            !GoogleSignInHelper.isSignedIn(this) -> " ☁✗"
            !hasSyncedOnce -> " ☁⚠"
            else -> " ☁"
        }
        
        // Add session indicator
        val sessionIndicator = " 🔗 $sessionId"
        
        val tv = binding.appBarMain.toolbarTitle ?: return
        val displayText = "$name$dot$cloudIcon$sessionIndicator"
        
        tv.text = displayText
        tv.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tv.textSize = 18f
        tv.letterSpacing = 0.02f
        
        // Make toolbar clickable to show quick session menu
        tv.setOnClickListener {
            if (CollaborativeSessionManager.isInSession()) {
                showSessionControlsMenu(sessionId)
            }
        }
    }
    
    private fun showSessionControlsMenu(sessionId: String) {
        val items = arrayOf("💬 Team Chat", "👥 View Users", "📋 Copy Session Code", "🚪 Leave Session")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Session: $sessionId")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showCollabChatDialog()
                    1 -> {
                        val isCreator = CollaborativeSessionManager.getCurrentUserId()?.let { userId ->
                            lifecycleScope.launch {
                                val session = CollaborativeSessionManager.observeSession(sessionId)
                                    .first { it != null }
                                session?.creatorId == userId
                            }
                        }
                        showSessionUsersDialog(sessionId, false)
                    }
                    2 -> {
                        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Session Code", sessionId)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this, "✓ Session code copied: $sessionId", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Leave Session?")
                            .setMessage("Are you sure you want to leave this collaborative session?")
                            .setPositiveButton("Leave") { _, _ ->
                                lifecycleScope.launch {
                                    // Show loading
                                    val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                        .setMessage("Leaving session...")
                                        .setCancelable(false)
                                        .create()
                                    progressDialog.show()
                                    
                                    // Save session content before leaving
                                    val currentContent = editorBinding.textArea.text.toString()
                                    val currentSession = CollaborativeSessionManager.getCurrentSession()
                                    if (currentSession != null && currentContent.isNotBlank()) {
                                        val creatorName = currentSession.users.values.firstOrNull { it.userId == currentSession.creatorId }?.userName ?: "Unknown"
                                        saveCollaborativeSessionFile(currentSession.sessionId, currentContent, creatorName)
                                    }
                                    
                                    CollaborativeSessionManager.leaveSession()
                                    contentSyncJob?.cancel()
                                    
                                    // Clear all session cache and close dialogs
                                    CollaborativeSessionManager.clearSessionCache(this@MainActivity)
                                    activeSessionDialog?.dismiss()
                                    activeSessionDialog = null
                                    
                                    progressDialog.dismiss()
                                    
                                    removeSessionInfoBar()
                                    
                                    // Clear editor and go back to home with animation
                                    editorBinding.textArea.animate()
                                        .alpha(0f)
                                        .setDuration(200)
                                        .withEndAction {
                                            editorBinding.textArea.setText("")
                                            currentFileUri = null
                                            textChanged = false
                                            updateToolbarTitle()
                                            showHome()
                                            editorBinding.textArea.alpha = 1f
                                        }
                                        .start()
                                    
                                    Toast.makeText(this@MainActivity, "✓ Left session", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
    
    private fun startCollaborativeSync(sessionId: String) {
        contentSyncJob?.cancel()

        var suppressWatcher = false
        val syncHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var syncRunnable: Runnable? = null
        var cursorRunnable: Runnable? = null
        var lastRemoteContent = ""

        // Listen to remote content changes
        contentSyncJob = lifecycleScope.launch {
            CollaborativeSessionManager.observeContent(sessionId).collect { content ->
                if (!suppressWatcher && content != lastRemoteContent) {
                    lastRemoteContent = content
                    suppressWatcher = true
                    
                    // Smart merge: preserve cursor position
                    val currentCursor = editorBinding.textArea.selectionStart
                    val currentText = editorBinding.textArea.text.toString()
                    
                    // Only update if content actually changed
                    if (currentText != content) {
                        // Calculate cursor offset based on text diff
                        val newCursor = calculateNewCursorPosition(currentText, content, currentCursor)
                        
                        editorBinding.textArea.setText(content)
                        editorBinding.textArea.setSelection(newCursor.coerceIn(0, content.length))
                    }
                    
                    suppressWatcher = false
                }
            }
        }

        // Observe kick
        val currentUserId = CollaborativeSessionManager.getCurrentUserId()
        if (currentUserId != null) {
            lifecycleScope.launch {
                CollaborativeSessionManager.observeKicked(sessionId, currentUserId).collect { kicked ->
                    if (kicked) {
                        contentSyncJob?.cancel()
                        CollaborativeSessionManager.leaveSession(clearLocal = false)
                        CollaborativeSessionManager.clearSessionCache(this@MainActivity)
                        activeSessionDialog?.dismiss()
                        activeSessionDialog = null
                        removeSessionInfoBar()
                        CollabSessionService.stop(this@MainActivity)
                        editorBinding.textArea.setText("")
                        this@MainActivity.currentFileUri = null
                        textChanged = false
                        updateToolbarTitle()
                        showHome()
                        
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Removed from Session")
                            .setMessage("You have been removed from the session by the host.")
                            .setPositiveButton("OK", null)
                            .setCancelable(false)
                            .show()
                    }
                }
            }
            
            lifecycleScope.launch {
                CollaborativeSessionManager.observeSessionExists(sessionId).collect { exists ->
                    if (!exists) {
                        contentSyncJob?.cancel()
                        CollaborativeSessionManager.clearSessionCache(this@MainActivity)
                        activeSessionDialog?.dismiss()
                        activeSessionDialog = null
                        removeSessionInfoBar()
                        CollabSessionService.stop(this@MainActivity)
                        editorBinding.textArea.setText("")
                        this@MainActivity.currentFileUri = null
                        textChanged = false
                        updateToolbarTitle()
                        showHome()
                        
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Session Ended")
                            .setMessage("The session has been ended by the host.")
                            .setPositiveButton("OK", null)
                            .setCancelable(false)
                            .show()
                    }
                }
            }
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppressWatcher) return
                val snapshot = s.toString()

                // Debounce content push: 300ms for faster sync
                syncRunnable?.let { syncHandler.removeCallbacks(it) }
                syncRunnable = Runnable {
                    lifecycleScope.launch {
                        CollaborativeSessionManager.updateContent(snapshot)
                    }
                }
                syncHandler.postDelayed(syncRunnable!!, 300)

                // Update cursor immediately
                cursorRunnable?.let { syncHandler.removeCallbacks(it) }
                val pos = editorBinding.textArea.selectionStart
                lifecycleScope.launch {
                    CollaborativeSessionManager.updateCursorPosition(pos, true)
                }
                cursorRunnable = Runnable {
                    lifecycleScope.launch {
                        CollaborativeSessionManager.updateCursorPosition(
                            editorBinding.textArea.selectionStart, false
                        )
                    }
                }
                syncHandler.postDelayed(cursorRunnable!!, 1000)
            }
            override fun afterTextChanged(s: Editable?) {}
        }

        editorBinding.textArea.addTextChangedListener(textWatcher)
        editorBinding.textArea.setOnClickListener {
            lifecycleScope.launch {
                CollaborativeSessionManager.updateCursorPosition(
                    editorBinding.textArea.selectionStart, false
                )
            }
        }
        editorBinding.textArea.setTag(R.id.nav_collaborative_session, textWatcher)
    }
    
    private fun calculateNewCursorPosition(oldText: String, newText: String, oldCursor: Int): Int {
        // Simple algorithm: try to maintain relative position
        if (oldText.isEmpty()) return 0
        if (newText.isEmpty()) return 0
        
        // If cursor is at end, keep it at end
        if (oldCursor >= oldText.length) return newText.length
        
        // Try to find the same context around cursor
        val contextBefore = oldText.substring(0, oldCursor.coerceAtMost(oldText.length))
        val matchIndex = newText.indexOf(contextBefore)
        
        return if (matchIndex >= 0) {
            matchIndex + contextBefore.length
        } else {
            // Fallback: maintain relative position
            ((oldCursor.toFloat() / oldText.length) * newText.length).toInt()
        }
    }
    
    private fun showCollabChatDialog() {
        CollaborativeSessionManager.getCurrentSessionId() ?: return
        CollaborativeSessionManager.getCurrentUserId() ?: return

        val fragment = CollabChatFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.slide_in_left, android.R.anim.slide_out_right,
                android.R.anim.slide_in_left, android.R.anim.slide_out_right
            )
            .replace(binding.appBarMain.contentMain.fragmentContainer.id, fragment)
            .addToBackStack("collab_chat")
            .commit()
        binding.appBarMain.contentMain.fragmentContainer.visibility = View.VISIBLE
    }

    @Suppress("UNUSED_PARAMETER")
    private fun showCollabChatDialogLegacy() {
        val sessionCode = CollaborativeSessionManager.getCurrentSessionId() ?: return
        val currentUserId = CollaborativeSessionManager.getCurrentUserId() ?: return

        val dialogView = layoutInflater.inflate(R.layout.fragment_collab_chat, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.setOnShowListener {
            val window = dialog.window
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.95).toInt()
            val height = (displayMetrics.heightPixels * 0.85).toInt()
            window?.setLayout(width, height)
        }
        
        // Get UI elements
        val rvChatMessages = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvChatMessages)
        val etChatMessage = dialogView.findViewById<EditText>(R.id.etChatMessage)
        val btnSendMessage = dialogView.findViewById<ImageButton>(R.id.btnSendMessage)
        val btnCreateTaskFromChat = dialogView.findViewById<Button>(R.id.btnCreateTaskFromChat)
        val btnCloseChat = dialogView.findViewById<ImageButton>(R.id.btnCloseChat)
        val chatRoot = dialogView.findViewById<RelativeLayout>(R.id.chatRoot)
        val quickActionsBar = dialogView.findViewById<LinearLayout>(R.id.quickActionsBar)
        val inputBar = dialogView.findViewById<LinearLayout>(R.id.inputBar)
        val chatHeader = dialogView.findViewById<LinearLayout>(R.id.chatHeader)
        val tvTargetDisplay = dialogView.findViewById<TextView>(R.id.tvTargetDisplay)
        val targetSelectorBar = dialogView.findViewById<LinearLayout>(R.id.targetSelectorBar)
        
        // Apply glass mode styling if enabled (inherit from main editor)
        if (isGlassMode) {
            // Glass mode - transparent with blur effect
            chatRoot.setBackgroundColor(0xBB0A0E14.toInt())
            rvChatMessages.setBackgroundColor(0x00000000)
            chatHeader.setBackgroundColor(0xCC1976D2.toInt())
            quickActionsBar.setBackgroundColor(0xCC0D1117.toInt())
            inputBar.setBackgroundColor(0xCC0D1117.toInt())
            targetSelectorBar.setBackgroundColor(0xBB1A1F26.toInt())
            etChatMessage.apply {
                setBackgroundColor(0xBB1A1F26.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0x88FFFFFF.toInt())
            }
            btnSendMessage.setColorFilter(0xFF64B5F6.toInt())
            btnCloseChat.setColorFilter(0xFFFFFFFF.toInt())
        } else {
            // Normal mode - follow system theme
            val isDark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
            
            if (isDark) {
                chatRoot.setBackgroundColor(resources.getColor(R.color.editor_bg, theme))
                rvChatMessages.setBackgroundColor(resources.getColor(R.color.editor_bg, theme))
                chatHeader.setBackgroundColor(0xFF1976D2.toInt())
                quickActionsBar.setBackgroundColor(resources.getColor(R.color.editor_surface, theme))
                inputBar.setBackgroundColor(resources.getColor(R.color.editor_surface, theme))
                targetSelectorBar.setBackgroundColor(resources.getColor(R.color.editor_surface, theme))
                etChatMessage.apply {
                    setBackgroundColor(resources.getColor(R.color.editor_surface, theme))
                    setTextColor(resources.getColor(R.color.editor_text, theme))
                    setHintTextColor(resources.getColor(R.color.editor_hint, theme))
                }
            } else {
                // Light mode
                chatRoot.setBackgroundColor(0xFFF5F5F5.toInt())
                rvChatMessages.setBackgroundColor(0xFFFFFFFF.toInt())
                chatHeader.setBackgroundColor(0xFF1976D2.toInt())
                quickActionsBar.setBackgroundColor(0xFFFFFFFF.toInt())
                inputBar.setBackgroundColor(0xFFFFFFFF.toInt())
                targetSelectorBar.setBackgroundColor(0xFFF5F5F5.toInt())
                etChatMessage.apply {
                    setBackgroundColor(0xFFFFFFFF.toInt())
                    setTextColor(0xFF212121.toInt())
                    setHintTextColor(0xFF757575.toInt())
                }
            }
        }
        
        // Fade-in animation for dialog
        dialogView.alpha = 0f
        dialogView.animate()
            .alpha(1f)
            .setDuration(250)
            .start()
        
        // Close button handler with animation
        btnCloseChat.setOnClickListener {
            dialogView.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { dialog.dismiss() }
                .start()
        }
        
        // Message visibility state
        var messageVisibility = "everyone" // default
        var selectedUserIds = mutableSetOf<String>() // for multiple user selection
        
        // Target selector click handler
        tvTargetDisplay.setOnClickListener {
            // Animate click
            tvTargetDisplay.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction {
                    tvTargetDisplay.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .start()
                }
                .start()
            
            lifecycleScope.launch {
                // Get all users in session
                val users = CollaborativeSessionManager.observeUsers(sessionCode).first()
                val usersList = users.values.filter { it.userId != currentUserId }.toList()
                
                val options = mutableListOf<String>()
                options.add("👥 Everyone")
                options.add("🤖 Beeta AI only")
                options.add("👥 Everyone + 🤖 AI")
                options.add("👤 Selected Users")
                
                // Add individual users
                usersList.forEach { user ->
                    options.add("👤 ${user.userName}")
                }
                
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Send message to:")
                    .setItems(options.toTypedArray()) { _, which ->
                        when (which) {
                            0 -> {
                                messageVisibility = "everyone"
                                selectedUserIds.clear()
                                tvTargetDisplay.text = "Everyone"
                            }
                            1 -> {
                                messageVisibility = "ai_only"
                                selectedUserIds.clear()
                                tvTargetDisplay.text = "Beeta AI only"
                            }
                            2 -> {
                                messageVisibility = "everyone_and_ai"
                                selectedUserIds.clear()
                                tvTargetDisplay.text = "Everyone + AI"
                            }
                            3 -> {
                                // Selected users only - show multi-select dialog
                                showUserSelectionDialog(usersList) { selectedUsers ->
                                    messageVisibility = "selected_users"
                                    selectedUserIds = selectedUsers.toMutableSet()
                                    val count = selectedUserIds.size
                                    tvTargetDisplay.text = "$count user(s)"
                                }
                            }
                            else -> {
                                // Individual user selected
                                val userIndex = which - 4
                                val selectedUser = usersList[userIndex]
                                messageVisibility = "selected_users"
                                selectedUserIds.clear()
                                selectedUserIds.add(selectedUser.userId)
                                tvTargetDisplay.text = selectedUser.userName
                            }
                        }
                    }
                    .show()
            }
        }
        
        val adapter = CollabChatAdapter(
            messages = emptyList(),
            currentUserId = currentUserId,
            onMarkImportant = { message ->
                lifecycleScope.launch {
                    val result = CollaborativeSessionManager.markMessageImportant(
                        message.messageId,
                        !message.isImportant
                    )
                    if (result.isSuccess) {
                        Toast.makeText(this@MainActivity, 
                            if (message.isImportant) "Unmarked" else "⭐ Marked important", 
                            Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onCreateTask = { message ->
                lifecycleScope.launch {
                    val result = CollaborativeSessionManager.createTaskFromMessage(message.messageId)
                    if (result.isSuccess) {
                        Toast.makeText(this@MainActivity, "✓ Task created", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSetReminder = { message ->
                val options = arrayOf("In 1 hour", "In 3 hours", "Tomorrow")
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Set Reminder")
                    .setItems(options) { _, which ->
                        val reminderTime = when (which) {
                            0 -> System.currentTimeMillis() + 3600000
                            1 -> System.currentTimeMillis() + 10800000
                            else -> System.currentTimeMillis() + 86400000
                        }
                        lifecycleScope.launch {
                            val result = CollaborativeSessionManager.setMessageReminder(
                                message.messageId,
                                reminderTime
                            )
                            if (result.isSuccess) {
                                Toast.makeText(this@MainActivity, "⏰ Reminder set", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
            }
        )
        
        rvChatMessages.layoutManager = LinearLayoutManager(this)
        rvChatMessages.adapter = adapter
        
        lifecycleScope.launch {
            CollaborativeSessionManager.observeChatMessages(sessionCode).collect { messages ->
                val oldCount = adapter.itemCount
                adapter.updateMessages(messages)
                
                if (messages.isNotEmpty()) {
                    // Smooth scroll to bottom
                    rvChatMessages.smoothScrollToPosition(messages.size - 1)
                    
                    // Animate new message if count increased
                    if (messages.size > oldCount) {
                        val lastPosition = messages.size - 1
                        rvChatMessages.post {
                            val viewHolder = rvChatMessages.findViewHolderForAdapterPosition(lastPosition)
                            viewHolder?.itemView?.apply {
                                alpha = 0f
                                translationX = 50f
                                animate()
                                    .alpha(1f)
                                    .translationX(0f)
                                    .setDuration(300)
                                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                                    .start()
                            }
                        }
                    }
                }
            }
        }
        
        // @mention detection in input
        etChatMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s.toString()
                
                // Detect @mentions
                when {
                    text.startsWith("@ai ", ignoreCase = true) -> {
                        messageVisibility = "ai_only"
                        tvTargetDisplay.text = "Beeta AI only"
                        etChatMessage.setText(text.substring(4))
                        etChatMessage.setSelection(etChatMessage.text.length)
                    }
                    text.startsWith("@all ", ignoreCase = true) -> {
                        messageVisibility = "everyone"
                        tvTargetDisplay.text = "Everyone"
                        etChatMessage.setText(text.substring(5))
                        etChatMessage.setSelection(etChatMessage.text.length)
                    }
                    text.startsWith("@") && text.contains(" ") -> {
                        // Check for username mention
                        val mention = text.substring(1, text.indexOf(" "))
                        lifecycleScope.launch {
                            val users = CollaborativeSessionManager.observeUsers(sessionCode).first()
                            val matchedUser = users.values.find { 
                                it.userName.equals(mention, ignoreCase = true) 
                            }
                            if (matchedUser != null) {
                                messageVisibility = "selected_users"
                                selectedUserIds.clear()
                                selectedUserIds.add(matchedUser.userId)
                                tvTargetDisplay.text = matchedUser.userName
                                etChatMessage.setText(text.substring(text.indexOf(" ") + 1))
                                etChatMessage.setSelection(etChatMessage.text.length)
                            }
                        }
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        
        btnSendMessage.setOnClickListener {
            val message = etChatMessage.text.toString().trim()
            if (message.isNotEmpty()) {
                // Animate send button
                btnSendMessage.animate()
                    .scaleX(0.8f)
                    .scaleY(0.8f)
                    .setDuration(100)
                    .withEndAction {
                        btnSendMessage.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .start()
                    }
                    .start()
                
                lifecycleScope.launch {
                    // Check if message should go to AI
                    val shouldSendToAI = messageVisibility == "ai_only" || messageVisibility == "everyone_and_ai" || messageVisibility == "everyone"
                    
                    // Send regular message first (if not AI-only)
                    if (messageVisibility != "ai_only") {
                        val targetType = when (messageVisibility) {
                            "everyone" -> "everyone"
                            "everyone_and_ai" -> "everyone_and_ai"
                            "selected_users" -> "selected_users"
                            else -> "everyone"
                        }
                        
                        val result = CollaborativeSessionManager.sendChatMessage(
                            message = message,
                            targetType = targetType,
                            targetUserIds = selectedUserIds.toList()
                        )
                        if (!result.isSuccess) {
                            Toast.makeText(this@MainActivity, "Failed to send", Toast.LENGTH_SHORT).show()
                        }
                    }
                    
                    // Send to AI if needed
                    if (shouldSendToAI) {
                        Toast.makeText(this@MainActivity, "🤖 AI is thinking...", Toast.LENGTH_SHORT).show()
                        val editorContent = editorBinding.textArea.text.toString()
                        
                        // Use existing AI system
                        CollaborativeSessionManager.askAIInChat(message, editorContent) { question ->
                            // Build context-aware prompt
                            val prompt = if (editorContent.isNotBlank()) {
                                "Context from editor:\n${editorContent.take(500)}\n\nUser question: $question\n\nProvide a helpful response:"
                            } else {
                                question
                            }
                            callAI(prompt, maxTokens = 512)
                        }
                    }
                    
                    etChatMessage.text.clear()
                    // Reset to default visibility
                    messageVisibility = "everyone"
                    selectedUserIds.clear()
                    tvTargetDisplay.text = "Everyone"
                }
            }
        }
        
        // Media attachment buttons
        val btnAttachImage = dialogView.findViewById<ImageButton>(R.id.btnAttachImage)
        val btnAttachDocument = dialogView.findViewById<ImageButton>(R.id.btnAttachDocument)
        val btnAttachVoice = dialogView.findViewById<ImageButton>(R.id.btnAttachVoice)
        val btnAttachVideo = dialogView.findViewById<ImageButton>(R.id.btnAttachVideo)
        
        btnAttachImage?.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
            // TODO: Handle image attachment
            Toast.makeText(this, "📷 Image attachment coming soon", Toast.LENGTH_SHORT).show()
        }
        
        btnAttachDocument?.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            // TODO: Handle document attachment
            Toast.makeText(this, "📄 Document attachment coming soon", Toast.LENGTH_SHORT).show()
        }
        
        btnAttachVoice?.setOnClickListener {
            // Start voice recording
            Toast.makeText(this, "🎤 Voice recording coming soon", Toast.LENGTH_SHORT).show()
        }
        
        btnAttachVideo?.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply { type = "video/*" }
            // TODO: Handle video attachment
            Toast.makeText(this, "🎥 Video attachment coming soon", Toast.LENGTH_SHORT).show()
        }
        
        btnCreateTaskFromChat.setOnClickListener {
            val message = etChatMessage.text.toString().trim()
            if (message.isNotEmpty()) {
                // Animate button
                btnCreateTaskFromChat.animate()
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(100)
                    .withEndAction {
                        btnCreateTaskFromChat.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .start()
                    }
                    .start()
                
                lifecycleScope.launch {
                    val result = CollaborativeSessionManager.createTask(message, "", "next")
                    if (result.isSuccess) {
                        etChatMessage.text.clear()
                        Toast.makeText(this@MainActivity, "✓ Task created", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        
        dialog.show()
    }
    
    private fun showUserSelectionDialog(users: List<SessionUser>, onSelected: (List<String>) -> Unit) {
        if (users.isEmpty()) {
            Toast.makeText(this, "No other users in session", Toast.LENGTH_SHORT).show()
            return
        }
        
        val userNames = users.map { it.userName }.toTypedArray()
        val checkedItems = BooleanArray(users.size) { false }
        val selectedUsers = mutableListOf<String>()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Users (${users.size} available)")
            .setMultiChoiceItems(userNames, checkedItems) { _, which, isChecked ->
                if (isChecked) {
                    selectedUsers.add(users[which].userId)
                } else {
                    selectedUsers.remove(users[which].userId)
                }
            }
            .setPositiveButton("OK") { _, _ ->
                if (selectedUsers.isNotEmpty()) {
                    onSelected(selectedUsers)
                } else {
                    Toast.makeText(this, "Please select at least one user", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showSessionUsersDialog(sessionId: String, isCreator: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_session_users, null)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvSessionUsers)
        val btnEndSession = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEndSession)
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        val currentUserId = CollaborativeSessionManager.getCurrentUserId() ?: ""
        
        // Check if current user is the creator
        var isCurrentUserCreator = false
        lifecycleScope.launch {
            val session = CollaborativeSessionManager.observeSession(sessionId).first { it != null }
            isCurrentUserCreator = session?.creatorId == currentUserId
            
            // Show/hide end session button based on creator status
            btnEndSession.visibility = if (isCurrentUserCreator) View.VISIBLE else View.GONE
        }
        
        val adapter = SessionUsersAdapter(
            users = emptyList(),
            currentUserId = currentUserId,
            isCreator = isCreator,
            onKickUser = { user ->
                // Confirm before kicking
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Remove User?")
                    .setMessage("Remove ${user.userName} from this session?")
                    .setPositiveButton("Remove") { _, _ ->
                        lifecycleScope.launch {
                            val result = CollaborativeSessionManager.kickUser(sessionId, user.userId)
                            result.onSuccess {
                                Toast.makeText(this@MainActivity, "✓ ${user.userName} removed", Toast.LENGTH_SHORT).show()
                            }.onFailure { e ->
                                Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        recyclerView.adapter = adapter
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()
        
        // End session button (creator only)
        btnEndSession.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("End Session?")
                .setMessage("This will end the session for all users. Are you sure?")
                .setPositiveButton("End Session") { _, _ ->
                    lifecycleScope.launch {
                        // Show loading animation
                        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setMessage("Ending session...")
                            .setCancelable(false)
                            .create()
                        progressDialog.show()
                        
                        // Save session content before ending
                        val currentContent = editorBinding.textArea.text.toString()
                        val currentSession = CollaborativeSessionManager.getCurrentSession()
                        if (currentSession != null && currentContent.isNotBlank()) {
                            val creatorName = currentSession.users.values.firstOrNull { it.userId == currentSession.creatorId }?.userName ?: "Unknown"
                            saveCollaborativeSessionFile(currentSession.sessionId, currentContent, creatorName)
                        }
                        
                        val result = CollaborativeSessionManager.endSession(sessionId)
                        progressDialog.dismiss()
                        
                        result.onSuccess {
                            dialog.dismiss()
                            contentSyncJob?.cancel()
                            
                            // Clear all session cache and close dialogs
                            CollaborativeSessionManager.clearSessionCache(this@MainActivity)
                            activeSessionDialog?.dismiss()
                            activeSessionDialog = null
                            
                            removeSessionInfoBar()
                            
                            // Clear editor and go back to home with animation
                            editorBinding.textArea.animate()
                                .alpha(0f)
                                .setDuration(200)
                                .withEndAction {
                                    editorBinding.textArea.setText("")
                                    currentFileUri = null
                                    textChanged = false
                                    updateToolbarTitle()
                                    showHome()
                                    editorBinding.textArea.alpha = 1f
                                }
                                .start()
                            
                            Toast.makeText(this@MainActivity, "✓ Session ended and deleted", Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        // Observe users and update list in real-time
        val usersJob = lifecycleScope.launch {
            CollaborativeSessionManager.observeUsers(sessionId).collect { users ->
                android.util.Log.d("SessionDialog", "Received users update: ${users.size} users")
                users.values.forEach { user ->
                    android.util.Log.d("SessionDialog", "  User: ${user.userName}, typing=${user.typing}")
                }
                withContext(Dispatchers.Main) {
                    adapter.updateUsers(users.values.toList())
                }
            }
        }
        
        // Cancel observation when dialog is dismissed
        dialog.setOnDismissListener {
            usersJob.cancel()
        }
        
        dialog.show()
    }
}

// Custom TypefaceSpan that works with any Typeface
class CustomTypefaceSpan(private val typeface: Typeface) : android.text.style.MetricAffectingSpan(), android.os.Parcelable {
    override fun updateDrawState(ds: android.text.TextPaint) {
        applyCustomTypeface(ds, typeface)
    }

    override fun updateMeasureState(paint: android.text.TextPaint) {
        applyCustomTypeface(paint, typeface)
    }
    
    private fun applyCustomTypeface(paint: android.text.TextPaint, tf: Typeface) {
        val oldStyle = paint.typeface?.style ?: 0
        val fake = oldStyle and tf.style.inv()
        
        if (fake and Typeface.BOLD != 0) {
            paint.isFakeBoldText = true
        }
        
        if (fake and Typeface.ITALIC != 0) {
            paint.textSkewX = -0.25f
        }
        
        paint.typeface = tf
    }
    
    override fun describeContents(): Int = 0
    
    override fun writeToParcel(dest: android.os.Parcel, flags: Int) {
        // Parcelable implementation (empty for now)
    }
    
    companion object CREATOR : android.os.Parcelable.Creator<CustomTypefaceSpan> {
        override fun createFromParcel(parcel: android.os.Parcel): CustomTypefaceSpan {
            return CustomTypefaceSpan(Typeface.DEFAULT)
        }

        override fun newArray(size: Int): Array<CustomTypefaceSpan?> {
            return arrayOfNulls(size)
        }
    }
}
