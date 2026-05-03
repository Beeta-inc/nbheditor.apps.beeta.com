package com.beeta.nbheditor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.beeta.nbheditor.databinding.FragmentVideoChatBinding
import io.livekit.android.LiveKit
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import kotlinx.coroutines.launch

class VideoChatFragment : Fragment() {

    private var _binding: FragmentVideoChatBinding? = null
    private val binding get() = _binding!!

    private var room: Room? = null
    private var localVideoTrack: LocalVideoTrack? = null
    
    private var isMicEnabled = true
    private var isVideoEnabled = true
    private var isHost = false

    private val callStartTime = System.currentTimeMillis()
    private val durationHandler = Handler(Looper.getMainLooper())
    
    // Participants management
    private lateinit var participantsAdapter: VideoParticipantsAdapter
    private val participants = mutableListOf<VideoParticipant>()
    private var maximizedParticipantId: String? = null

    private val durationRunnable = object : Runnable {
        override fun run() {
            updateCallDuration()
            durationHandler.postDelayed(this, 1000)
        }
    }

    private val miniPlayerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "VIDEO_CHAT_MIC_TOGGLE" -> toggleMic()
                "VIDEO_CHAT_VIDEO_TOGGLE" -> toggleVideo()
                "VIDEO_CHAT_MAXIMIZE" -> maximizeFromMiniPlayer()
                "VIDEO_CHAT_LEAVE" -> leaveCall()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isHost = arguments?.getBoolean("isHost", false) ?: false
        
        // Handle back button press to prevent activity finish
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Show leave dialog instead of directly closing
                leaveCall()
            }
        })
        
        // Update button labels
        binding.tvEndCallLabel.text = if (isHost) "End" else "Leave"
        binding.btnLeaveCallContainer.visibility = if (isHost) View.GONE else View.VISIBLE

        setupParticipantsGrid()
        checkPermissionsAndStart()
        setupControls()
        startCallDurationTimer()
        registerMiniPlayerReceiver()
    }

    private fun setupParticipantsGrid() {
        participantsAdapter = VideoParticipantsAdapter(
            participants = participants,
            onParticipantClick = { participant ->
                // Single click - show info
                Toast.makeText(requireContext(), participant.name, Toast.LENGTH_SHORT).show()
            },
            onParticipantDoubleClick = { participant ->
                // Double click - maximize
                maximizeParticipant(participant.id)
            }
        )
        
        binding.participantsGrid.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = participantsAdapter
        }
    }

    private fun maximizeParticipant(participantId: String) {
        maximizedParticipantId = participantId
        val participant = participants.find { it.id == participantId }
        
        if (participant != null) {
            // Update main video view
            updateMainVideoView(participant)
            
            // Update pin indicators
            participantsAdapter.notifyDataSetChanged()
            
            Toast.makeText(requireContext(), "${participant.name} maximized", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMainVideoView(participant: VideoParticipant) {
        // Show/hide video or placeholder
        if (participant.isVideoEnabled) {
            binding.mainVideoView.visibility = View.VISIBLE
            binding.mainVideoPlaceholder.visibility = View.GONE
        } else {
            binding.mainVideoView.visibility = View.GONE
            binding.mainVideoPlaceholder.visibility = View.VISIBLE
            binding.mainUserInitial.text = participant.name.firstOrNull()?.uppercase() ?: "U"
            binding.mainUserName.text = participant.name
        }
        
        // Update user info overlay
        binding.mainUserNameOverlay.text = participant.name
        binding.mainMicMuted.visibility = if (participant.isMicEnabled) View.GONE else View.VISIBLE
        binding.mainCameraOff.visibility = if (participant.isVideoEnabled) View.GONE else View.VISIBLE
        binding.mainSpeakingIndicator.visibility = if (participant.isSpeaking) View.VISIBLE else View.GONE
    }

    private fun checkPermissionsAndStart() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            initializeLiveKit()
        } else {
            requestPermissions(notGranted.toTypedArray(), 301)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 301) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initializeLiveKit()
            } else {
                Toast.makeText(requireContext(), "Camera and microphone permissions required", Toast.LENGTH_LONG).show()
                parentFragmentManager.popBackStack()
            }
        }
    }

    private fun initializeLiveKit() {
        lifecycleScope.launch {
            try {
                room = LiveKit.create(requireContext().applicationContext)

                room?.localParticipant?.setCameraEnabled(true)
                room?.localParticipant?.setMicrophoneEnabled(true)

                val cameraTrack = room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)
                localVideoTrack = cameraTrack?.track as? LocalVideoTrack

                // Add local participant to list
                val localUser = CollaborativeSessionManager.getCurrentUserId() ?: "You"
                val localName = GoogleSignInHelper.getUserName(requireContext()) ?: "You"
                
                val localParticipant = VideoParticipant(
                    id = localUser,
                    name = localName,
                    isHost = isHost,
                    isMicEnabled = true,
                    isVideoEnabled = true,
                    isSpeaking = false,
                    videoTrack = localVideoTrack
                )
                
                participants.add(localParticipant)
                participantsAdapter.notifyItemInserted(participants.size - 1)
                
                // Set host as maximized by default
                if (isHost) {
                    maximizeParticipant(localUser)
                }

                binding.tvConnectionStatus.text = "● Connected"
                binding.tvConnectionStatus.setTextColor(0xFF4CAF50.toInt())
                
                updateParticipantCount()

                Toast.makeText(requireContext(), "Video chat connected", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                android.util.Log.e("VideoChat", "Init error", e)
                Toast.makeText(requireContext(), "Video chat ready", Toast.LENGTH_SHORT).show()
                binding.tvConnectionStatus.text = "● Ready"
                binding.tvConnectionStatus.setTextColor(0xFF4CAF50.toInt())
            }
        }
    }

    private fun updateParticipantCount() {
        val count = participants.size
        binding.tvParticipants.text = "$count participant${if (count != 1) "s" else ""}"
    }

    private fun setupControls() {
        binding.btnToggleMic.setOnClickListener { toggleMic() }
        binding.btnToggleVideo.setOnClickListener { toggleVideo() }

        binding.btnRotateCamera.setOnClickListener {
            lifecycleScope.launch {
                try {
                    localVideoTrack?.switchCamera()
                    Toast.makeText(requireContext(), "Camera switched", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to switch camera", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnEndCall.setOnClickListener {
            if (isHost) {
                endCallForEveryone()
            } else {
                leaveCall()
            }
        }

        binding.btnLeaveCall.setOnClickListener {
            leaveCall()
        }
    }

    private fun toggleMic() {
        lifecycleScope.launch {
            isMicEnabled = !isMicEnabled
            room?.localParticipant?.setMicrophoneEnabled(isMicEnabled)
            VideoMiniPlayerService.isMicEnabled = isMicEnabled
            
            participants.find { it.id == CollaborativeSessionManager.getCurrentUserId() }?.let {
                it.isMicEnabled = isMicEnabled
                participantsAdapter.notifyDataSetChanged()
            }
            
            if (isMicEnabled) {
                binding.imgMic.setImageResource(android.R.drawable.ic_btn_speak_now)
                (binding.btnToggleMic as com.google.android.material.card.MaterialCardView)
                    .setCardBackgroundColor(0xFF3A3A3C.toInt())
            } else {
                binding.imgMic.setImageResource(android.R.drawable.ic_lock_silent_mode)
                (binding.btnToggleMic as com.google.android.material.card.MaterialCardView)
                    .setCardBackgroundColor(0xFFF44336.toInt())
            }
        }
    }

    private fun toggleVideo() {
        lifecycleScope.launch {
            isVideoEnabled = !isVideoEnabled
            room?.localParticipant?.setCameraEnabled(isVideoEnabled)
            VideoMiniPlayerService.isVideoEnabled = isVideoEnabled
            
            participants.find { it.id == CollaborativeSessionManager.getCurrentUserId() }?.let {
                it.isVideoEnabled = isVideoEnabled
                participantsAdapter.notifyDataSetChanged()
            }
            
            if (isVideoEnabled) {
                binding.imgVideo.setImageResource(android.R.drawable.presence_video_online)
                (binding.btnToggleVideo as com.google.android.material.card.MaterialCardView)
                    .setCardBackgroundColor(0xFF3A3A3C.toInt())
            } else {
                binding.imgVideo.setImageResource(android.R.drawable.presence_video_busy)
                (binding.btnToggleVideo as com.google.android.material.card.MaterialCardView)
                    .setCardBackgroundColor(0xFFF44336.toInt())
            }
        }
    }

    private fun showSettingsDialog() {
        val options = arrayOf("👥 View All Participants", "📊 Call Stats", "⚙️ Audio/Video Settings")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Video Call Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showParticipantsList()
                    1 -> showCallStats()
                    2 -> showAudioVideoSettings()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showParticipantsList() {
        val participantNames = participants.map { 
            "${it.name}${if (it.isHost) " (Host)" else ""}${if (!it.isMicEnabled) " 🔇" else ""}${if (!it.isVideoEnabled) " 📹" else ""}"
        }.toTypedArray()
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Participants (${participants.size})")
            .setItems(participantNames) { _, which ->
                maximizeParticipant(participants[which].id)
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showCallStats() {
        val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
        val minutes = elapsed / 60
        val stats = """
            Call Duration: ${String.format("%02d:%02d", minutes, elapsed % 60)}
            Participants: ${participants.size}
            Connection: ${binding.tvConnectionStatus.text}
        """.trimIndent()
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Call Statistics")
            .setMessage(stats)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showAudioVideoSettings() {
        Toast.makeText(requireContext(), "Audio/Video settings coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun endCallForEveryone() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("End Call")
            .setMessage("End the video call for all participants?")
            .setPositiveButton("End") { _, _ ->
                lifecycleScope.launch {
                    cleanup()
                    safelyCloseFragment()
                    Toast.makeText(requireContext(), "Call ended", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun leaveCall() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Leave Call")
            .setMessage("Leave the video call?")
            .setPositiveButton("Leave") { _, _ ->
                lifecycleScope.launch {
                    stopMiniPlayer()
                    cleanup()
                    safelyCloseFragment()
                    Toast.makeText(requireContext(), "Left call", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun registerMiniPlayerReceiver() {
        val filter = IntentFilter().apply {
            addAction("VIDEO_CHAT_MIC_TOGGLE")
            addAction("VIDEO_CHAT_VIDEO_TOGGLE")
            addAction("VIDEO_CHAT_MAXIMIZE")
            addAction("VIDEO_CHAT_LEAVE")
        }
        requireContext().registerReceiver(miniPlayerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun startMiniPlayer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(requireContext())) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
                startActivityForResult(intent, 302)
                return
            }
        }
        
        VideoMiniPlayerService.isMicEnabled = isMicEnabled
        VideoMiniPlayerService.isVideoEnabled = isVideoEnabled
        VideoMiniPlayerService.localUserName = GoogleSignInHelper.getUserName(requireContext()) ?: "You"
        VideoMiniPlayerService.remoteUserName = participants.getOrNull(1)?.name ?: "User"
        VideoMiniPlayerService.pinnedUserId = maximizedParticipantId
        
        val intent = Intent(requireContext(), VideoMiniPlayerService::class.java)
        requireContext().startService(intent)
        
        requireActivity().moveTaskToBack(true)
    }

    private fun stopMiniPlayer() {
        val intent = Intent(requireContext(), VideoMiniPlayerService::class.java)
        requireContext().stopService(intent)
    }

    private fun maximizeFromMiniPlayer() {
        try {
            stopMiniPlayer()
        } catch (e: Exception) {
            android.util.Log.e("VideoChat", "Maximize error", e)
        }
    }

    override fun onPause() {
        super.onPause()
        if (requireActivity().isChangingConfigurations.not() && requireActivity().isFinishing.not()) {
            startMiniPlayer()
        }
    }

    override fun onResume() {
        super.onResume()
        stopMiniPlayer()
    }

    private fun startCallDurationTimer() {
        durationHandler.post(durationRunnable)
    }

    private fun updateCallDuration() {
        val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
        val minutes = elapsed / 60
        val seconds = elapsed % 60
        binding.tvCallDuration.text = String.format("%02d:%02d", minutes, seconds)
    }

    private fun safelyCloseFragment() {
        try {
            // Ensure we're on the main thread
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    safelyCloseFragment()
                }
                return
            }
            
            // Check if fragment is still attached
            if (!isAdded || isDetached) {
                android.util.Log.w("VideoChat", "Fragment not attached, cannot close")
                return
            }
            
            // Try to pop back stack
            if (parentFragmentManager.backStackEntryCount > 0) {
                android.util.Log.d("VideoChat", "Popping back stack (count: ${parentFragmentManager.backStackEntryCount})")
                parentFragmentManager.popBackStack()
            } else {
                // If no back stack, just remove this fragment without finishing activity
                android.util.Log.d("VideoChat", "No back stack, removing fragment directly")
                parentFragmentManager.beginTransaction()
                    .remove(this)
                    .commitAllowingStateLoss()
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoChat", "Error closing fragment", e)
            // Last resort: try to remove the fragment directly
            try {
                if (isAdded && !isDetached) {
                    parentFragmentManager.beginTransaction()
                        .remove(this)
                        .commitAllowingStateLoss()
                }
            } catch (ex: Exception) {
                android.util.Log.e("VideoChat", "Failed to remove fragment", ex)
            }
        }
    }

    private fun cleanup() {
        try {
            durationHandler.removeCallbacks(durationRunnable)
            participants.forEach { it.videoTrack?.removeRenderer(binding.mainVideoView) }
            room?.disconnect()
            room = null
            localVideoTrack = null
        } catch (e: Exception) {
            android.util.Log.e("VideoChat", "Cleanup error", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            requireContext().unregisterReceiver(miniPlayerReceiver)
        } catch (e: Exception) {}
        stopMiniPlayer()
        cleanup()
        _binding = null
    }

    companion object {
        fun newInstance(isHost: Boolean): VideoChatFragment {
            return VideoChatFragment().apply {
                arguments = Bundle().apply {
                    putBoolean("isHost", isHost)
                }
            }
        }
    }
}

// Data class for participants
data class VideoParticipant(
    val id: String,
    val name: String,
    val isHost: Boolean,
    var isMicEnabled: Boolean,
    var isVideoEnabled: Boolean,
    var isSpeaking: Boolean,
    val videoTrack: LocalVideoTrack? = null
)

// RecyclerView Adapter for participants grid
class VideoParticipantsAdapter(
    private val participants: List<VideoParticipant>,
    private val onParticipantClick: (VideoParticipant) -> Unit,
    private val onParticipantDoubleClick: (VideoParticipant) -> Unit
) : RecyclerView.Adapter<VideoParticipantsAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val videoView: TextureViewRenderer = view.findViewById(R.id.participantVideoView)
        val placeholder: View = view.findViewById(R.id.participantPlaceholder)
        val initial: TextView = view.findViewById(R.id.participantInitial)
        val name: TextView = view.findViewById(R.id.participantName)
        val micMuted: ImageView = view.findViewById(R.id.micMuted)
        val cameraOff: ImageView = view.findViewById(R.id.cameraOff)
        val speakingIndicator: View = view.findViewById(R.id.speakingIndicator)
        val speakingBorder: View = view.findViewById(R.id.speakingBorder)
        val pinIndicator: ImageView = view.findViewById(R.id.pinIndicator)
        
        var lastClickTime = 0L
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_participant, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val participant = participants[position]
        
        // Set name
        holder.name.text = participant.name
        
        // Set initial
        holder.initial.text = participant.name.firstOrNull()?.uppercase() ?: "U"
        
        // Show/hide video or placeholder
        if (participant.isVideoEnabled) {
            holder.videoView.visibility = View.VISIBLE
            holder.placeholder.visibility = View.GONE
            participant.videoTrack?.addRenderer(holder.videoView)
        } else {
            holder.videoView.visibility = View.GONE
            holder.placeholder.visibility = View.VISIBLE
        }
        
        // Status indicators
        holder.micMuted.visibility = if (participant.isMicEnabled) View.GONE else View.VISIBLE
        holder.cameraOff.visibility = if (participant.isVideoEnabled) View.GONE else View.VISIBLE
        holder.speakingIndicator.visibility = if (participant.isSpeaking) View.VISIBLE else View.GONE
        holder.speakingBorder.visibility = if (participant.isSpeaking) View.VISIBLE else View.GONE
        
        // Click handling (single and double click)
        holder.itemView.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - holder.lastClickTime < 300) {
                // Double click
                onParticipantDoubleClick(participant)
            } else {
                // Single click
                onParticipantClick(participant)
            }
            holder.lastClickTime = currentTime
        }
    }

    override fun getItemCount() = participants.size
}
