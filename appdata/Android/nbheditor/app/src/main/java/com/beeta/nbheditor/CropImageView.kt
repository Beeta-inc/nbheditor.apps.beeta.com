package com.beeta.nbheditor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CropImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            value?.let { resetCrop(it) }
            invalidate()
        }

    // Crop rect in bitmap coordinates
    private var cropRect = RectF()
    // Displayed bitmap rect on canvas
    private var bmpRect = RectF()

    private val paintDim   = Paint().apply { color = 0xAA000000.toInt() }
    private val paintBorder = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val paintHandle = Paint().apply { color = Color.WHITE }
    private val paintGrid  = Paint().apply {
        color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = 1f
    }

    private val HANDLE = 28f   // handle touch radius px
    private var dragging = -1  // 0=TL 1=TR 2=BR 3=BL 4=whole rect
    private var lastX = 0f; private var lastY = 0f

    private fun resetCrop(bmp: Bitmap) {
        // Start with full image selected
        cropRect.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
    }

    /** Returns the cropped + any pending rotation already applied bitmap */
    fun getCroppedBitmap(): Bitmap? {
        val bmp = bitmap ?: return null
        val l = cropRect.left.toInt().coerceIn(0, bmp.width)
        val t = cropRect.top.toInt().coerceIn(0, bmp.height)
        val r = cropRect.right.toInt().coerceIn(l + 1, bmp.width)
        val b = cropRect.bottom.toInt().coerceIn(t + 1, bmp.height)
        return Bitmap.createBitmap(bmp, l, t, r - l, b - t)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw == 0f || vh == 0f) return

        // Fit bitmap into view
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val bw = bmp.width * scale; val bh = bmp.height * scale
        val ox = (vw - bw) / 2f; val oy = (vh - bh) / 2f
        bmpRect.set(ox, oy, ox + bw, oy + bh)

        canvas.drawBitmap(bmp, null, bmpRect, null)

        // Map crop rect to canvas coords
        val cl = ox + cropRect.left  * scale
        val ct = oy + cropRect.top   * scale
        val cr = ox + cropRect.right * scale
        val cb = oy + cropRect.bottom * scale
        val cr2 = RectF(cl, ct, cr, cb)

        // Dim outside crop
        canvas.drawRect(bmpRect.left, bmpRect.top, bmpRect.right, ct, paintDim)
        canvas.drawRect(bmpRect.left, cb, bmpRect.right, bmpRect.bottom, paintDim)
        canvas.drawRect(bmpRect.left, ct, cl, cb, paintDim)
        canvas.drawRect(cr, ct, bmpRect.right, cb, paintDim)

        // Grid lines (rule of thirds)
        val tw = (cr - cl) / 3f; val th = (cb - ct) / 3f
        canvas.drawLine(cl + tw, ct, cl + tw, cb, paintGrid)
        canvas.drawLine(cl + tw * 2, ct, cl + tw * 2, cb, paintGrid)
        canvas.drawLine(cl, ct + th, cr, ct + th, paintGrid)
        canvas.drawLine(cl, ct + th * 2, cr, ct + th * 2, paintGrid)

        // Border
        canvas.drawRect(cr2, paintBorder)

        // Corner handles
        val h = HANDLE / 2f
        for ((hx, hy) in listOf(cl to ct, cr to ct, cr to cb, cl to cb)) {
            canvas.drawCircle(hx, hy, h, paintHandle)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmap ?: return false
        val vw = width.toFloat(); val vh = height.toFloat()
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val ox = (vw - bmp.width * scale) / 2f
        val oy = (vh - bmp.height * scale) / 2f

        // Canvas coords of crop corners
        val cl = ox + cropRect.left  * scale
        val ct = oy + cropRect.top   * scale
        val cr = ox + cropRect.right * scale
        val cb = oy + cropRect.bottom * scale

        val x = event.x; val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = when {
                    dist(x, y, cl, ct) < HANDLE -> 0
                    dist(x, y, cr, ct) < HANDLE -> 1
                    dist(x, y, cr, cb) < HANDLE -> 2
                    dist(x, y, cl, cb) < HANDLE -> 3
                    x in cl..cr && y in ct..cb  -> 4
                    else -> -1
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging < 0) return true
                val dx = (x - lastX) / scale
                val dy = (y - lastY) / scale
                val minSize = 40f
                when (dragging) {
                    0 -> { cropRect.left   = (cropRect.left   + dx).coerceIn(0f, cropRect.right  - minSize)
                           cropRect.top    = (cropRect.top    + dy).coerceIn(0f, cropRect.bottom - minSize) }
                    1 -> { cropRect.right  = (cropRect.right  + dx).coerceIn(cropRect.left + minSize, bmp.width.toFloat())
                           cropRect.top    = (cropRect.top    + dy).coerceIn(0f, cropRect.bottom - minSize) }
                    2 -> { cropRect.right  = (cropRect.right  + dx).coerceIn(cropRect.left + minSize, bmp.width.toFloat())
                           cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top  + minSize, bmp.height.toFloat()) }
                    3 -> { cropRect.left   = (cropRect.left   + dx).coerceIn(0f, cropRect.right  - minSize)
                           cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top  + minSize, bmp.height.toFloat()) }
                    4 -> {
                        val newL = (cropRect.left + dx).coerceIn(0f, bmp.width  - cropRect.width())
                        val newT = (cropRect.top  + dy).coerceIn(0f, bmp.height - cropRect.height())
                        cropRect.offsetTo(newL, newT)
                    }
                }
                lastX = x; lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = -1
        }
        return true
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
        Math.hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()
}
