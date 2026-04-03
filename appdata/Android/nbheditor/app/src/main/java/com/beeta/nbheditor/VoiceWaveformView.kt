package com.beeta.nbheditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    private val bars = 30
    private val barHeights = FloatArray(bars) { 0.2f }
    private var animationPhase = 0f
    private var isAnimating = false
    private var hasVoice = false

    fun startAnimation(voiceDetected: Boolean = false) {
        isAnimating = true
        hasVoice = voiceDetected
        invalidate()
    }

    fun stopAnimation() {
        isAnimating = false
        hasVoice = false
        barHeights.fill(0.2f)
        invalidate()
    }

    fun setVoiceDetected(detected: Boolean) {
        hasVoice = detected
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        val barWidth = w / bars
        val centerY = h / 2f

        paint.color = if (hasVoice) {
            context.resources.getColor(R.color.accent_secondary, null) // Green when voice detected
        } else {
            context.resources.getColor(R.color.accent_peach, null) // Orange when listening
        }

        for (i in 0 until bars) {
            val x = i * barWidth + barWidth / 2f
            
            if (isAnimating) {
                // Animate bar heights
                if (hasVoice) {
                    // Active waveform when voice detected
                    barHeights[i] = (sin(animationPhase + i * 0.5f) * 0.4f + 0.5f).coerceIn(0.1f, 1f)
                } else {
                    // Gentle pulse when just listening
                    barHeights[i] = (sin(animationPhase * 0.5f) * 0.15f + 0.25f).coerceIn(0.1f, 0.4f)
                }
            }
            
            val barHeight = barHeights[i] * h * 0.7f
            canvas.drawLine(x, centerY - barHeight / 2f, x, centerY + barHeight / 2f, paint)
        }

        if (isAnimating) {
            animationPhase += if (hasVoice) 0.3f else 0.15f
            postInvalidateDelayed(50)
        }
    }
}
