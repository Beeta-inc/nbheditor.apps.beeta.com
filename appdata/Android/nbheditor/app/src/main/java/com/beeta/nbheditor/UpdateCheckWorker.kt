package com.beeta.nbheditor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import java.net.URL
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val remoteVersion = fetchText("$RAW_BASE/version.txt")?.trim() ?: return Result.success()
        val changelog     = fetchText("$RAW_BASE/about.txt")?.trim() ?: ""
        val downloadLink  = fetchText("$RAW_BASE/link.txt")?.trim() ?: return Result.success()

        val currentVersion = getCurrentVersion()
        if (!isNewerVersion(remoteVersion, currentVersion)) return Result.success()

        // Don't re-notify for the same version
        val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
        val lastNotified = prefs.getString(PREFS_NOTIFIED_VERSION, "")
        if (lastNotified == remoteVersion) return Result.success()
        prefs.edit().putString(PREFS_NOTIFIED_VERSION, remoteVersion).apply()

        showUpdateNotification(remoteVersion, changelog, downloadLink)
        return Result.success()
    }

    private fun showUpdateNotification(version: String, changelog: String, downloadLink: String) {
        createNotificationChannel()

        // Tapping the notification opens the app (SplashActivity will handle the dialog)
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, SplashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(EXTRA_FORCE_UPDATE_CHECK, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isMajor = isMajorUpdate(version, getCurrentVersion())
        val title = if (isMajor) "⚠ Required Update: NBH Editor $version" else "NBH Editor $version Available"
        val body = if (changelog.isNotBlank())
            changelog.lines().first().take(80)
        else
            "A new version of NBH Editor is ready to download."

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
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted on API 33+ */ }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifies when a new version of NBH Editor is available" }
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
        URL(url).openStream().bufferedReader().use { it.readText() }
    } catch (_: Exception) { null }

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

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // don't reset timer if already scheduled
                request
            )
        }
    }
}
