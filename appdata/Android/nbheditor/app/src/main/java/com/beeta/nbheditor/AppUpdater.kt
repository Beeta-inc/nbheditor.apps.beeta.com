package com.beeta.nbheditor

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val RAW_BASE = "https://raw.githubusercontent.com/Beeta-inc/Nbheditrupdater/main"
    private const val PREFS_KEY_LAST_CHECK = "updater_last_check_date"
    private const val PREFS_KEY_SKIP_VERSION = "updater_skip_version"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun shouldCheckToday(context: Context): Boolean {
        val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val lastCheck = prefs.getString(PREFS_KEY_LAST_CHECK, "")
        return lastCheck != today
    }

    fun markCheckedToday(context: Context) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY_LAST_CHECK, today).apply()
    }

    suspend fun checkForUpdate(context: Context, force: Boolean = false) {
        try {
            if (!force && !shouldCheckToday(context)) {
                Log.d(TAG, "Already checked today, skipping")
                return
            }
            
            Log.d(TAG, "Checking for updates...")
            markCheckedToday(context)

            val remoteVersion = fetchText("$RAW_BASE/version.txt")?.trim()
            if (remoteVersion.isNullOrBlank()) {
                Log.e(TAG, "Failed to fetch remote version")
                return
            }
            
            val changelog = fetchText("$RAW_BASE/about.txt")?.trim() ?: ""
            val downloadLink = fetchText("$RAW_BASE/link.txt")?.trim()
            if (downloadLink.isNullOrBlank()) {
                Log.e(TAG, "Failed to fetch download link")
                return
            }

            val currentVersion = getCurrentVersion(context)
            Log.d(TAG, "Current: $currentVersion, Remote: $remoteVersion")
            
            if (!isNewerVersion(remoteVersion, currentVersion)) {
                Log.d(TAG, "No update available")
                return
            }
            
            // Check if user skipped this version
            val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
            val skippedVersion = prefs.getString(PREFS_KEY_SKIP_VERSION, "")
            if (!force && skippedVersion == remoteVersion) {
                Log.d(TAG, "User skipped version $remoteVersion")
                return
            }

            val isMajor = isMajorUpdate(remoteVersion, currentVersion)
            Log.d(TAG, "Update available: $remoteVersion (Major: $isMajor)")

            withContext(Dispatchers.Main) {
                showUpdateDialog(context, remoteVersion, changelog, downloadLink, isMajor)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates: ${e.message}", e)
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
        val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
        val isGlass = prefs.getBoolean("glass_mode", false)
        
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_update, null)
        
        val versionText = dialogView.findViewById<TextView>(R.id.updateVersionText)
        val changelogText = dialogView.findViewById<TextView>(R.id.updateChangelogText)
        val majorBadge = dialogView.findViewById<MaterialCardView>(R.id.majorUpdateBadge)
        val contentCard = dialogView.findViewById<MaterialCardView>(R.id.updateContentCard)
        
        versionText.text = version
        changelogText.text = if (changelog.isNotBlank()) changelog else "Bug fixes and improvements"
        majorBadge.visibility = if (isMajor) android.view.View.VISIBLE else android.view.View.GONE
        
        if (isGlass) {
            contentCard.setCardBackgroundColor(0xBB0D1117.toInt())
            contentCard.strokeColor = 0x77FFFFFF
            versionText.setTextColor(0xFFFFFFFF.toInt())
            changelogText.setTextColor(0xDDFFFFFF.toInt())
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setPositiveButton("Download Now") { _, _ -> downloadApk(context, downloadLink, version) }
            .apply { 
                if (!isMajor) {
                    setNegativeButton("Later", null)
                    setNeutralButton("Skip") { _, _ ->
                        prefs.edit().putString(PREFS_KEY_SKIP_VERSION, version).apply()
                        android.widget.Toast.makeText(context, "Update skipped", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    setCancelable(false)
                }
            }
            .create()
        
        if (isGlass) {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
        
        dialog.show()
        
        if (isGlass) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(context.resources.getColor(R.color.accent_primary, null))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xDDFFFFFF.toInt())
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(0xAAFFFFFF.toInt())
        }
    }

    private fun downloadApk(context: Context, url: String, version: String) {
        try {
            Log.d(TAG, "Starting download from: $url")
            
            // Extract filename from URL or use default
            val filename = try {
                val lastSegment = Uri.parse(url).lastPathSegment
                if (lastSegment != null && lastSegment.endsWith(".apk")) {
                    lastSegment
                } else {
                    "NbhEditor-$version.apk"
                }
            } catch (_: Exception) {
                "NbhEditor-$version.apk"
            }
            
            Log.d(TAG, "Download filename: $filename")
            
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("NBH Editor $version")
                setDescription("Downloading update...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                setMimeType("application/vnd.android.package-archive")
                
                // Add headers for GitHub releases and direct downloads
                addRequestHeader("User-Agent", "NBHEditor-Updater/2.2")
                addRequestHeader("Accept", "application/octet-stream, application/vnd.android.package-archive, */*")
                
                // Allow download over metered networks
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            }
            
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)
            
            Log.d(TAG, "Download enqueued with ID: $downloadId")
            
            // Save download ID for tracking
            context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_download_id", downloadId)
                .putString("last_download_version", version)
                .putString("last_download_filename", filename)
                .apply()
            
            // Register broadcast receiver for download completion
            registerDownloadReceiver(context, downloadId, filename)
            
            android.widget.Toast.makeText(
                context, "✓ Downloading $filename...", android.widget.Toast.LENGTH_LONG
            ).show()
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            // Fallback: open in browser
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                android.widget.Toast.makeText(
                    context, "Opening download in browser...", android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (browserError: Exception) {
                Log.e(TAG, "Browser fallback failed: ${browserError.message}", browserError)
                android.widget.Toast.makeText(
                    context, "Failed to download. Please check the link.", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun registerDownloadReceiver(context: Context, downloadId: Long, filename: String) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    ctx.unregisterReceiver(this)
                    promptInstall(ctx, filename)
                }
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }
    
    private fun promptInstall(context: Context, filename: String) {
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), filename)
            
            if (!file.exists()) {
                android.widget.Toast.makeText(
                    context, "Download completed. Check Downloads folder.", android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }
            
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }
            
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(installIntent)
            
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context, "✓ Download complete. Open from Downloads to install.", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching: $url")
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "NBHEditor-Updater/2.2")
                .addHeader("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    Log.d(TAG, "Fetched successfully: ${body.take(100)}")
                    body
                } else {
                    Log.e(TAG, "Fetch failed: ${response.code} ${response.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch error: ${e.message}", e)
            null
        }
    }
}
