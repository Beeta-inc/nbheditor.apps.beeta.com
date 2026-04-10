package com.beeta.nbheditor

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
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
        return prefs.getString(PREFS_KEY_LAST_CHECK, "") != today
    }

    fun markCheckedToday(context: Context) {
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY_LAST_CHECK, today).apply()
    }

    suspend fun checkForUpdate(context: Context, force: Boolean = false) {
        try {
            if (!force && !shouldCheckToday(context)) return
            markCheckedToday(context)

            val remoteVersion = fetchText("$RAW_BASE/version.txt")?.trim() ?: return
            val changelog = fetchText("$RAW_BASE/about.txt")?.trim() ?: ""
            val downloadLink = fetchText("$RAW_BASE/link.txt")?.trim() ?: return

            val currentVersion = getCurrentVersion(context)
            if (!isNewerVersion(remoteVersion, currentVersion)) return

            val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
            if (!force && prefs.getString(PREFS_KEY_SKIP_VERSION, "") == remoteVersion) return

            val isMajor = isMajorUpdate(remoteVersion, currentVersion)
            withContext(Dispatchers.Main) {
                showUpdateDialog(context, remoteVersion, changelog, downloadLink, isMajor)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates: ${e.message}", e)
        }
    }

    private fun getCurrentVersion(context: Context) = try {
        "v${context.packageManager.getPackageInfo(context.packageName, 0).versionName}"
    } catch (_: Exception) { "v0.0.0" }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val r = parseParts(remote); val c = parseParts(current)
        for (i in 0..2) { if (r[i] > c[i]) return true; if (r[i] < c[i]) return false }
        return false
    }

    private fun isMajorUpdate(remote: String, current: String) =
        parseParts(remote)[0] > parseParts(current)[0]

    private fun parseParts(version: String): IntArray {
        val parts = version.trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
        return intArrayOf(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
    }

    private fun showUpdateDialog(context: Context, version: String, changelog: String, downloadLink: String, isMajor: Boolean) {
        val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
        val isGlass = prefs.getBoolean("glass_mode", false)

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_update, null)
        dialogView.findViewById<TextView>(R.id.updateVersionText).text = version
        dialogView.findViewById<TextView>(R.id.updateChangelogText).text =
            if (changelog.isNotBlank()) changelog else "Bug fixes and improvements"
        dialogView.findViewById<MaterialCardView>(R.id.majorUpdateBadge).visibility =
            if (isMajor) View.VISIBLE else View.GONE

        if (isGlass) {
            dialogView.findViewById<MaterialCardView>(R.id.updateContentCard)
                .setCardBackgroundColor(0xBB0D1117.toInt())
            dialogView.findViewById<TextView>(R.id.updateVersionText).setTextColor(0xFFFFFFFF.toInt())
            dialogView.findViewById<TextView>(R.id.updateChangelogText).setTextColor(0xDDFFFFFF.toInt())
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setPositiveButton("Download Now") { _, _ ->
                downloadApkWithProgress(context, downloadLink, version)
            }
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

        if (isGlass) dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        if (isGlass) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(context.resources.getColor(R.color.accent_primary, null))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xDDFFFFFF.toInt())
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(0xAAFFFFFF.toInt())
        }
    }

    private fun downloadApkWithProgress(context: Context, url: String, version: String) {
        val filename = try {
            Uri.parse(url).lastPathSegment?.takeIf { it.endsWith(".apk") } ?: "NbhEditor-$version.apk"
        } catch (_: Exception) { "NbhEditor-$version.apk" }

        // Build progress dialog
        val progressLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 48, 60, 32)
        }
        val statusText = TextView(context).apply {
            text = "⬇ Downloading $filename..."
            textSize = 14f
            setTextColor(if (context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE).getBoolean("glass_mode", false)) 0xFFFFFFFF.toInt() else 0xFF212121.toInt())
        }
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 24 }
        }
        val percentText = TextView(context).apply {
            text = "0%"
            textSize = 12f
            gravity = android.view.Gravity.END
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 8 }
        }
        progressLayout.addView(statusText)
        progressLayout.addView(progressBar)
        progressLayout.addView(percentText)

        val progressDialog = MaterialAlertDialogBuilder(context)
            .setView(progressLayout)
            .setCancelable(false)
            .create()
        progressDialog.show()

        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("NBH Editor $version")
                setDescription("Downloading update...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                setMimeType("application/vnd.android.package-archive")
                addRequestHeader("User-Agent", "NBHEditor-Updater/4.0")
                addRequestHeader("Accept", "application/octet-stream, */*")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)

            // Poll progress on main thread
            val handler = Handler(Looper.getMainLooper())
            val pollRunnable = object : Runnable {
                override fun run() {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = dm.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        cursor.close()

                        when (status) {
                            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                                if (total > 0) {
                                    val pct = (downloaded * 100 / total).toInt()
                                    progressBar.progress = pct
                                    percentText.text = "$pct%"
                                } else {
                                    progressBar.isIndeterminate = true
                                }
                                handler.postDelayed(this, 300)
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                progressBar.progress = 100
                                percentText.text = "100%"
                                statusText.text = "✓ Download complete — launching installer..."
                                handler.postDelayed({
                                    progressDialog.dismiss()
                                    promptInstall(context, filename)
                                }, 800)
                            }
                            DownloadManager.STATUS_FAILED -> {
                                progressDialog.dismiss()
                                android.widget.Toast.makeText(context, "Download failed. Try again.", android.widget.Toast.LENGTH_LONG).show()
                            }
                            else -> handler.postDelayed(this, 500)
                        }
                    } else {
                        cursor?.close()
                        handler.postDelayed(this, 500)
                    }
                }
            }
            handler.post(pollRunnable)

            // Also register broadcast as backup
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadId) {
                        ctx.unregisterReceiver(this)
                        handler.removeCallbacksAndMessages(null)
                        if (progressDialog.isShowing) {
                            statusText.text = "✓ Download complete — launching installer..."
                            progressBar.progress = 100
                            percentText.text = "100%"
                            handler.postDelayed({
                                progressDialog.dismiss()
                                promptInstall(ctx, filename)
                            }, 800)
                        }
                    }
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }

        } catch (e: Exception) {
            progressDialog.dismiss()
            Log.e(TAG, "Download failed: ${e.message}", e)
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, "Download failed. Please try manually.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun promptInstall(context: Context, filename: String) {
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), filename)
            if (!file.exists()) {
                android.widget.Toast.makeText(context, "APK not found in Downloads. Check manually.", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Install prompt failed: ${e.message}", e)
            android.widget.Toast.makeText(context, "✓ Download complete. Open from Downloads to install.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(
                Request.Builder().url(url)
                    .addHeader("User-Agent", "NBHEditor-Updater/4.0")
                    .addHeader("Cache-Control", "no-cache")
                    .build()
            ).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) { Log.e(TAG, "Fetch error: ${e.message}"); null }
    }
}
