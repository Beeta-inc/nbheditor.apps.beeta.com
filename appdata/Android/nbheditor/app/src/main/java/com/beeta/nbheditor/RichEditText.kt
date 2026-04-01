package com.beeta.nbheditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * EditText that supports inline images with text wrapping beside them,
 * similar to how Word/LibreOffice handle inline images.
 *
 * Images are inserted as ImageSpan anchors. Text before and after the image
 * on the same "block" uses LeadingMarginSpan to indent around the image width.
 */
class RichEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyle) {

    /**
     * Inserts a bitmap at the given cursor position.
     * Image is scaled down and placed inline with text.
     */
    fun insertImageAtCursor(bitmap: Bitmap, cursorPos: Int) {
        val density = resources.displayMetrics.density

        // Scale to max 120dp width
        val maxWidthPx = (120 * density).toInt()
        val maxHeightPx = (120 * density).toInt()

        val scaledBitmap = scaleBitmap(bitmap, maxWidthPx, maxHeightPx)
        val imgW = scaledBitmap.width
        val imgH = scaledBitmap.height

        val drawable = BitmapDrawable(resources, scaledBitmap).apply {
            setBounds(0, 0, imgW, imgH)
        }

        val sb = text as? SpannableStringBuilder ?: SpannableStringBuilder(text)
        val pos = cursorPos.coerceIn(0, sb.length)

        // Insert on new line with newlines around it
        val prefix = if (pos > 0 && sb.isNotEmpty() && sb[pos - 1] != '\n') "\n" else ""
        val imgChar = "\uFFFC"
        val suffix = "\n"
        val insertStr = "$prefix$imgChar$suffix"
        
        sb.insert(pos, insertStr)
        val imgPos = pos + prefix.length

        // Attach ImageSpan
        sb.setSpan(
            ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM),
            imgPos, imgPos + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        setText(sb)
        setSelection((pos + insertStr.length).coerceAtMost(text?.length ?: 0))
    }

    private fun scaleBitmap(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val ratio = src.width.toFloat() / src.height
        var w = maxW
        var h = (w / ratio).toInt()
        if (h > maxH) { h = maxH; w = (h * ratio).toInt() }
        return if (w != src.width || h != src.height)
            Bitmap.createScaledBitmap(src, w, h, true)
        else src
    }

    private fun findLineStart(sb: CharSequence, from: Int): Int {
        for (i in (from - 1) downTo 0) {
            if (sb[i] == '\n') return i + 1
        }
        return 0
    }

    private fun findLineEnd(sb: CharSequence, from: Int): Int {
        for (i in from until sb.length) {
            if (sb[i] == '\n') return i
        }
        return sb.length
    }
}
