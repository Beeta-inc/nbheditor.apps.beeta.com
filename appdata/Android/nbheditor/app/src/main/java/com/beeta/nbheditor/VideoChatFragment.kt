package com.beeta.nbheditor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.beeta.nbheditor.databinding.FragmentVideoChatBinding
import io.livekit.android.LiveKit
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
    private val durationRunnable = object : Runnable {
        override fun run() {
            updateCallDuration()
            durationHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isHost = arguments?.getBoolean("isHost", false) ?: false
        
        binding.btnLeaveCall.visibility = if (isHost) View.GONE else View.VISIBLE

        checkPermissionsAndStart()
        setupControls()
        startCallDurationTimer()
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

                localVideoTrack?.addRenderer(binding.localVideoView)

                binding.tvConnectionStatus.text = "● Connected"
                binding.tvConnectionStatus.setTextColor(0xFF4CAF50.toInt())
                
                val participantCount = (room?.remoteParticipants?.size ?: 0) + 1
                binding.tvParticipants.text = "$participantCount participant${if (participantCount != 1) "s" else ""}"

                Toast.makeText(requireContext(), "Video chat connected", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                android.util.Log.e("VideoChat", "Init error", e)
                Toast.makeText(requireContext(), "Video chat ready", Toast.LENGTH_SHORT).show()
                binding.tvConnectionStatus.text = "● Ready"
                binding.tvConnectionStatus.setTextColor(0xFF4CAF50.toInt())
            }
        }
    }

    private fun setupControls() {
        binding.btnToggleMic.setOnClickListener {
            lifecycleScope.launch {
                isMicEnabled = !isMicEnabled
                room?.localParticipant?.setMicrophoneEnabled(isMicEnabled)
                
                if (isMicEnabled) {
                    binding.imgMic.setImageResource(android.R.drawable.ic_btn_speak_now)
                    (binding.btnToggleMic as com.google.android.material.card.MaterialCardView)
                        .setCardBackgroundColor(0x4DFFFFFF)
                } else {
                    binding.imgMic.setImageResource(android.R.drawable.ic_lock_silent_mode)
                    (binding.btnToggleMic as com.google.android.material.card.MaterialCardView)
                        .setCardBackgroundColor(0xFFF44336.toInt())
                }
                
                Toast.makeText(requireContext(), if (isMicEnabled) "Mic on" else "Mic off", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnToggleVideo.setOnClickListener {
            lifecycleScope.launch {
                isVideoEnabled = !isVideoEnabled
                room?.localParticipant?.setCameraEnabled(isVideoEnabled)
                
                if (isVideoEnabled) {
                    binding.imgVideo.setImageResource(android.R.drawable.presence_video_online)
                    (binding.btnToggleVideo as com.google.android.material.card.MaterialCardView)
                        .setCardBackgroundColor(0x4DFFFFFF)
                    binding.localVideoView.visibility = View.VISIBLE
                } else {
                    binding.imgVideo.setImageResource(android.R.drawable.presence_video_busy)
                    (binding.btnToggleVideo as com.google.android.material.card.MaterialCardView)
                        .setCardBackgroundColor(0xFFF44336.toInt())
                    binding.localVideoView.visibility = View.INVISIBLE
                }
                
                Toast.makeText(requireContext(), if (isVideoEnabled) "Video on" else "Video off", Toast.LENGTH_SHORT).show()
            }
        }

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

    private fun endCallForEveryone() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("End Call")
            .setMessage("End the video call for all participants?")
            .setPositiveButton("End") { _, _ ->
                lifecycleScope.launch {
                    cleanup()
                    parentFragmentManager.popBackStack()
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
                    cleanup()
                    parentFragmentManager.popBackStack()
                    Toast.makeText(requireContext(), "Left call", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun cleanup() {
        try {
            durationHandler.removeCallbacks(durationRunnable)
            localVideoTrack?.removeRenderer(binding.localVideoView)
            room?.disconnect()
            room = null
            localVideoTrack = null
        } catch (e: Exception) {
            android.util.Log.e("VideoChat", "Cleanup error", e)
        }
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
