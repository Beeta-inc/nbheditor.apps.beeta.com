package com.beeta.nbheditor

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

object GlassTextAdapter {

    private const val POLL_MS         = 400L
    private const val LIGHT_THRESHOLD = 0.5f
    private const val SAMPLE_SCALE    = 0.06f

    private val mainHandler = Handler(Looper.getMainLooper())

    // Plain class — data class can't hold lambdas reliably
    private class WatchTarget(
        val view: View,
        val onColor: (fg: Int, shadow: Int) -> Unit,
        var lastIsLight: Boolean? = null
    )

    private val targets = mutableListOf<WatchTarget>()
    private var window: Window? = null
    private var screenW = 0
    private var screenH = 0
    private var running = false
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

    fun watch(tv: TextView, bold: Boolean = true) {
        if (bold) tv.paintFlags = tv.paintFlags or Paint.FAKE_BOLD_TEXT_FLAG
        targets.add(WatchTarget(tv, { fg, sh ->
            tv.setTextColor(fg)
            tv.setShadowLayer(6f, 0f, 1.5f, sh)
        }))
    }

    fun watch(ib: ImageButton) {
        targets.add(WatchTarget(ib, { fg, _ -> ib.setColorFilter(fg) }))
    }

    fun watch(view: View, onColor: (fg: Int, shadow: Int) -> Unit) {
        targets.add(WatchTarget(view, onColor))
    }

    private fun scheduleTick() {
        if (!running) return
        mainHandler.postDelayed({ tick() }, POLL_MS)
    }

    private fun tick() {
        if (!running) return
        val bmp = captureBmp ?: run { scheduleTick(); return }
        val win = window    ?: run { scheduleTick(); return }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                PixelCopy.request(
                    win,
                    Rect(0, 0, screenW, screenH),
                    bmp,
                    PixelCopy.OnPixelCopyFinishedListener { result ->
                        if (result == PixelCopy.SUCCESS) applyColors(bmp)
                        scheduleTick()
                    },
                    mainHandler
                )
            } catch (e: Exception) {
                scheduleTick()
            }
        } else {
            try {
                val full = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
                win.decorView.draw(Canvas(full))
                val scaled = Bitmap.createScaledBitmap(full, captureW, captureH, true)
                full.recycle()
                applyColors(scaled)
                scaled.recycle()
            } catch (e: Exception) {
                // ignore
            }
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
            if (!view.isAttachedToWindow || view.width == 0) continue

            val loc = IntArray(2)
            view.getLocationOnScreen(loc)

            val left   = (loc[0] * scaleX).toInt().coerceIn(0, bmpW - 1)
            val top    = (loc[1] * scaleY).toInt().coerceIn(0, bmpH - 1)
            val right  = ((loc[0] + view.width)  * scaleX).toInt().coerceIn(left + 1, bmpW)
            val bottom = ((loc[1] + view.height) * scaleY).toInt().coerceIn(top  + 1, bmpH)

            val cx = (left + right) / 2
            val cy = (top + bottom) / 2

            val lum = listOf(
                bmp.getPixel(cx, cy),
                bmp.getPixel(left,       top),
                bmp.getPixel(right - 1,  top),
                bmp.getPixel(left,       bottom - 1),
                bmp.getPixel(right - 1,  bottom - 1)
            ).map { ColorUtils.calculateLuminance(it) }.average().toFloat()

            val isLight = lum > LIGHT_THRESHOLD
            if (target.lastIsLight == isLight) continue
            target.lastIsLight = isLight

            val fg = if (isLight) Color.BLACK else Color.WHITE
            val sh = if (isLight) 0x55FFFFFF.toInt() else Color.argb(0x99, 0, 0, 0)
            target.onColor(fg, sh)
        }
    }
}
