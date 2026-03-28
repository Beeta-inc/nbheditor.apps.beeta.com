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
    fun enableWindowBlur(window: Window, activity: Activity, blurRadius: Int = 200) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: native compositor blur — fast, runs on GPU, safe on main thread
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also { it.blurBehindRadius = blurRadius.coerceAtMost(150) }
            window.setBackgroundBlurRadius(blurRadius)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29-30: RenderScript is CPU-heavy — run on background thread
            Thread {
                blurWallpaperFallback(window, activity, blurRadius)
            }.start()
        }
    }

    private fun blurWallpaperFallback(window: Window, activity: Activity, blurRadius: Int) {
        try {
            val wallpaperManager = WallpaperManager.getInstance(activity)
            val wallpaperDrawable = wallpaperManager.drawable ?: return

            val scale = 0.25f
            val displayMetrics = activity.resources.displayMetrics
            val w = (displayMetrics.widthPixels * scale).toInt().coerceAtLeast(1)
            val h = (displayMetrics.heightPixels * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            wallpaperDrawable.setBounds(0, 0, w, h)
            wallpaperDrawable.draw(canvas)

            val blurred = blurBitmap(activity, bitmap, 25f)
            bitmap.recycle()

            val fullSize = Bitmap.createScaledBitmap(blurred, displayMetrics.widthPixels, displayMetrics.heightPixels, true)
            blurred.recycle()

            // Must set drawable back on main thread
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                window.setBackgroundDrawable(BitmapDrawable(activity.resources, fullSize))
            }
        } catch (e: Exception) {
            // Permission denied or wallpaper unavailable
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
