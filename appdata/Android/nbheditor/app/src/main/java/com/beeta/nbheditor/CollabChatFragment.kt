package com.beeta.nbheditor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.beeta.nbheditor.databinding.FragmentCollabChatBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class CollabChatFragment : Fragment() {

    private var _binding: FragmentCollabChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: CollabChatAdapter
    // "everyone"  = all users, NO AI
    // "ai_only"   = AI only
    // "everyone_and_ai" = all users + AI
    // "selected_users"  = specific users
    private var messageVisibility = "everyone"
    private var selectedUserIds = mutableSetOf<String>()
    private var mediaRecorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var isRecording = false

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val name = getFileName(uri)
                sendAttachment(name, uri.toString(), "image")
            }
        }
    }

    private val documentPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val name = getFileName(uri)
                sendAttachment(name, uri.toString(), "document")
            }
        }
    }

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val name = getFileName(uri)
                sendAttachment(name, uri.toString(), "video")
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCollabChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sessionCode = CollaborativeSessionManager.getCurrentSessionId() ?: run {
            parentFragmentManager.popBackStack(); return
        }
        val currentUserId = CollaborativeSessionManager.getCurrentUserId() ?: run {
            parentFragmentManager.popBackStack(); return
        }

        applyTheme()
        setupAdapter(currentUserId)
        setupTargetSelector(sessionCode, currentUserId)
        setupSend(sessionCode)
        setupAttachments()
        setupCreateTask()
        observeMessages(sessionCode)

        binding.btnCloseChat.setOnClickListener { parentFragmentManager.popBackStack() }

        view.translationX = view.width.toFloat()
        view.animate().translationX(0f).setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun applyTheme() {
        val activity = requireActivity() as MainActivity
        val isGlass = activity.isGlassModePublic()
        if (isGlass) {
            binding.chatRoot.setBackgroundColor(0xBB0A0E14.toInt())
            binding.rvChatMessages.setBackgroundColor(0x00000000)
            binding.chatHeader.setBackgroundColor(0xCC1976D2.toInt())
            binding.quickActionsBar.setBackgroundColor(0xCC0D1117.toInt())
            binding.inputBar.setBackgroundColor(0xCC0D1117.toInt())
            binding.targetSelectorBar.setBackgroundColor(0xBB1A1F26.toInt())
            binding.etChatMessage.apply {
                setBackgroundColor(0xBB1A1F26.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0x88FFFFFF.toInt())
            }
            binding.btnSendMessage.setColorFilter(0xFF64B5F6.toInt())
            binding.btnCloseChat.setColorFilter(0xFFFFFFFF.toInt())
        } else {
            val isDark = resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (isDark) {
                val bg = resources.getColor(R.color.editor_bg, requireActivity().theme)
                val surface = resources.getColor(R.color.editor_surface, requireActivity().theme)
                binding.chatRoot.setBackgroundColor(bg)
                binding.rvChatMessages.setBackgroundColor(bg)
                binding.chatHeader.setBackgroundColor(0xFF1976D2.toInt())
                binding.quickActionsBar.setBackgroundColor(surface)
                binding.inputBar.setBackgroundColor(surface)
                binding.targetSelectorBar.setBackgroundColor(surface)
                binding.etChatMessage.apply {
                    setBackgroundColor(surface)
                    setTextColor(resources.getColor(R.color.editor_text, requireActivity().theme))
                    setHintTextColor(resources.getColor(R.color.editor_hint, requireActivity().theme))
                }
            } else {
                binding.chatRoot.setBackgroundColor(0xFFF5F5F5.toInt())
                binding.rvChatMessages.setBackgroundColor(0xFFFFFFFF.toInt())
                binding.chatHeader.setBackgroundColor(0xFF1976D2.toInt())
                binding.quickActionsBar.setBackgroundColor(0xFFFFFFFF.toInt())
                binding.inputBar.setBackgroundColor(0xFFFFFFFF.toInt())
                binding.targetSelectorBar.setBackgroundColor(0xFFF5F5F5.toInt())
                binding.etChatMessage.apply {
                    setBackgroundColor(0xFFFFFFFF.toInt())
                    setTextColor(0xFF212121.toInt())
                    setHintTextColor(0xFF757575.toInt())
                }
            }
        }
    }

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
                val options = arrayOf("In 1 hour", "In 3 hours", "Tomorrow")
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Set Reminder")
                    .setItems(options) { _, which ->
                        val time = when (which) {
                            0 -> System.currentTimeMillis() + 3_600_000L
                            1 -> System.currentTimeMillis() + 10_800_000L
                            else -> System.currentTimeMillis() + 86_400_000L
                        }
                        lifecycleScope.launch {
                            CollaborativeSessionManager.setMessageReminder(msg.messageId, time)
                            Toast.makeText(requireContext(), "⏰ Reminder set", Toast.LENGTH_SHORT).show()
                        }
                    }.show()
            }
        )
        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChatMessages.adapter = adapter
    }

    private fun setupTargetSelector(sessionCode: String, currentUserId: String) {
        binding.tvTargetDisplay.setOnClickListener {
            lifecycleScope.launch {
                val users = CollaborativeSessionManager.observeUsers(sessionCode).first()
                val others = users.values.filter { it.userId != currentUserId }.toList()
                // Options: @all = everyone (no AI), @ai = AI only, @all+ai = everyone+AI, specific users
                val options = mutableListOf(
                    "👥 @all — Everyone (no AI)",
                    "🤖 @ai — Beeta AI only",
                    "👥+🤖 Everyone + AI",
                    "👤 Select specific users"
                )
                others.forEach { options.add("👤 ${it.userName}") }

                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Send message to:")
                    .setItems(options.toTypedArray()) { _, which ->
                        when (which) {
                            0 -> { messageVisibility = "everyone"; selectedUserIds.clear(); binding.tvTargetDisplay.text = "@all" }
                            1 -> { messageVisibility = "ai_only"; selectedUserIds.clear(); binding.tvTargetDisplay.text = "@ai" }
                            2 -> { messageVisibility = "everyone_and_ai"; selectedUserIds.clear(); binding.tvTargetDisplay.text = "@all + AI" }
                            3 -> showUserSelectionDialog(others)
                            else -> {
                                val user = others[which - 4]
                                messageVisibility = "selected_users"
                                selectedUserIds = mutableSetOf(user.userId)
                                binding.tvTargetDisplay.text = "@${user.userName}"
                            }
                        }
                    }.show()
            }
        }

        // @mention detection — @all = everyone only (no AI), @ai = AI only
        binding.etChatMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s.toString()
                when {
                    text.startsWith("@ai ", ignoreCase = true) -> {
                        messageVisibility = "ai_only"; binding.tvTargetDisplay.text = "@ai"
                        binding.etChatMessage.setText(text.substring(4))
                        binding.etChatMessage.setSelection(binding.etChatMessage.text.length)
                    }
                    text.startsWith("@all ", ignoreCase = true) -> {
                        // @all = everyone, NO AI
                        messageVisibility = "everyone"; binding.tvTargetDisplay.text = "@all"
                        binding.etChatMessage.setText(text.substring(5))
                        binding.etChatMessage.setSelection(binding.etChatMessage.text.length)
                    }
                    text.startsWith("@") && text.contains(" ") -> {
                        val mention = text.substring(1, text.indexOf(" "))
                        lifecycleScope.launch {
                            val users = CollaborativeSessionManager.observeUsers(sessionCode).first()
                            val matched = users.values.find { it.userName.equals(mention, ignoreCase = true) }
                            if (matched != null) {
                                messageVisibility = "selected_users"
                                selectedUserIds = mutableSetOf(matched.userId)
                                binding.tvTargetDisplay.text = "@${matched.userName}"
                                binding.etChatMessage.setText(text.substring(text.indexOf(" ") + 1))
                                binding.etChatMessage.setSelection(binding.etChatMessage.text.length)
                            }
                        }
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupSend(sessionCode: String) {
        val activity = requireActivity() as MainActivity
        binding.btnSendMessage.setOnClickListener {
            val message = binding.etChatMessage.text.toString().trim()
            if (message.isEmpty()) return@setOnClickListener

            lifecycleScope.launch {
                // Send to users (not for ai_only)
                if (messageVisibility != "ai_only") {
                    CollaborativeSessionManager.sendChatMessage(
                        message = message,
                        targetType = messageVisibility,
                        targetUserIds = selectedUserIds.toList()
                    )
                }
                // Send to AI only when explicitly requested (NOT for plain @all)
                if (messageVisibility in listOf("ai_only", "everyone_and_ai")) {
                    Toast.makeText(requireContext(), "🤖 AI is thinking...", Toast.LENGTH_SHORT).show()
                    val editorContent = activity.getEditorText()
                    CollaborativeSessionManager.askAIInChat(message, editorContent) { question ->
                        val prompt = if (editorContent.isNotBlank())
                            "Context from editor:\n${editorContent.take(500)}\n\nUser question: $question\n\nProvide a helpful response:"
                        else question
                        activity.callAIPublic(prompt, 512)
                    }
                }
                binding.etChatMessage.text.clear()
                messageVisibility = "everyone"
                selectedUserIds.clear()
                binding.tvTargetDisplay.text = "@all"
            }
        }
    }

    private fun setupAttachments() {
        binding.btnAttachImage.setOnClickListener {
            imagePickerLauncher.launch(Intent(Intent.ACTION_PICK).apply { type = "image/*" })
        }
        binding.btnAttachDocument.setOnClickListener {
            documentPickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            })
        }
        binding.btnAttachVoice.setOnClickListener {
            if (isRecording) stopVoiceRecording() else startVoiceRecording()
        }
        binding.btnAttachVideo.setOnClickListener {
            videoPickerLauncher.launch(Intent(Intent.ACTION_PICK).apply { type = "video/*" })
        }
    }

    private fun startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 201); return
        }
        try {
            voiceFile = File(requireContext().cacheDir, "voice_${System.currentTimeMillis()}.3gp")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(requireContext()) else @Suppress("DEPRECATION") MediaRecorder()
            mediaRecorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(voiceFile!!.absolutePath)
                prepare(); start()
            }
            isRecording = true
            binding.btnAttachVoice.setColorFilter(0xFFFF0000.toInt())
            Toast.makeText(requireContext(), "🎤 Recording... tap again to stop", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVoiceRecording() {
        try {
            mediaRecorder?.stop(); mediaRecorder?.release(); mediaRecorder = null
            isRecording = false
            binding.btnAttachVoice.setColorFilter(0xFFFF5722.toInt())
            voiceFile?.let { file -> sendAttachment(file.name, file.absolutePath, "audio") }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Stop failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendAttachment(name: String, uriStr: String, type: String) {
        lifecycleScope.launch {
            CollaborativeSessionManager.sendChatMessage(
                message = name,
                targetType = messageVisibility,
                targetUserIds = selectedUserIds.toList(),
                attachmentUri = uriStr,
                attachmentType = type
            )
            Toast.makeText(requireContext(), "✓ Sent", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupCreateTask() {
        binding.btnCreateTaskFromChat.setOnClickListener {
            val message = binding.etChatMessage.text.toString().trim()
            if (message.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                if (CollaborativeSessionManager.createTask(message, "", "next").isSuccess) {
                    binding.etChatMessage.text.clear()
                    Toast.makeText(requireContext(), "✓ Task created", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun observeMessages(sessionCode: String) {
        lifecycleScope.launch {
            CollaborativeSessionManager.observeChatMessages(sessionCode).collect { messages ->
                val oldCount = adapter.itemCount
                adapter.updateMessages(messages)
                if (messages.isNotEmpty()) {
                    binding.rvChatMessages.smoothScrollToPosition(messages.size - 1)
                    if (messages.size > oldCount) {
                        binding.rvChatMessages.post {
                            binding.rvChatMessages.findViewHolderForAdapterPosition(messages.size - 1)
                                ?.itemView?.apply {
                                    alpha = 0f; translationX = 50f
                                    animate().alpha(1f).translationX(0f).setDuration(300).start()
                                }
                        }
                    }
                }
            }
        }
    }

    private fun showUserSelectionDialog(users: List<SessionUser>) {
        if (users.isEmpty()) { Toast.makeText(requireContext(), "No other users", Toast.LENGTH_SHORT).show(); return }
        val names = users.map { it.userName }.toTypedArray()
        val checked = BooleanArray(users.size)
        val selected = mutableListOf<String>()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Users")
            .setMultiChoiceItems(names, checked) { _, i, isChecked ->
                if (isChecked) selected.add(users[i].userId) else selected.remove(users[i].userId)
            }
            .setPositiveButton("OK") { _, _ ->
                if (selected.isNotEmpty()) {
                    messageVisibility = "selected_users"
                    selectedUserIds = selected.toMutableSet()
                    binding.tvTargetDisplay.text = "${selected.size} user(s)"
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun getFileName(uri: android.net.Uri): String = try {
        requireContext().contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "file"
    } catch (_: Exception) { uri.lastPathSegment ?: "file" }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isRecording) { mediaRecorder?.stop(); mediaRecorder?.release(); mediaRecorder = null }
        _binding = null
    }

    companion object {
        fun newInstance() = CollabChatFragment()
    }
}
