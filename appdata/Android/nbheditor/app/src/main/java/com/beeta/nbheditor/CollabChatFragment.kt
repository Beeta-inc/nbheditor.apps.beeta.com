package com.beeta.nbheditor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.beeta.nbheditor.databinding.FragmentCollabChatBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

class CollabChatFragment : Fragment() {

    private var _binding: FragmentCollabChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: CollabChatAdapter
    private var messageVisibility = "everyone"
    private var selectedUserIds = mutableSetOf<String>()
    private var mediaRecorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var isRecording = false
    private var videoJob: Job? = null
    private var userPhotoMap = mapOf<String, String>() // userId -> photoUrl

    // Mention popup
    private lateinit var mentionAdapter: MentionAdapter
    private var sessionUsers: List<SessionUser> = emptyList()

    data class MentionOption(val tag: String, val name: String, val desc: String, val visibility: String, val userId: String? = null)

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            result.data?.data?.let { sendAttachment(getFileName(it), it.toString(), "image") }
    }
    private val documentPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            result.data?.data?.let { sendAttachment(getFileName(it), it.toString(), "document") }
    }
    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            result.data?.data?.let { uri -> sendVideoWithCompression(uri) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCollabChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sessionCode = CollaborativeSessionManager.getCurrentSessionId() ?: run { parentFragmentManager.popBackStack(); return }
        val currentUserId = CollaborativeSessionManager.getCurrentUserId() ?: run { parentFragmentManager.popBackStack(); return }

        applyTheme()
        setupAdapter(currentUserId)
        setupMentionPopup(sessionCode, currentUserId)
        setupTargetChip(sessionCode, currentUserId)
        setupInput(sessionCode)
        setupAttachments()
        setupCreateTask()
        observeMessages(sessionCode)
        loadUsers(sessionCode, currentUserId)

        binding.btnCloseChat.setOnClickListener { parentFragmentManager.popBackStack() }

        view.translationX = view.width.toFloat()
        view.animate().translationX(0f).setDuration(280)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    // ── Theme ─────────────────────────────────────────────────────────────────

    private fun applyTheme() {
        val isGlass = (requireActivity() as MainActivity).isGlassModePublic()
        if (!isGlass) return
        binding.chatRoot.setBackgroundColor(0xBB0A0E14.toInt())
        binding.rvChatMessages.setBackgroundColor(0x00000000)
        binding.chatHeader.setBackgroundColor(0xCC1976D2.toInt())
        binding.inputBar.setBackgroundColor(0xCC0D1117.toInt())
        binding.etChatMessage.apply {
            setBackgroundColor(0x00000000)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x88FFFFFF.toInt())
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private fun setupAdapter(currentUserId: String) {
        adapter = CollabChatAdapter(
            messages = emptyList(),
            currentUserId = currentUserId,
            onMarkImportant = { msg ->
                lifecycleScope.launch { CollaborativeSessionManager.markMessageImportant(msg.messageId, !msg.isImportant) }
            },
            onCreateTask = { msg ->
                lifecycleScope.launch {
                    if (CollaborativeSessionManager.createTaskFromMessage(msg.messageId).isSuccess)
                        Toast.makeText(requireContext(), "✓ Task created", Toast.LENGTH_SHORT).show()
                }
            },
            onSetReminder = { msg ->
                val opts = arrayOf("In 1 hour", "In 3 hours", "Tomorrow")
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Set Reminder").setItems(opts) { _, w ->
                        val t = when (w) { 0 -> 3_600_000L; 1 -> 10_800_000L; else -> 86_400_000L }
                        lifecycleScope.launch {
                            CollaborativeSessionManager.setMessageReminder(msg.messageId, System.currentTimeMillis() + t)
                            Toast.makeText(requireContext(), "⏰ Reminder set", Toast.LENGTH_SHORT).show()
                        }
                    }.show()
            }
        )
        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.rvChatMessages.adapter = adapter
    }

    // ── Mention popup ─────────────────────────────────────────────────────────

    private fun loadUsers(sessionCode: String, currentUserId: String) {
        lifecycleScope.launch {
            CollaborativeSessionManager.observeUsers(sessionCode).collect { users ->
                sessionUsers = users.values.filter { it.userId != currentUserId }
                // Build photo map for ALL users (including self for display purposes)
                val map = mutableMapOf<String, String>()
                users.values.forEach { user ->
                    if (user.photoUrl.isNotBlank()) map[user.userId] = user.photoUrl
                }
                // Also add current user's own photo from Google sign-in
                val myPhoto = GoogleSignInHelper.getUserPhotoUrl(requireContext())
                if (!myPhoto.isNullOrBlank()) map[currentUserId] = myPhoto
                userPhotoMap = map
                adapter.updatePhotoMap(userPhotoMap)
            }
        }
    }

    private fun buildMentionOptions(filter: String): List<MentionOption> {
        val fixed = listOf(
            MentionOption("@all", "Everyone", "Send to all users (no AI)", "everyone"),
            MentionOption("@ai", "Beeta AI", "Send to AI only", "ai_only"),
            MentionOption("@all+ai", "Everyone + AI", "Send to all users and AI", "everyone_and_ai")
        )
        val users = sessionUsers.map {
            MentionOption("@${it.userName}", it.userName, it.email.ifBlank { "Team member" }, "selected_users", it.userId)
        }
        val all = fixed + users
        return if (filter.isEmpty()) all
        else all.filter { it.tag.contains(filter, ignoreCase = true) || it.name.contains(filter, ignoreCase = true) }
    }

    private fun setupMentionPopup(sessionCode: String, currentUserId: String) {
        mentionAdapter = MentionAdapter(emptyList()) { option ->
            hideMentionPopup()
            applyMention(option)
        }
        binding.rvMentionSuggestions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMentionSuggestions.adapter = mentionAdapter

        binding.etChatMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString() ?: return
                val atIdx = text.lastIndexOf('@')
                if (atIdx >= 0) {
                    val query = text.substring(atIdx + 1)
                    // Only show popup if no space after @
                    if (!query.contains(' ')) {
                        val options = buildMentionOptions(query)
                        if (options.isNotEmpty()) {
                            mentionAdapter.update(options)
                            binding.mentionPopup.visibility = View.VISIBLE
                            return
                        }
                    }
                }
                hideMentionPopup()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun applyMention(option: MentionOption) {
        // Replace the @... fragment in the input with nothing (mention applied via chip)
        val text = binding.etChatMessage.text.toString()
        val atIdx = text.lastIndexOf('@')
        if (atIdx >= 0) {
            binding.etChatMessage.setText(text.substring(0, atIdx))
            binding.etChatMessage.setSelection(binding.etChatMessage.text.length)
        }
        // Apply visibility
        messageVisibility = option.visibility
        if (option.userId != null) {
            selectedUserIds = mutableSetOf(option.userId)
        } else {
            selectedUserIds.clear()
        }
        binding.tvTargetDisplay.text = option.tag
    }

    private fun hideMentionPopup() {
        binding.mentionPopup.visibility = View.GONE
    }

    // ── Target chip (tap to change) ───────────────────────────────────────────

    private fun setupTargetChip(sessionCode: String, currentUserId: String) {
        binding.targetSelectorBar.setOnClickListener {
            lifecycleScope.launch {
                val users = CollaborativeSessionManager.observeUsers(sessionCode).first()
                val others = users.values.filter { it.userId != currentUserId }.toList()
                val options = mutableListOf("👥 @all — Everyone (no AI)", "🤖 @ai — Beeta AI only", "👥+🤖 @all+ai — Everyone + AI", "👤 Select specific users")
                others.forEach { options.add("👤 @${it.userName}") }
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Send to:")
                    .setItems(options.toTypedArray()) { _, which ->
                        when (which) {
                            0 -> { messageVisibility = "everyone"; selectedUserIds.clear(); binding.tvTargetDisplay.text = "@all" }
                            1 -> { messageVisibility = "ai_only"; selectedUserIds.clear(); binding.tvTargetDisplay.text = "@ai" }
                            2 -> { messageVisibility = "everyone_and_ai"; selectedUserIds.clear(); binding.tvTargetDisplay.text = "@all+ai" }
                            3 -> showUserSelectionDialog(others)
                            else -> {
                                val u = others[which - 4]
                                messageVisibility = "selected_users"; selectedUserIds = mutableSetOf(u.userId)
                                binding.tvTargetDisplay.text = "@${u.userName}"
                            }
                        }
                    }.show()
            }
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    private fun setupInput(sessionCode: String) {
        val activity = requireActivity() as MainActivity
        binding.btnSendMessage.setOnClickListener {
            val message = binding.etChatMessage.text.toString().trim()
            if (message.isEmpty()) return@setOnClickListener
            hideMentionPopup()
            lifecycleScope.launch {
                if (messageVisibility != "ai_only") {
                    CollaborativeSessionManager.sendChatMessage(message = message, targetType = messageVisibility, targetUserIds = selectedUserIds.toList())
                }
                if (messageVisibility in listOf("ai_only", "everyone_and_ai")) {
                    Toast.makeText(requireContext(), "🤖 AI is thinking...", Toast.LENGTH_SHORT).show()
                    val editorContent = activity.getEditorText()
                    CollaborativeSessionManager.askAIInChat(message, editorContent) { q ->
                        val prompt = if (editorContent.isNotBlank()) "Context:\n${editorContent.take(500)}\n\nQuestion: $q\n\nAnswer:" else q
                        activity.callAIPublic(prompt, 512)
                    }
                }
                binding.etChatMessage.text.clear()
                messageVisibility = "everyone"; selectedUserIds.clear(); binding.tvTargetDisplay.text = "@all"
            }
        }
    }

    // ── Attachments ───────────────────────────────────────────────────────────

    private fun setupAttachments() {
        binding.btnAttachImage.setOnClickListener {
            imagePickerLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            })
        }
        binding.btnAttachDocument.setOnClickListener {
            documentPickerLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/pdf",
                    "application/rtf",
                    "text/rtf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "text/plain", "text/csv", "application/zip"
                ))
            })
        }
        binding.btnAttachVoice.setOnClickListener { if (isRecording) stopVoiceRecording() else startVoiceRecording() }
        binding.btnAttachVideo.setOnClickListener {
            videoPickerLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            })
        }
    }

    private fun startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 201); return
        }
        try {
            voiceFile = File(requireContext().cacheDir, "voice_${System.currentTimeMillis()}.3gp")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(requireContext())
            else @Suppress("DEPRECATION") MediaRecorder()
            mediaRecorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB); setOutputFile(voiceFile!!.absolutePath)
                prepare(); start()
            }
            isRecording = true
            binding.btnAttachVoice.setColorFilter(0xFFFF0000.toInt())
            Toast.makeText(requireContext(), "🎤 Recording... tap again to stop", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(requireContext(), "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun stopVoiceRecording() {
        try {
            mediaRecorder?.stop(); mediaRecorder?.release(); mediaRecorder = null; isRecording = false
            binding.btnAttachVoice.clearColorFilter()
            voiceFile?.let { sendAttachment(it.name, it.absolutePath, "audio") }
        } catch (e: Exception) { Toast.makeText(requireContext(), "Stop failed: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun sendAttachment(name: String, uriStr: String, type: String) {
        lifecycleScope.launch {
            val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                isIndeterminate = false
            }
            val tvStatus = TextView(requireContext()).apply { 
                text = "Uploading $name... 0%"
                textSize = 14f
            }
            val tvSpeed = TextView(requireContext()).apply {
                text = "Preparing..."
                textSize = 12f
                setTextColor(0xFF888888.toInt())
            }
            val container = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 32, 48, 16)
                addView(tvStatus)
                addView(progressBar)
                addView(tvSpeed)
            }
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle("Uploading to Firebase")
                .setView(container)
                .setCancelable(false)
                .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                .create()
            dialog.show()
            
            var startTime = System.currentTimeMillis()
            var lastProgress = 0
            
            try {
                val result = CollaborativeSessionManager.sendChatMessage(
                    message = name,
                    targetType = messageVisibility,
                    targetUserIds = selectedUserIds.toList(),
                    attachmentUri = uriStr,
                    attachmentType = type,
                    onProgress = { progress ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            progressBar.progress = progress
                            tvStatus.text = "Uploading $name... $progress%"
                            
                            // Calculate speed and time remaining
                            if (progress > lastProgress && progress > 0) {
                                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                                val speed = progress / elapsed
                                val remaining = ((100 - progress) / speed).toInt()
                                
                                val speedText = when {
                                    speed > 10 -> "Fast"
                                    speed > 5 -> "Normal"
                                    else -> "Slow"
                                }
                                
                                tvSpeed.text = "Speed: $speedText • ${remaining}s remaining"
                                lastProgress = progress
                            }
                        }
                    }
                )
                
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), "✓ Sent successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                dialog.dismiss()
            }
        }
    }

    private fun sendVideoWithCompression(uri: Uri) {
        val name = getFileName(uri)
        videoJob = lifecycleScope.launch {
            val fileSizeBytes = withContext(Dispatchers.IO) {
                requireContext().contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }
            val needsCompression = fileSizeBytes > 100 * 1024 * 1024L

            val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; isIndeterminate = false
            }
            val tvStatus = TextView(requireContext()).apply { text = if (needsCompression) "Compressing..." else "Uploading..." }
            val container = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 32, 48, 16)
                addView(tvStatus)
                addView(progressBar)
            }
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(if (needsCompression) "Preparing video" else "Uploading")
                .setView(container)
                .setCancelable(false)
                .setNegativeButton("Cancel") { d, _ -> d.dismiss(); videoJob?.cancel() }
                .create()
            dialog.show()

            var uploadUri = uri.toString()

            if (needsCompression) {
                val compressed = withContext(Dispatchers.IO) {
                    try {
                        compressVideo(uri) { pct ->
                            if (!isActive) return@compressVideo
                            lifecycleScope.launch(Dispatchers.Main) {
                                progressBar.progress = (pct * 0.7f).toInt()
                                tvStatus.text = "Compressing... ${(pct * 0.7f).toInt()}%"
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        null
                    }
                }
                // Cancelled — dialog already dismissed by button, just stop
                if (!isActive) { dialog.dismiss(); return@launch }

                if (compressed == null) {
                    dialog.dismiss()
                    AlertDialog.Builder(requireContext())
                        .setTitle("Compression failed")
                        .setMessage("Sending original file instead.")
                        .setPositiveButton("Send anyway") { _, _ ->
                            videoJob = lifecycleScope.launch { doUpload(uri.toString(), name) }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@launch
                } else {
                    uploadUri = compressed.toURI().toString()
                }
            }

            dialog.dismiss()
            doUpload(uploadUri, name, startPct = if (needsCompression) 70 else 0)
        }
    }

    private suspend fun doUpload(uriStr: String, name: String, startPct: Int = 0) {
        val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = startPct; isIndeterminate = false
        }
        val tvStatus = TextView(requireContext()).apply { 
            text = "Uploading... $startPct%"
            textSize = 14f
        }
        val tvSpeed = TextView(requireContext()).apply {
            text = "Preparing..."
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
            addView(tvStatus); addView(progressBar); addView(tvSpeed)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Uploading Video")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss(); videoJob?.cancel() }
            .create()
        dialog.show()

        var startTime = System.currentTimeMillis()
        var lastProgress = startPct
        
        try {
            val result = CollaborativeSessionManager.sendChatMessage(
                message = name,
                targetType = messageVisibility,
                targetUserIds = selectedUserIds.toList(),
                attachmentUri = uriStr,
                attachmentType = "video",
                onProgress = { progress ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        val adjustedProgress = startPct + ((progress * (100 - startPct)) / 100)
                        progressBar.progress = adjustedProgress
                        tvStatus.text = "Uploading... $adjustedProgress%"
                        
                        if (adjustedProgress > lastProgress && adjustedProgress > startPct) {
                            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                            val speed = (adjustedProgress - startPct) / elapsed
                            val remaining = ((100 - adjustedProgress) / speed).toInt()
                            
                            val speedText = when {
                                speed > 10 -> "Fast"
                                speed > 5 -> "Normal"
                                else -> "Slow"
                            }
                            
                            tvSpeed.text = "Speed: $speedText • ${remaining}s remaining"
                            lastProgress = adjustedProgress
                        }
                    }
                }
            )
            
            if (result.isSuccess) {
                withContext(Dispatchers.Main) {
                    progressBar.progress = 100
                    tvStatus.text = "Done!"
                    tvSpeed.text = "Upload complete"
                }
                kotlinx.coroutines.delay(500)
                Toast.makeText(requireContext(), "✓ Sent", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Upload failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException)
                Toast.makeText(requireContext(), "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            withContext(Dispatchers.Main) { dialog.dismiss() }
        }
    }

    private fun buildUploadDialog(name: String): AlertDialog {
        val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
            addView(TextView(requireContext()).apply { text = "Sending $name..." })
            addView(progressBar)
        }
        return AlertDialog.Builder(requireContext()).setView(container).setCancelable(false).create()
    }

    /** Compress video using MediaCodec. Returns output File or throws. */
    private fun compressVideo(uri: Uri, onProgress: (Int) -> Unit): File {
        val outFile = File(requireContext().cacheDir, "compressed_${System.currentTimeMillis()}.mp4")
        val extractor = MediaExtractor()
        extractor.setDataSource(requireContext(), uri, null)

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && videoTrackIndex < 0) { videoTrackIndex = i; videoFormat = fmt }
            else if (mime.startsWith("audio/") && audioTrackIndex < 0) { audioTrackIndex = i; audioFormat = fmt }
        }
        if (videoTrackIndex < 0) throw IllegalStateException("No video track")

        val srcWidth = videoFormat!!.getInteger(MediaFormat.KEY_WIDTH)
        val srcHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val duration = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) videoFormat.getLong(MediaFormat.KEY_DURATION) else 0L

        // Scale down to max 720p
        val scale = if (srcWidth > 1280 || srcHeight > 720) minOf(1280f / srcWidth, 720f / srcHeight) else 1f
        val outW = (srcWidth * scale).toInt().let { if (it % 2 != 0) it - 1 else it }
        val outH = (srcHeight * scale).toInt().let { if (it % 2 != 0) it - 1 else it }

        val encFormat = MediaFormat.createVideoFormat("video/avc", outW, outH).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }

        val encoder = MediaCodec.createEncoderByType("video/avc")
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(videoFormat, inputSurface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        // Pass-through audio
        if (audioTrackIndex >= 0 && audioFormat != null) {
            muxerAudioTrack = muxer.addTrack(audioFormat)
        }

        extractor.selectTrack(videoTrackIndex)
        val bufInfo = MediaCodec.BufferInfo()
        var decoderDone = false
        var encoderDone = false
        var processedUs = 0L

        while (!encoderDone) {
            // Feed decoder
            if (!decoderDone) {
                val inIdx = decoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        decoderDone = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            // Drain decoder -> encoder surface (automatic via inputSurface)
            val decOutIdx = decoder.dequeueOutputBuffer(bufInfo, 10_000)
            if (decOutIdx >= 0) {
                decoder.releaseOutputBuffer(decOutIdx, true) // render to surface
                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    encoder.signalEndOfInputStream()
                }
            }
            // Drain encoder
            val encOutIdx = encoder.dequeueOutputBuffer(bufInfo, 10_000)
            when {
                encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                    if (muxerAudioTrack < 0 || audioTrackIndex < 0) { muxer.start(); muxerStarted = true }
                    else { muxer.start(); muxerStarted = true }
                }
                encOutIdx >= 0 -> {
                    val encBuf = encoder.getOutputBuffer(encOutIdx)!!
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxerStarted) {
                        muxer.writeSampleData(muxerVideoTrack, encBuf, bufInfo)
                        processedUs = bufInfo.presentationTimeUs
                        if (duration > 0) onProgress(((processedUs.toFloat() / duration) * 100).toInt().coerceIn(0, 99))
                    }
                    encoder.releaseOutputBuffer(encOutIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                }
            }
        }

        // Pass-through audio track
        if (audioTrackIndex >= 0 && muxerAudioTrack >= 0 && muxerStarted) {
            extractor.unselectTrack(videoTrackIndex)
            extractor.selectTrack(audioTrackIndex)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val audioBuf = ByteBuffer.allocate(256 * 1024)
            val audioBufInfo = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(audioBuf, 0)
                if (size < 0) break
                audioBufInfo.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(muxerAudioTrack, audioBuf, audioBufInfo)
                extractor.advance()
            }
        }

        decoder.stop(); decoder.release()
        encoder.stop(); encoder.release()
        extractor.release()
        muxer.stop(); muxer.release()
        onProgress(100)
        return outFile
    }

    // ── Create task ───────────────────────────────────────────────────────────

    private fun setupCreateTask() {
        binding.btnCreateTaskFromChat.setOnClickListener {
            val msg = binding.etChatMessage.text.toString().trim()
            if (msg.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                if (CollaborativeSessionManager.createTask(msg, "", "next").isSuccess) {
                    binding.etChatMessage.text.clear()
                    Toast.makeText(requireContext(), "✓ Task created", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Observe messages ──────────────────────────────────────────────────────

    private fun observeMessages(sessionCode: String) {
        lifecycleScope.launch {
            CollaborativeSessionManager.observeChatMessages(sessionCode).collect { messages ->
                val oldCount = adapter.itemCount
                adapter.updateMessages(messages)
                if (messages.isNotEmpty()) {
                    binding.rvChatMessages.scrollToPosition(messages.size - 1)
                    if (messages.size > oldCount) {
                        binding.rvChatMessages.post {
                            binding.rvChatMessages.findViewHolderForAdapterPosition(messages.size - 1)
                                ?.itemView?.apply { alpha = 0f; animate().alpha(1f).setDuration(200).start() }
                        }
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun showUserSelectionDialog(users: List<SessionUser>) {
        if (users.isEmpty()) { Toast.makeText(requireContext(), "No other users", Toast.LENGTH_SHORT).show(); return }
        val names = users.map { it.userName }.toTypedArray()
        val checked = BooleanArray(users.size)
        val selected = mutableListOf<String>()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Users")
            .setMultiChoiceItems(names, checked) { _, i, on -> if (on) selected.add(users[i].userId) else selected.remove(users[i].userId) }
            .setPositiveButton("OK") { _, _ ->
                if (selected.isNotEmpty()) { messageVisibility = "selected_users"; selectedUserIds = selected.toMutableSet(); binding.tvTargetDisplay.text = "${selected.size} user(s)" }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun getFileName(uri: android.net.Uri): String = try {
        requireContext().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "file"
    } catch (_: Exception) { uri.lastPathSegment ?: "file" }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isRecording) { mediaRecorder?.stop(); mediaRecorder?.release(); mediaRecorder = null }
        videoJob?.cancel()
        _binding = null
    }

    companion object { fun newInstance() = CollabChatFragment() }

    // ── Mention RecyclerView Adapter ──────────────────────────────────────────

    inner class MentionAdapter(
        private var items: List<MentionOption>,
        private val onClick: (MentionOption) -> Unit
    ) : RecyclerView.Adapter<MentionAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: TextView = v.findViewById(R.id.tvMentionAvatar)
            val name: TextView = v.findViewById(R.id.tvMentionName)
            val desc: TextView = v.findViewById(R.id.tvMentionDesc)
            val tag: TextView = v.findViewById(R.id.tvMentionTag)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_mention_suggestion, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.avatar.text = when (item.visibility) {
                "ai_only" -> "✦"; "everyone_and_ai" -> "✦"; else -> item.name.firstOrNull()?.uppercase() ?: "@"
            }
            holder.name.text = item.name
            holder.desc.text = item.desc
            holder.tag.text = item.tag
            holder.itemView.setOnClickListener { onClick(item) }
        }

        fun update(new: List<MentionOption>) { items = new; notifyDataSetChanged() }
    }
}
