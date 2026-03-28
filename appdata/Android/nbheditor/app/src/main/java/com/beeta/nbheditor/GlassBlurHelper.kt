package com.beeta.nbheditor

import android.app.Activity
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.View
import android.view.Window
import android.view.WindowManager

object GlassBlurHelper {

    /**
     * API 31+: real compositor blur behind the window (live wallpaper, homescreen, other apps).
     * API 29-30: RenderScript blur of the wallpaper bitmap set as window background.
     */
    fun enableWindowBlur(window: Window, activity: Activity, blurRadius: Int = 80) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Native window blur — works with live wallpapers in real-time
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also { it.blurBehindRadius = blurRadius }
            window.setBackgroundBlurRadius(blurRadius)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29-30: blur the wallpaper bitmap with RenderScript
            blurWallpaperFallback(window, activity, blurRadius)
        }
    }

    private fun blurWallpaperFallback(window: Window, activity: Activity, blurRadius: Int) {
        try {
            val wallpaperManager = WallpaperManager.getInstance(activity)
            val wallpaperDrawable = wallpaperManager.drawable ?: return

            // Draw wallpaper into a downscaled bitmap (faster blur + less memory)
            val scale = 0.25f
            val displayMetrics = activity.resources.displayMetrics
            val w = (displayMetrics.widthPixels * scale).toInt().coerceAtLeast(1)
            val h = (displayMetrics.heightPixels * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            wallpaperDrawable.setBounds(0, 0, w, h)
            wallpaperDrawable.draw(canvas)

            // RenderScript blur
            val blurred = blurBitmap(activity, bitmap, blurRadius.toFloat().coerceIn(1f, 25f))
            bitmap.recycle()

            // Scale back up to fill screen
            val fullSize = Bitmap.createScaledBitmap(blurred, displayMetrics.widthPixels, displayMetrics.heightPixels, true)
            blurred.recycle()

            window.setBackgroundDrawable(BitmapDrawable(activity.resources, fullSize))
        } catch (e: Exception) {
            // Permission denied or wallpaper unavailable — leave transparent
        }
    }

    private fun blurBitmap(activity: Activity, src: Bitmap, radius: Float): Bitmap {
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val rs = RenderScript.create(activity)
        val input = Allocation.createFromBitmap(rs, src)
        val out = Allocation.createFromBitmap(rs, output)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        script.setRadius(radius)
        script.setInput(input)
        script.forEach(out)
        out.copyTo(output)
        rs.destroy()
        return output
    }

    /**
     * Apply RenderEffect blur to a specific View (API 31+ only).
     * Used for blurring content panels on top of the window.
     */
    fun applyViewBlur(view: View, radius: Float = 20f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)
            )
        }
    }

    fun clearViewBlur(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }
}
