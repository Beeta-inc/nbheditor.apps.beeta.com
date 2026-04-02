package com.beeta.nbheditor

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppUpdater {

    private const val RAW_BASE = "https://raw.githubusercontent.com/Beeta-inc/Nbheditrupdater/main"
    private const val PREFS_KEY_LAST_CHECK = "updater_last_check_date"

    fun shouldCheckToday(context: Context): Boolean {
        val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        return prefs.getString(PREFS_KEY_LAST_CHECK, "") != today
    }

    fun markCheckedToday(context: Context) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY_LAST_CHECK, today).apply()
    }

    /**
     * @param force skip the "already checked today" guard — used when user taps the notification
     */
    suspend fun checkForUpdate(context: Context, force: Boolean = false) {
        if (!force && !shouldCheckToday(context)) return
        markCheckedToday(context)

        val remoteVersion = fetchText("$RAW_BASE/version.txt")?.trim() ?: return
        val changelog     = fetchText("$RAW_BASE/about.txt")?.trim() ?: ""
        val downloadLink  = fetchText("$RAW_BASE/link.txt")?.trim() ?: return

        val currentVersion = getCurrentVersion(context)
        if (!isNewerVersion(remoteVersion, currentVersion)) return

        val isMajor = isMajorUpdate(remoteVersion, currentVersion)

        withContext(Dispatchers.Main) {
            showUpdateDialog(context, remoteVersion, changelog, downloadLink, isMajor)
        }
    }

    private fun getCurrentVersion(context: Context): String {
        return try {
            "v${context.packageManager.getPackageInfo(context.packageName, 0).versionName}"
        } catch (_: Exception) { "v0.0.0" }
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

    private fun showUpdateDialog(
        context: Context,
        version: String,
        changelog: String,
        downloadLink: String,
        isMajor: Boolean
    ) {
        val message = buildString {
            append("Version $version is available.\n\n")
            if (changelog.isNotBlank()) append("What's new:\n$changelog")
            if (isMajor) append("\n\n⚠ This is a major update and is required.")
        }

        AlertDialog.Builder(context)
            .setTitle("✦ Update Available")
            .setMessage(message)
            .setPositiveButton("Download") { _, _ -> downloadApk(context, downloadLink, version) }
            .apply { if (!isMajor) setNegativeButton("Later", null) else setCancelable(false) }
            .show()
    }

    private fun downloadApk(context: Context, url: String, version: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("NBH Editor $version")
                setDescription("Downloading update...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "NbhEditor-$version.apk")
                setMimeType("application/vnd.android.package-archive")
            }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            android.widget.Toast.makeText(
                context, "Downloading update to Downloads folder...", android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun fetchText(url: String): String? = try {
        URL(url).openStream().bufferedReader().use { it.readText() }
    } catch (_: Exception) { null }
}
