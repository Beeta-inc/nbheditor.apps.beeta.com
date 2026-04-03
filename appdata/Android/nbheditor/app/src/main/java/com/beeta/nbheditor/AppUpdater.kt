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

    private const val RAW_BASE = "https://raw.githubusercontent.com/Beeta-inc/Nbheditrupdater/main"
    private const val PREFS_KEY_LAST_CHECK = "updater_last_check_date"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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
        val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
        val isGlass = prefs.getBoolean("glass_mode", false)
        
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_update, null)
        
        val versionText = dialogView.findViewById<TextView>(R.id.updateVersionText)
        val changelogText = dialogView.findViewById<TextView>(R.id.updateChangelogText)
        val majorBadge = dialogView.findViewById<MaterialCardView>(R.id.majorUpdateBadge)
        val contentCard = dialogView.findViewById<MaterialCardView>(R.id.updateContentCard)
        
        versionText.text = "Version $version"
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
                if (!isMajor) setNegativeButton("Later", null) 
                else setCancelable(false) 
            }
            .create()
        
        if (isGlass) {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        }
        
        dialog.show()
        
        if (isGlass) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(context.resources.getColor(R.color.accent_primary, null))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xDDFFFFFF.toInt())
        }
    }

    private fun downloadApk(context: Context, url: String, version: String) {
        try {
            // Extract filename from URL or use default
            val filename = try {
                Uri.parse(url).lastPathSegment ?: "NbhEditor-$version.apk"
            } catch (_: Exception) {
                "NbhEditor-$version.apk"
            }
            
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("NBH Editor $version")
                setDescription("Downloading update...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                setMimeType("application/vnd.android.package-archive")
                
                // Add headers for GitHub releases
                addRequestHeader("User-Agent", "NBHEditor-Updater")
                addRequestHeader("Accept", "application/octet-stream")
                
                // Allow download over metered networks
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
            }
            
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)
            
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
            // Fallback: open in browser
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                android.widget.Toast.makeText(
                    context, "Opening download in browser...", android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (_: Exception) {
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
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: Exception) { null }
    }
}
