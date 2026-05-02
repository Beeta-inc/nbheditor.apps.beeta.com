package com.beeta.nbheditor

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import io.livekit.android.renderer.TextureViewRenderer

class VideoMiniPlayerService : Service() {

    private lateinit var windowManager: WindowManager
    private var miniPlayerView: View? = null
    private var overlayView: View? = null
    
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
    private val overlayHandler = Handler(Looper.getMainLooper())
    private var overlayRunnable: Runnable? = null
    
    companion object {
        var isMicEnabled = true
        var isVideoEnabled = true
        var localUserName = "You"
        var remoteUserName = "User"
        var pinnedUserId: String? = null
        var activeSpeakerId: String? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createMiniPlayer()
    }

    private fun createMiniPlayer() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        miniPlayerView = LayoutInflater.from(this).inflate(R.layout.mini_player_video, null)
        
        setupMiniPlayerViews()
        setupTouchListener(params)
        
        windowManager.addView(miniPlayerView, params)
    }

    private fun setupMiniPlayerViews() {
        val localVideo = miniPlayerView?.findViewById<TextureViewRenderer>(R.id.miniLocalVideo)
        val remoteVideo = miniPlayerView?.findViewById<TextureViewRenderer>(R.id.miniRemoteVideo)
        val localName = miniPlayerView?.findViewById<TextView>(R.id.miniLocalName)
        val remoteName = miniPlayerView?.findViewById<TextView>(R.id.miniRemoteName)
        val localInitial = miniPlayerView?.findViewById<TextView>(R.id.miniLocalInitial)
        val remoteInitial = miniPlayerView?.findViewById<TextView>(R.id.miniRemoteInitial)
        val localPlaceholder = miniPlayerView?.findViewById<View>(R.id.miniLocalPlaceholder)
        val remotePlaceholder = miniPlayerView?.findViewById<View>(R.id.miniRemotePlaceholder)
        
        localName?.text = localUserName
        remoteName?.text = remoteUserName
        localInitial?.text = localUserName.firstOrNull()?.uppercase() ?: "Y"
        remoteInitial?.text = remoteUserName.firstOrNull()?.uppercase() ?: "U"
        
        // Show/hide video based on camera state
        if (isVideoEnabled) {
            localVideo?.visibility = View.VISIBLE
            localPlaceholder?.visibility = View.GONE
        } else {
            localVideo?.visibility = View.GONE
            localPlaceholder?.visibility = View.VISIBLE
        }
        
        miniPlayerView?.setOnClickListener {
            showOverlay()
        }
    }

    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        miniPlayerView?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(miniPlayerView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = Math.abs(event.rawX - initialTouchX)
                    val deltaY = Math.abs(event.rawY - initialTouchY)
                    if (deltaX < 10 && deltaY < 10) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun showOverlay() {
        if (overlayView != null) {
            hideOverlay()
            return
        }
        
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        overlayView = LayoutInflater.from(this).inflate(R.layout.mini_player_overlay, null)
        
        setupOverlayControls()
        
        windowManager.addView(overlayView, params)
        
        // Auto-hide after 4 seconds
        overlayRunnable = Runnable { hideOverlay() }
        overlayHandler.postDelayed(overlayRunnable!!, 4000)
    }

    private fun setupOverlayControls() {
        overlayView?.findViewById<ImageView>(R.id.btnMaximize)?.setOnClickListener {
            maximizePlayer()
        }
        
        overlayView?.findViewById<androidx.cardview.widget.CardView>(R.id.btnLeaveOverlay)?.setOnClickListener {
            leaveCall()
        }
        
        val btnMicOverlay = overlayView?.findViewById<androidx.cardview.widget.CardView>(R.id.btnMicOverlay)
        val imgMicOverlay = overlayView?.findViewById<ImageView>(R.id.imgMicOverlay)
        btnMicOverlay?.setOnClickListener {
            isMicEnabled = !isMicEnabled
            if (isMicEnabled) {
                imgMicOverlay?.setImageResource(android.R.drawable.ic_btn_speak_now)
            } else {
                imgMicOverlay?.setImageResource(android.R.drawable.ic_lock_silent_mode)
            }
            sendBroadcast(Intent("VIDEO_CHAT_MIC_TOGGLE"))
        }
        
        val btnVideoOverlay = overlayView?.findViewById<androidx.cardview.widget.CardView>(R.id.btnVideoOverlay)
        val imgVideoOverlay = overlayView?.findViewById<ImageView>(R.id.imgVideoOverlay)
        btnVideoOverlay?.setOnClickListener {
            isVideoEnabled = !isVideoEnabled
            if (isVideoEnabled) {
                imgVideoOverlay?.setImageResource(android.R.drawable.presence_video_online)
            } else {
                imgVideoOverlay?.setImageResource(android.R.drawable.presence_video_busy)
            }
            setupMiniPlayerViews()
            sendBroadcast(Intent("VIDEO_CHAT_VIDEO_TOGGLE"))
        }
        
        overlayView?.setOnClickListener {
            hideOverlay()
        }
    }

    private fun hideOverlay() {
        overlayRunnable?.let { overlayHandler.removeCallbacks(it) }
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    private fun maximizePlayer() {
        sendBroadcast(Intent("VIDEO_CHAT_MAXIMIZE"))
        stopSelf()
    }

    private fun leaveCall() {
        sendBroadcast(Intent("VIDEO_CHAT_LEAVE"))
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        miniPlayerView?.let { windowManager.removeView(it) }
        overlayView?.let { windowManager.removeView(it) }
    }
}
