package com.beeta.nbheditor

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        Handler(Looper.getMainLooper()).postDelayed({
            val prefs = getSharedPreferences("nbheditor_prefs", MODE_PRIVATE)
            // Glass is default on first launch — user can switch in drawer
            val glassDefault = !prefs.contains("glass_mode")
            if (glassDefault) prefs.edit().putBoolean("glass_mode", true).apply()
            val target = if (prefs.getBoolean("glass_mode", true))
                GlassMainActivity::class.java else MainActivity::class.java
            startActivity(Intent(this, target))
            finish()
        }, 1500)
    }
}
