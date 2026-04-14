package com.beeta.nbheditor

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var scaleFactor = 1f
    private val minScale = 1f
    private val maxScale = 5f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scale = detector.scaleFactor
            scaleFactor = (scaleFactor * scale).coerceIn(minScale, maxScale)
            matrix.set(savedMatrix)
            matrix.postScale(scaleFactor / getCurrentScale(), scaleFactor / getCurrentScale(),
                detector.focusX, detector.focusY)
            imageMatrix = matrix
            constrainMatrix()
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            savedMatrix.set(matrix)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scaleFactor > minScale) {
                // Reset to fit
                scaleFactor = minScale
                matrix.reset()
                imageMatrix = matrix
            } else {
                // Zoom in to 2.5x at tap point
                scaleFactor = 2.5f
                matrix.postScale(2.5f, 2.5f, e.x, e.y)
                imageMatrix = matrix
                constrainMatrix()
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress && scaleFactor > minScale) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    matrix.postTranslate(dx, dy)
                    imageMatrix = matrix
                    constrainMatrix()
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }

    private fun getCurrentScale(): Float {
        matrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun constrainMatrix() {
        val drawable = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val drawW = drawable.intrinsicWidth * getCurrentScale()
        val drawH = drawable.intrinsicHeight * getCurrentScale()

        matrix.getValues(matrixValues)
        var transX = matrixValues[Matrix.MTRANS_X]
        var transY = matrixValues[Matrix.MTRANS_Y]

        if (drawW <= viewW) {
            transX = (viewW - drawW) / 2f
        } else {
            transX = transX.coerceIn(viewW - drawW, 0f)
        }
        if (drawH <= viewH) {
            transY = (viewH - drawH) / 2f
        } else {
            transY = transY.coerceIn(viewH - drawH, 0f)
        }

        matrixValues[Matrix.MTRANS_X] = transX
        matrixValues[Matrix.MTRANS_Y] = transY
        matrix.setValues(matrixValues)
        imageMatrix = matrix
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        matrix.reset()
        imageMatrix = matrix
        scaleFactor = minScale
    }
}
