package com.beeta.nbheditor

import android.os.Build
import android.view.Window
import android.view.WindowManager

object GlassBlurHelper {

    /**
     * Enables real frosted-glass blur of whatever is BEHIND the app window
     * (live wallpaper, homescreen, other apps).
     *
     * Uses two complementary APIs:
     *  - setBackgroundBlurRadius  → blurs the entire window background (API 31+)
     *  - FLAG_BLUR_BEHIND + blurBehindRadius → blurs content behind a translucent window (API 31+)
     *
     * The window background must be transparent/semi-transparent for this to show through.
     */
    fun enableWindowBlur(window: Window, blurRadius: Int = 80) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Allow blur — must be set before or after setContentView
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also { attrs ->
                attrs.blurBehindRadius = blurRadius
            }
            window.setBackgroundBlurRadius(blurRadius)
        }
    }
}
