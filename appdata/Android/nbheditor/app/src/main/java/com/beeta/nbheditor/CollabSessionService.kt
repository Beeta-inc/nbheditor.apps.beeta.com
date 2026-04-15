package com.beeta.nbheditor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CollabSessionService : Service() {

    companion object {
        const val CHANNEL_ID = "collab_session_channel"
        const val NOTIF_ID = 7001
        const val ACTION_LEAVE = "com.beeta.nbheditor.ACTION_LEAVE_SESSION"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SESSION_START = "session_start"
        const val PREF_SESSION_ID = "active_collab_session_id"
        const val PREF_SESSION_START = "active_collab_session_start"

        fun start(context: Context, sessionId: String) {
            val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString(PREF_SESSION_ID, sessionId)
                .putLong(PREF_SESSION_START, System.currentTimeMillis())
                .apply()
            val intent = Intent(context, CollabSessionService::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_SESSION_START, System.currentTimeMillis())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
            prefs.edit().remove(PREF_SESSION_ID).remove(PREF_SESSION_START).apply()
            context.stopService(Intent(context, CollabSessionService::class.java))
        }

        fun getActiveSessionId(context: Context): String? {
            val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
            return prefs.getString(PREF_SESSION_ID, null)
        }
    }

    private var sessionId = ""
    private var sessionStart = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) {
            // User tapped Leave in notification
            serviceScope.launch {
                CollaborativeSessionManager.leaveSession()
            }
            stop(this)
            return START_NOT_STICKY
        }
        sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: ""
        sessionStart = intent?.getLongExtra(EXTRA_SESSION_START, System.currentTimeMillis()) ?: System.currentTimeMillis()
        
        // Ensure notification channel exists before starting foreground
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("00:00"))
        handler.post(timerRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(timerRunnable)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotification() {
        val elapsed = System.currentTimeMillis() - sessionStart
        val secs = (elapsed / 1000) % 60
        val mins = (elapsed / 60000) % 60
        val hrs = elapsed / 3600000
        val timer = if (hrs > 0) "%02d:%02d:%02d".format(hrs, mins, secs)
                    else "%02d:%02d".format(mins, secs)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(timer))
    }

    private fun buildNotification(timer: String): Notification {
        // Tap notification → open app and rejoin
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Leave button in notification
        val leaveIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CollabSessionService::class.java).apply { action = ACTION_LEAVE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud_sync)
            .setContentTitle("🔗 Collaborative Session Active")
            .setContentText("Session: $sessionId  •  $timer")
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Leave", leaveIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Collaborative Session",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows active collaborative session timer"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
                enableLights(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
