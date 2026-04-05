package com.beeta.nbheditor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    private val TAG = "UpdateCheckWorker"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting background update check")
            
            val remoteVersion = fetchText("$RAW_BASE/version.txt")?.trim()
            if (remoteVersion.isNullOrBlank()) {
                Log.e(TAG, "Failed to fetch remote version")
                return Result.retry()
            }
            
            val changelog = fetchText("$RAW_BASE/about.txt")?.trim() ?: ""
            val downloadLink = fetchText("$RAW_BASE/link.txt")?.trim()
            if (downloadLink.isNullOrBlank()) {
                Log.e(TAG, "Failed to fetch download link")
                return Result.retry()
            }

            val currentVersion = getCurrentVersion()
            Log.d(TAG, "Current: $currentVersion, Remote: $remoteVersion")
            
            if (!isNewerVersion(remoteVersion, currentVersion)) {
                Log.d(TAG, "No update available")
                return Result.success()
            }

            val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
            val lastNotified = prefs.getString(PREFS_NOTIFIED_VERSION, "")
            val skippedVersion = prefs.getString("updater_skip_version", "")
            
            // Don't notify if already notified or user skipped this version
            if (lastNotified == remoteVersion || skippedVersion == remoteVersion) {
                Log.d(TAG, "Already notified or skipped version $remoteVersion")
                return Result.success()
            }
            
            prefs.edit().putString(PREFS_NOTIFIED_VERSION, remoteVersion).apply()
            Log.d(TAG, "Showing notification for version $remoteVersion")

            showUpdateNotification(remoteVersion, changelog, downloadLink)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun showUpdateNotification(version: String, changelog: String, downloadLink: String) {
        createNotificationChannel()

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, SplashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(EXTRA_FORCE_UPDATE_CHECK, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isMajor = isMajorUpdate(version, getCurrentVersion())
        val title = if (isMajor) "⚠ Required: NBH Editor $version" else "✦ NBH Editor $version Available"
        val body = if (changelog.isNotBlank())
            changelog.lines().firstOrNull()?.take(80) ?: "New update available"
        else
            "Tap to download the latest version"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_save_toolbar)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (changelog.isNotBlank()) changelog.take(300) else body
            ))
            .setPriority(if (isMajor) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setColor(context.resources.getColor(R.color.accent_primary, null))
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) { }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { 
                description = "Notifications for NBH Editor updates"
                enableLights(true)
                lightColor = context.resources.getColor(R.color.accent_primary, null)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun getCurrentVersion(): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${info.versionName}"
        } catch (e: Exception) { "v0.0.0" }
    }

    private fun fetchText(url: String): String? = try {
        Log.d(TAG, "Fetching: $url")
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "NBHEditor-Updater/2.2")
            .addHeader("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (response.isSuccessful && body != null) {
                Log.d(TAG, "Fetched successfully")
                body
            } else {
                Log.e(TAG, "Fetch failed: ${response.code}")
                null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Fetch error: ${e.message}", e)
        null
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val r = parseParts(remote)
        val c = parseParts(current)
        for (i in 0..2) {
            if (r[i] > c[i]) return true
            if (r[i] < c[i]) return false
        }
        return false
    }

    private fun isMajorUpdate(remote: String, current: String) =
        parseParts(remote)[0] > parseParts(current)[0]

    private fun parseParts(version: String): IntArray {
        val clean = version.trimStart('v', 'V')
        val parts = clean.split(".").map { it.toIntOrNull() ?: 0 }
        return intArrayOf(
            parts.getOrElse(0) { 0 },
            parts.getOrElse(1) { 0 },
            parts.getOrElse(2) { 0 }
        )
    }

    companion object {
        private const val RAW_BASE = "https://raw.githubusercontent.com/Beeta-inc/Nbheditrupdater/main"
        private const val CHANNEL_ID = "nbheditor_updates"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NOTIFIED_VERSION = "updater_last_notified_version"
        const val EXTRA_FORCE_UPDATE_CHECK = "force_update_check"
        private const val WORK_NAME = "nbheditor_update_check"
        private const val WORK_NAME_FREQUENT = "nbheditor_update_check_frequent"

        fun schedule(context: Context) {
            Log.d("UpdateCheckWorker", "Scheduling update checks")
            
            // Single daily check at a reasonable time
            val dailyRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .setInitialDelay(1, TimeUnit.HOURS) // Wait 1 hour after app start
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                dailyRequest
            )
            
            Log.d("UpdateCheckWorker", "Daily update check scheduled successfully")
        }
    }
}
