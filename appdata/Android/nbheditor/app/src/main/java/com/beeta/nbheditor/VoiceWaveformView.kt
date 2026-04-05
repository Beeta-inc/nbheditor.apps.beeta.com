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
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    private val bars = 40
    private val barHeights = FloatArray(bars) { 0.2f }
    private val barVelocities = FloatArray(bars) { 0f }
    private var animationPhase = 0f
    private var isAnimating = false
    private var hasVoice = false
    private var intensity = 0.3f

    fun startAnimation(voiceDetected: Boolean = false) {
        isAnimating = true
        hasVoice = voiceDetected
        intensity = if (voiceDetected) 0.8f else 0.3f
        invalidate()
    }

    fun stopAnimation() {
        isAnimating = false
        hasVoice = false
        intensity = 0.2f
        barHeights.fill(0.2f)
        invalidate()
    }

    fun setVoiceDetected(detected: Boolean) {
        hasVoice = detected
        intensity = if (detected) 0.8f else 0.3f
    }
    
    fun setIntensity(level: Float) {
        intensity = level.coerceIn(0.2f, 1.0f)
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
            context.resources.getColor(R.color.accent_primary, null) // Blue when listening
        }

        for (i in 0 until bars) {
            val x = i * barWidth + barWidth / 2f
            
            if (isAnimating) {
                // Smooth wave animation with physics-like motion
                val targetHeight = if (hasVoice) {
                    // Active waveform when voice detected
                    val wave1 = sin(animationPhase + i * 0.4f) * 0.35f
                    val wave2 = sin(animationPhase * 1.5f + i * 0.2f) * 0.25f
                    (wave1 + wave2 + 0.6f).coerceIn(0.2f, 1.0f) * intensity
                } else {
                    // Gentle pulse when just listening
                    val pulse = sin(animationPhase * 0.6f + i * 0.1f) * 0.1f
                    (0.25f + pulse).coerceIn(0.15f, 0.4f)
                }
                
                // Smooth interpolation
                val diff = targetHeight - barHeights[i]
                barVelocities[i] = barVelocities[i] * 0.7f + diff * 0.3f
                barHeights[i] += barVelocities[i]
                barHeights[i] = barHeights[i].coerceIn(0.1f, 1.0f)
            }
            
            val barHeight = barHeights[i] * h * 0.8f
            
            // Add alpha variation for depth effect
            val alpha = (180 + (75 * barHeights[i])).toInt().coerceIn(150, 255)
            paint.alpha = alpha
            
            canvas.drawLine(x, centerY - barHeight / 2f, x, centerY + barHeight / 2f, paint)
        }

        if (isAnimating) {
            animationPhase += if (hasVoice) 0.35f else 0.18f
            postInvalidateDelayed(33) // ~30 FPS
        }
    }
}
