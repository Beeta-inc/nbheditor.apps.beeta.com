package com.beeta.nbheditor

import android.app.Activity
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * Samples the wallpaper luminance under a View and returns adaptive
 * foreground (text/icon) and shadow colors so content is always legible
 * on any background — white half, black half, gradient, photo, etc.
 */
object GlassTextAdapter {

    // Cached scaled wallpaper bitmap (low-res is fine for luminance sampling)
    private var wallpaperCache: Bitmap? = null
    private var cacheWidth = 0
    private var cacheHeight = 0

    private const val SAMPLE_SCALE = 0.05f  // 5% of screen — fast, accurate enough

    fun warmUp(activity: Activity) {
        if (wallpaperCache != null) return
        try {
            val wm = WallpaperManager.getInstance(activity)
            val drawable = wm.drawable ?: return
            val dm = activity.resources.displayMetrics
            val w = (dm.widthPixels * SAMPLE_SCALE).toInt().coerceAtLeast(1)
            val h = (dm.heightPixels * SAMPLE_SCALE).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(Canvas(bmp))
            wallpaperCache = bmp
            cacheWidth = w
            cacheHeight = h
        } catch (_: Exception) {}
    }

    fun clear() {
        wallpaperCache?.recycle()
        wallpaperCache = null
    }

    /**
     * Returns the average luminance (0..1) of the wallpaper region
     * that sits behind [view] on screen.
     */
    fun luminanceUnder(view: View): Float {
        val bmp = wallpaperCache ?: return 0f  // assume dark if no wallpaper
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val dm = view.resources.displayMetrics
        val scaleX = cacheWidth.toFloat() / dm.widthPixels
        val scaleY = cacheHeight.toFloat() / dm.heightPixels

        val left   = (loc[0] * scaleX).toInt().coerceIn(0, cacheWidth - 1)
        val top    = (loc[1] * scaleY).toInt().coerceIn(0, cacheHeight - 1)
        val right  = ((loc[0] + view.width) * scaleX).toInt().coerceIn(left + 1, cacheWidth)
        val bottom = ((loc[1] + view.height) * scaleY).toInt().coerceIn(top + 1, cacheHeight)

        var sum = 0.0
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val pixel = bmp.getPixel(x, y)
                sum += ColorUtils.calculateLuminance(pixel)
                count++
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    /**
     * Returns Color.WHITE or Color.BLACK — whichever contrasts better
     * against the wallpaper region behind [view].
     */
    fun adaptiveColor(view: View): Int {
        return if (luminanceUnder(view) > 0.45f) Color.BLACK else Color.WHITE
    }

    /**
     * Returns the shadow color that contrasts with [adaptiveColor]:
     * white text → dark shadow, black text → light shadow.
     */
    fun shadowColor(view: View): Int {
        return if (luminanceUnder(view) > 0.45f) 0x66000000.toInt() else 0x88FFFFFF.toInt()
    }

    // ── Convenience appliers ──────────────────────────────────────────────────

    fun applyTo(tv: TextView, extraBold: Boolean = true) {
        tv.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                tv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val fg = adaptiveColor(tv)
                val sh = shadowColor(tv)
                tv.setTextColor(fg)
                tv.setShadowLayer(6f, 0f, 2f, sh)
                if (extraBold) tv.typeface = android.graphics.Typeface.create(
                    tv.typeface, android.graphics.Typeface.BOLD
                )
                tv.paintFlags = tv.paintFlags or Paint.FAKE_BOLD_TEXT_FLAG
            }
        })
    }

    fun applyTo(ib: ImageButton) {
        ib.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                ib.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val fg = adaptiveColor(ib)
                val sh = shadowColor(ib)
                ib.setColorFilter(fg)
                // Glow shadow via outline provider is not possible on ImageButton directly;
                // use a background tint ring instead
                ib.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.argb(30, Color.red(fg), Color.green(fg), Color.blue(fg))
                )
            }
        })
    }

    fun applyTo(iv: ImageView) {
        iv.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                iv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                iv.setColorFilter(adaptiveColor(iv))
            }
        })
    }
}
