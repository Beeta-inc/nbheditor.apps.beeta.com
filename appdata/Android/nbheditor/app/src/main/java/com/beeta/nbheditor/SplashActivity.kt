package com.beeta.nbheditor

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Force black window background before setContentView to avoid any flash
        window.setBackgroundDrawableResource(android.R.color.black)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        // Clear light status-bar flag so icons are white on black
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility and
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // ── Entrance animations ──────────────────────────────────────────────
        val logo    = findViewById<View>(R.id.splash_logo)
        val title   = findViewById<View>(R.id.splash_title)
        val tagline = findViewById<View>(R.id.splash_tagline)
        val version = findViewById<View>(R.id.splash_version)

        // Logo → 0 ms, Title → 100 ms, Tagline → 200 ms, Version → 300 ms
        // Each fades in over 200 ms.
        logo.animate().alpha(1f).setDuration(200).setStartDelay(0).start()
        title.animate().alpha(1f).setDuration(200).setStartDelay(100).start()
        tagline.animate().alpha(1f).setDuration(200).setStartDelay(200).start()
        version.animate().alpha(1f).setDuration(200).setStartDelay(300).start()

        // ── Existing launch logic (preserved) ────────────────────────────────
        // Schedule the daily background update check (safe to call every launch — KEEP policy)
        UpdateCheckWorker.schedule(this)

        val forceCheck = intent.getBooleanExtra(UpdateCheckWorker.EXTRA_FORCE_UPDATE_CHECK, false)

        lifecycleScope.launch {
            // Run in parallel: honour minimum display time (1500 ms) AND update check.
            // Animations finish at ~500 ms; the 1500 ms floor keeps the splash visible long
            // enough for the full fade-in + a brief pause before the main screen appears.
            val delayJob = launch { delay(1500) }
            val updateJob = launch(Dispatchers.IO) {
                try {
                    if (forceCheck) {
                        // User tapped the update notification — always show dialog
                        AppUpdater.checkForUpdate(this@SplashActivity, force = true)
                    } else {
                        AppUpdater.checkForUpdate(this@SplashActivity)
                    }
                } catch (_: Exception) {}
            }
            delayJob.join()
            updateJob.join()

            val prefs = getSharedPreferences("nbheditor_prefs", MODE_PRIVATE)
            val glassDefault = !prefs.contains("glass_mode")
            if (glassDefault) prefs.edit().putBoolean("glass_mode", true).apply()
            val target = if (prefs.getBoolean("glass_mode", true))
                GlassMainActivity::class.java else MainActivity::class.java

            withContext(Dispatchers.Main) {
                startActivity(Intent(this@SplashActivity, target))
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }
    }
}
