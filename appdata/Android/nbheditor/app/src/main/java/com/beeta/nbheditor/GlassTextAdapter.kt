package com.beeta.nbheditor

import android.animation.ArgbEvaluator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * Real-time adaptive text/icon color for glass mode.
 *
 * Every POLL_MS milliseconds it captures the actual rendered screen pixels
 * (what the user literally sees — blur, wallpaper, orbs, everything) under
 * each registered view, computes luminance, and smoothly animates the
 * text/icon color between black and white.
 *
 * API 26+: PixelCopy — captures the compositor output including blur effects.
 * API < 26: drawToBitmap on the decor view (no blur, but still correct for
 *           the background gradient/orb colors).
 */
object GlassTextAdapter {

    private const val POLL_MS = 250L          // sample every 250 ms
    private const val LIGHT_THRESHOLD = 0.42f // luminance above this → use black text
    private const val SAMPLE_SCALE = 0.08f    // capture at 8% resolution — fast

    private val mainHandler = Handler(Looper.getMainLooper())
    private val argbEval = ArgbEvaluator()

    // Each registered target: the view + its current displayed color
    data class Target(
        val view: View,
        val onColor: (fg: Int, shadow: Int) -> Unit,
        var currentFg: Int = Color.WHITE
    )

    private val targets = mutableListOf<Target>()
    private var window: Window? = null
    private var screenW = 0
    private var screenH = 0
    private var running = false

    // Shared low-res screen capture bitmap (reused every tick)
    private var captureBmp: Bitmap? = null
    private var captureW = 0
    private var captureH = 0

    fun start(activity: Activity) {
        window = activity.window
        val dm = activity.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        captureW = (screenW * SAMPLE_SCALE).toInt().coerceAtLeast(1)
        captureH = (screenH * SAMPLE_SCALE).toInt().coerceAtLeast(1)
        captureBmp = Bitmap.createBitmap(captureW, captureH, Bitmap.Config.ARGB_8888)
        running = true
        scheduleTick()
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        captureBmp?.recycle()
        captureBmp = null
        targets.clear()
        window = null
    }

    /** Register a TextView for real-time adaptive color. */
    fun watch(tv: TextView, bold: Boolean = true) {
        if (bold) {
            tv.typeface = android.graphics.Typeface.create(tv.typeface, android.graphics.Typeface.BOLD)
            tv.paintFlags = tv.paintFlags or Paint.FAKE_BOLD_TEXT_FLAG
        }
        targets.add(Target(tv, { fg, sh ->
            tv.setTextColor(fg)
            tv.setShadowLayer(8f, 0f, 2f, sh)
        }, tv.currentTextColor))
    }

    /** Register an ImageButton for real-time adaptive tint. */
    fun watch(ib: ImageButton) {
        targets.add(Target(ib, { fg, _ ->
            ib.setColorFilter(fg)
        }, Color.WHITE))
    }

    /** Register an arbitrary view with a custom color callback. */
    fun watch(view: View, onColor: (fg: Int, shadow: Int) -> Unit) {
        targets.add(Target(view, onColor, Color.WHITE))
    }

    private fun scheduleTick() {
        if (!running) return
        mainHandler.postDelayed({ tick() }, POLL_MS)
    }

    private fun tick() {
        if (!running) return
        val bmp = captureBmp ?: return scheduleTick()
        val win = window ?: return scheduleTick()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // PixelCopy captures the real compositor output (blur included)
            val srcRect = Rect(0, 0, screenW, screenH)
            try {
                PixelCopy.request(win, srcRect, bmp, { result ->
                    if (result == PixelCopy.SUCCESS) applyColors(bmp)
                    scheduleTick()
                }, mainHandler)
            } catch (_: Exception) {
                scheduleTick()
            }
        } else {
            // Fallback: draw decor view to bitmap
            try {
                val decor = win.decorView
                val full = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(full)
                decor.draw(canvas)
                // Scale down
                val scaled = Bitmap.createScaledBitmap(full, captureW, captureH, true)
                full.recycle()
                applyColors(scaled)
                scaled.recycle()
            } catch (_: Exception) {}
            scheduleTick()
        }
    }

    private fun applyColors(bmp: Bitmap) {
        val bmpW = bmp.width
        val bmpH = bmp.height
        val scaleX = bmpW.toFloat() / screenW
        val scaleY = bmpH.toFloat() / screenH

        for (target in targets) {
            val view = target.view
            if (!view.isAttachedToWindow) continue

            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val vw = view.width.coerceAtLeast(1)
            val vh = view.height.coerceAtLeast(1)

            val left   = (loc[0] * scaleX).toInt().coerceIn(0, bmpW - 1)
            val top    = (loc[1] * scaleY).toInt().coerceIn(0, bmpH - 1)
            val right  = ((loc[0] + vw) * scaleX).toInt().coerceIn(left + 1, bmpW)
            val bottom = ((loc[1] + vh) * scaleY).toInt().coerceIn(top + 1, bmpH)

            // Sample a grid of pixels (max 25 samples for speed)
            val stepX = ((right - left) / 5).coerceAtLeast(1)
            val stepY = ((bottom - top) / 5).coerceAtLeast(1)
            var lumSum = 0.0
            var count = 0
            var x = left
            while (x < right) {
                var y = top
                while (y < bottom) {
                    lumSum += ColorUtils.calculateLuminance(bmp.getPixel(x, y))
                    count++
                    y += stepY
                }
                x += stepX
            }
            val lum = if (count == 0) 0f else (lumSum / count).toFloat()

            val targetFg = if (lum > LIGHT_THRESHOLD) Color.BLACK else Color.WHITE
            val targetSh = if (lum > LIGHT_THRESHOLD) 0x88000000.toInt() else 0xAAFFFFFF.toInt()

            // Smooth transition: interpolate 30% toward target each tick
            val newFg = argbEval.evaluate(0.3f, target.currentFg, targetFg) as Int
            target.currentFg = newFg
            target.onColor(newFg, targetSh)
        }
    }
}
