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
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }

    private val barCount = 7
    private val barHeights = FloatArray(barCount) { 0.3f }
    private val barPhases = FloatArray(barCount) { Random.nextFloat() * 6.28f }
    private val barSpeeds = FloatArray(barCount) { 2.5f + Random.nextFloat() * 1.5f }
    private var animationTime = 0f
    private var isAnimating = false
    private var intensity = 1.0f // 0.0 to 1.0

    fun startAnimation() {
        isAnimating = true
        animationTime = 0f
        intensity = 1.0f
        postInvalidateOnAnimation()
    }

    fun stopAnimation() {
        isAnimating = false
        intensity = 0.3f
        barHeights.fill(0.3f)
        invalidate()
    }
    
    fun setIntensity(level: Float) {
        intensity = level.coerceIn(0.3f, 1.0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerY = height / 2f
        val barSpacing = width / (barCount + 1f)
        
        for (i in 0 until barCount) {
            val x = barSpacing * (i + 1)
            val heightFactor = if (isAnimating) {
                val wave = sin(animationTime * barSpeeds[i] + barPhases[i]) * 0.5f + 0.5f
                0.2f + (0.8f * wave * intensity)
            } else {
                0.3f
            }
            
            val barHeight = (height * 0.7f * heightFactor).coerceAtLeast(height * 0.15f)
            
            // Add gradient effect by varying alpha
            val alpha = (200 + (55 * heightFactor)).toInt().coerceIn(150, 255)
            paint.alpha = alpha
            
            canvas.drawLine(x, centerY - barHeight / 2, x, centerY + barHeight / 2, paint)
        }
        
        if (isAnimating) {
            animationTime += 0.12f
            postInvalidateOnAnimation()
        }
    }
}
