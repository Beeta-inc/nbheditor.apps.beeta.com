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
import kotlinx.coroutines.launch
import io.getstream.webrtc.android.ktx.*
import org.webrtc.*

class VideoChatFragment : Fragment() {

    private var _binding: FragmentVideoChatBinding? = null
    private val binding get() = _binding!!

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    private var isMicEnabled = true
    private var isVideoEnabled = true
    private var isFrontCamera = true
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
        
        // Show/hide leave button based on host status
        binding.btnLeaveCall.visibility = if (isHost) View.GONE else View.VISIBLE

        checkPermissionsAndStart()
        setupControls()
        startCallDurationTimer()
        updateParticipantCount()
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
            initializeWebRTC()
        } else {
            requestPermissions(notGranted.toTypedArray(), 301)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 301) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initializeWebRTC()
            } else {
                Toast.makeText(requireContext(), "Camera and microphone permissions required", Toast.LENGTH_LONG).show()
                parentFragmentManager.popBackStack()
            }
        }
    }

    private fun initializeWebRTC() {
        lifecycleScope.launch {
            try {
                // Initialize PeerConnectionFactory
                val options = PeerConnectionFactory.InitializationOptions.builder(requireContext())
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)

                val encoderFactory = DefaultVideoEncoderFactory(
                    EglBase.create().eglBaseContext,
                    true,
                    true
                )
                val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)

                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory()

                // Initialize video views
                binding.localVideoView.init(EglBase.create().eglBaseContext, null)
                binding.remoteVideoView.init(EglBase.create().eglBaseContext, null)

                binding.localVideoView.setMirror(true)
                binding.localVideoView.setEnableHardwareScaler(true)
                binding.remoteVideoView.setEnableHardwareScaler(true)

                // Create local media tracks
                createLocalMediaTracks()

                // Create peer connection
                createPeerConnection()

                binding.tvConnectionStatus.text = "● Connected"
                binding.tvConnectionStatus.setTextColor(0xFF4CAF50.toInt())

            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to initialize video: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.tvConnectionStatus.text = "● Connection failed"
                binding.tvConnectionStatus.setTextColor(0xFFF44336.toInt())
            }
        }
    }

    private fun createLocalMediaTracks() {
        // Create video track
        val videoCapturer = createCameraCapturer()
        this.videoCapturer = videoCapturer

        videoSource = peerConnectionFactory?.createVideoSource(videoCapturer?.isScreencast ?: false)
        videoCapturer?.initialize(
            SurfaceTextureHelper.create("CaptureThread", EglBase.create().eglBaseContext),
            requireContext(),
            videoSource?.capturerObserver
        )
        videoCapturer?.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory?.createVideoTrack("local_video", videoSource)
        localVideoTrack?.addSink(binding.localVideoView)

        // Create audio track
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("local_audio", audioSource)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(requireContext())
        val deviceNames = enumerator.deviceNames

        // Try front camera first
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    isFrontCamera = true
                    return capturer
                }
            }
        }

        // Fallback to back camera
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    isFrontCamera = false
                    return capturer
                }
            }
        }

        return null
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    // Send ICE candidate to remote peer via Firebase
                    candidate?.let { sendIceCandidate(it) }
                }

                override fun onAddStream(stream: MediaStream?) {
                    stream?.videoTracks?.firstOrNull()?.addSink(binding.remoteVideoView)
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    lifecycleScope.launch {
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED -> {
                                binding.tvConnectionStatus.text = "● Connected"
                                binding.tvConnectionStatus.setTextColor(0xFF4CAF50.toInt())
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                binding.tvConnectionStatus.text = "● Disconnected"
                                binding.tvConnectionStatus.setTextColor(0xFFFFC107.toInt())
                            }
                            PeerConnection.IceConnectionState.FAILED -> {
                                binding.tvConnectionStatus.text = "● Connection failed"
                                binding.tvConnectionStatus.setTextColor(0xFFF44336.toInt())
                            }
                            else -> {}
                        }
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )

        // Add local tracks to peer connection
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("stream")) }
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("stream")) }
    }

    private fun setupControls() {
        // Toggle microphone
        binding.btnToggleMic.setOnClickListener {
            isMicEnabled = !isMicEnabled
            localAudioTrack?.setEnabled(isMicEnabled)
            
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

        // Toggle video
        binding.btnToggleVideo.setOnClickListener {
            isVideoEnabled = !isVideoEnabled
            localVideoTrack?.setEnabled(isVideoEnabled)
            
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

        // Rotate camera
        binding.btnRotateCamera.setOnClickListener {
            switchCamera()
        }

        // End call (host)
        binding.btnEndCall.setOnClickListener {
            if (isHost) {
                // Host ends call for everyone
                endCallForEveryone()
            } else {
                // Non-host just leaves
                leaveCall()
            }
        }

        // Leave call (non-host)
        binding.btnLeaveCall.setOnClickListener {
            leaveCall()
        }
    }

    private fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                this@VideoChatFragment.isFrontCamera = isFrontCamera
                binding.localVideoView.setMirror(isFrontCamera)
                Toast.makeText(
                    requireContext(),
                    if (isFrontCamera) "Front camera" else "Back camera",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Toast.makeText(requireContext(), "Failed to switch camera", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        // TODO: Send ICE candidate to Firebase for signaling
        lifecycleScope.launch {
            try {
                val sessionId = CollaborativeSessionManager.getCurrentSessionId() ?: return@launch
                // Store ICE candidate in Firebase for other peers to receive
            } catch (e: Exception) {
                android.util.Log.e("VideoChat", "Failed to send ICE candidate", e)
            }
        }
    }

    private fun endCallForEveryone() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("End Call")
            .setMessage("End the video call for all participants?")
            .setPositiveButton("End") { _, _ ->
                lifecycleScope.launch {
                    // Notify all participants via Firebase
                    val sessionId = CollaborativeSessionManager.getCurrentSessionId()
                    // TODO: Send end call signal to Firebase
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
                    // Notify others that you left
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

    private fun updateParticipantCount() {
        lifecycleScope.launch {
            try {
                val sessionId = CollaborativeSessionManager.getCurrentSessionId() ?: return@launch
                CollaborativeSessionManager.observeUsers(sessionId).collect { users ->
                    val count = users.size
                    binding.tvParticipants.text = "$count participant${if (count != 1) "s" else ""}"
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoChat", "Failed to update participant count", e)
            }
        }
    }

    private fun cleanup() {
        durationHandler.removeCallbacks(durationRunnable)
        
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()
        
        peerConnection?.close()
        peerConnection?.dispose()
        
        binding.localVideoView.release()
        binding.remoteVideoView.release()
        
        peerConnectionFactory?.dispose()
    }

    override fun onDestroyView() {
        super.onDestroyView()
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
