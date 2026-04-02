package com.beeta.nbheditor

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        lifecycleScope.launch {
            // Run splash delay and update check in parallel
            val delayJob = launch { delay(1500) }
            val updateJob = launch(Dispatchers.IO) {
                try { AppUpdater.checkForUpdate(this@SplashActivity) } catch (_: Exception) {}
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
                finish()
            }
        }
    }
}
