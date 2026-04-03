package com.beeta.nbheditor

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("NBH Editor $version")
                setDescription("Downloading update...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "NbhEditor-$version.apk")
                setMimeType("application/vnd.android.package-archive")
            }
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            android.widget.Toast.makeText(
                context, "✓ Downloading update...", android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
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
