package com.beeta.nbheditor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * High-performance Drawing Canvas View matching nbhweb src/drawingCanvas.js
 * Supports smooth touch paths, pen, highlighter (alpha blending), eraser, and undo/redo stacks.
 */
class DrawingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Tool { PEN, HIGHLIGHTER, ERASER }

    private var currentTool = Tool.PEN
    private var currentColor = Color.WHITE
    private var strokeWidth = 8f

    private var canvasBitmap: Bitmap? = null
    private var drawCanvas: Canvas? = null
    private val canvasPaint = Paint(Paint.DITHER_FLAG)

    private val drawPaint = Paint().apply {
        isAntiAlias = true
        isDither = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val currentPath = Path()
    private var mX = 0f
    private var mY = 0f
    private val TOUCH_TOLERANCE = 4f

    private val undoStack = mutableListOf<Bitmap>()
    private val redoStack = mutableListOf<Bitmap>()
    private val MAX_UNDO = 25

    var onHistoryChangeListener: (() -> Unit)? = null

    init {
        updatePaint()
    }

    private fun updatePaint() {
        when (currentTool) {
            Tool.PEN -> {
                drawPaint.xfermode = null
                drawPaint.color = currentColor
                drawPaint.strokeWidth = strokeWidth
                drawPaint.alpha = 255
            }
            Tool.HIGHLIGHTER -> {
                drawPaint.xfermode = null
                drawPaint.color = currentColor
                drawPaint.strokeWidth = strokeWidth * 2.5f
                drawPaint.alpha = 90
            }
            Tool.ERASER -> {
                drawPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                drawPaint.strokeWidth = strokeWidth * 3f
            }
        }
    }

    fun setTool(tool: Tool) {
        currentTool = tool
        updatePaint()
    }

    fun setColor(color: Int) {
        currentColor = color
        if (currentTool == Tool.ERASER) {
            currentTool = Tool.PEN
        }
        updatePaint()
    }

    fun setStrokeThickness(size: Float) {
        strokeWidth = size
        updatePaint()
    }

    fun getTool(): Tool = currentTool

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val newBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val newCanvas = Canvas(newBitmap)
        newCanvas.drawColor(Color.TRANSPARENT)
        
        canvasBitmap?.let { oldBmp ->
            newCanvas.drawBitmap(oldBmp, 0f, 0f, null)
            oldBmp.recycle()
        }
        canvasBitmap = newBitmap
        drawCanvas = newCanvas
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvasBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, canvasPaint)
        }
        if (!currentPath.isEmpty) {
            canvas.drawPath(currentPath, drawPaint)
        }
    }

    private fun saveUndoState() {
        canvasBitmap?.let { bmp ->
            if (undoStack.size >= MAX_UNDO) {
                val oldest = undoStack.removeAt(0)
                if (!oldest.isRecycled) oldest.recycle()
            }
            undoStack.add(bmp.copy(bmp.config, true))
            clearRedo()
            onHistoryChangeListener?.invoke()
        }
    }

    private fun clearRedo() {
        redoStack.forEach { if (!it.isRecycled) it.recycle() }
        redoStack.clear()
    }

    fun undo() {
        if (undoStack.isNotEmpty() && canvasBitmap != null) {
            val prev = undoStack.removeAt(undoStack.size - 1)
            redoStack.add(canvasBitmap!!.copy(canvasBitmap!!.config, true))
            
            val canvas = drawCanvas ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(prev, 0f, 0f, null)
            prev.recycle()
            invalidate()
            onHistoryChangeListener?.invoke()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty() && canvasBitmap != null) {
            val next = redoStack.removeAt(redoStack.size - 1)
            undoStack.add(canvasBitmap!!.copy(canvasBitmap!!.config, true))
            
            val canvas = drawCanvas ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(next, 0f, 0f, null)
            next.recycle()
            invalidate()
            onHistoryChangeListener?.invoke()
        }
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun clearCanvas() {
        saveUndoState()
        drawCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        invalidate()
    }

    fun exportBitmap(backgroundColor: Int = Color.BLACK): Bitmap? {
        val bmp = canvasBitmap ?: return null
        val output = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(backgroundColor)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        return output
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                saveUndoState()
                currentPath.reset()
                currentPath.moveTo(x, y)
                mX = x
                mY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(x - mX)
                val dy = Math.abs(y - mY)
                if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                    currentPath.quadTo(mX, mY, (x + mX) / 2, (y + mY) / 2)
                    mX = x
                    mY = y
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(mX, mY)
                drawCanvas?.drawPath(currentPath, drawPaint)
                currentPath.reset()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                currentPath.reset()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
