package com.beeta.nbheditor

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View

object GlassBlurHelper {

    /**
     * Applies a real frosted-glass blur to [view] using RenderEffect (API 31+).
     * On older devices, the semi-transparent backgrounds in XML provide the glass look.
     */
    fun applyBlur(view: View, radius: Float = 20f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            )
        }
        // Below API 31: glass effect comes from semi-transparent XML backgrounds
    }

    fun clearBlur(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }
}
