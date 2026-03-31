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
     * Inserts a bitmap at the given cursor position with text wrapping.
     * The image is placed inline and text flows to the right of it.
     */
    fun insertImageAtCursor(bitmap: Bitmap, cursorPos: Int) {
        val density = resources.displayMetrics.density

        // Max image width: 40% of editor width, min 120dp
        val maxWidthPx = (width * 0.40f).toInt().coerceAtLeast((120 * density).toInt())
        val maxHeightPx = (200 * density).toInt()

        // Scale bitmap maintaining aspect ratio
        val scaledBitmap = scaleBitmap(bitmap, maxWidthPx, maxHeightPx)
        val imgW = scaledBitmap.width
        val imgH = scaledBitmap.height

        val drawable = BitmapDrawable(resources, scaledBitmap).apply {
            setBounds(0, 0, imgW, imgH)
        }

        val sb = text as? SpannableStringBuilder ?: SpannableStringBuilder(text)
        val pos = cursorPos.coerceIn(0, sb.length)

        // Insert: newline + image anchor char + newline
        val imgChar = "\uFFFC" // object replacement character — standard image anchor
        val insertStr = if (pos > 0 && sb.isNotEmpty() && sb[pos - 1] != '\n') "\n$imgChar\n" else "$imgChar\n"
        val imgOffset = if (insertStr.startsWith("\n")) 1 else 0

        sb.insert(pos, insertStr)
        val imgStart = pos + imgOffset
        val imgEnd = imgStart + 1

        // Attach ImageSpan
        sb.setSpan(
            ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM),
            imgStart, imgEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // LeadingMarginSpan on the line AFTER the image so text wraps beside it
        // Apply to the character right after the image up to end of paragraph
        val afterImg = imgEnd
        val paraEnd = findParagraphEnd(sb, afterImg)
        if (afterImg < paraEnd) {
            sb.setSpan(
                LeadingMarginSpan.Standard(imgW + (8 * density).toInt(), 0),
                afterImg, paraEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

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

    private fun findParagraphEnd(sb: CharSequence, from: Int): Int {
        for (i in from until sb.length) {
            if (sb[i] == '\n') return i
        }
        return sb.length
    }
}
