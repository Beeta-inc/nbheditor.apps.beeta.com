package com.beeta.nbheditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.sin
import kotlin.random.Random

class VoiceEqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_secondary)
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val barCount = 5
    private val barHeights = FloatArray(barCount) { 0.3f }
    private val barPhases = FloatArray(barCount) { Random.nextFloat() * 6.28f }
    private var animationTime = 0f
    private var isAnimating = false

    fun startAnimation() {
        isAnimating = true
        animationTime = 0f
        postInvalidateOnAnimation()
    }

    fun stopAnimation() {
        isAnimating = false
        barHeights.fill(0.3f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerY = height / 2f
        val barSpacing = width / (barCount + 1f)
        
        for (i in 0 until barCount) {
            val x = barSpacing * (i + 1)
            val heightFactor = if (isAnimating) {
                0.3f + 0.7f * (sin(animationTime * 3f + barPhases[i]) * 0.5f + 0.5f)
            } else {
                0.3f
            }
            val barHeight = (height * 0.6f * heightFactor).coerceAtLeast(height * 0.1f)
            
            canvas.drawLine(x, centerY - barHeight / 2, x, centerY + barHeight / 2, paint)
        }
        
        if (isAnimating) {
            animationTime += 0.08f
            postInvalidateOnAnimation()
        }
    }
}
