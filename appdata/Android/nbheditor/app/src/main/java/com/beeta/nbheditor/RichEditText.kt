package com.beeta.nbheditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.*
import android.util.AttributeSet
import android.util.Log
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.text.HtmlCompat

/**
 * Rich text editor with support for Markdown, HTML, and inline formatting.
 * Automatically renders formatting while editing.
 */
class RichEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyle) {

    var isRichTextMode = true
    private var isApplyingSpans = false

    /**
     * Applies rich text formatting to the current text.
     * Supports Markdown and HTML.
     */
    fun applyRichTextFormatting() {
        if (!isRichTextMode || isApplyingSpans) return
        
        // Don't format if user is actively interacting with the text
        if (hasSelection() && hasFocus()) {
            return
        }
        
        isApplyingSpans = true
        
        post {
            try {
                val currentText = text?.toString() ?: ""
                if (currentText.isEmpty()) {
                    isApplyingSpans = false
                    return@post
                }
                
                // Save cursor position
                val cursorStart = selectionStart
                val cursorEnd = selectionEnd
                
                // Create new spannable from scratch
                val newSpannable = SpannableStringBuilder(currentText)
                
                // Copy ImageSpans from original
                val originalText = text
                if (originalText is Spannable) {
                    val imageSpans = originalText.getSpans(0, originalText.length, ImageSpan::class.java)
                    for (span in imageSpans) {
                        val start = originalText.getSpanStart(span)
                        val end = originalText.getSpanEnd(span)
                        if (start >= 0 && end <= newSpannable.length && start < end) {
                            newSpannable.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
                
                // Apply formatting to new spannable
                when {
                    currentText.contains("<html>", ignoreCase = true) || 
                    currentText.contains("<p>", ignoreCase = true) -> applyHtmlFormatting(newSpannable)
                    else -> applyMarkdownFormatting(newSpannable)
                }
                
                // Replace text with formatted version
                setText(newSpannable)
                
                // Restore cursor position
                postDelayed({
                    try {
                        val safeStart = cursorStart.coerceIn(0, text?.length ?: 0)
                        val safeEnd = cursorEnd.coerceIn(0, text?.length ?: 0)
                        setSelection(safeStart, safeEnd)
                    } catch (e: Exception) {
                        // Ignore cursor errors
                    }
                }, 50)
            } catch (e: Exception) {
                Log.e("RichEditText", "Error applying formatting", e)
            } finally {
                isApplyingSpans = false
            }
        }
    }
    
    private fun applyMarkdownFormatting(spannable: Spannable) {
        try {
            val text = spannable.toString()
            val lines = text.split("\n")
            var currentPos = 0
            
            for (line in lines) {
                val lineStart = currentPos
                val lineEnd = currentPos + line.length
                
                // Safety check
                if (lineStart < 0 || lineEnd > spannable.length) {
                    currentPos = lineEnd + 1
                    continue
                }
                
                // Headings: # H1, ## H2, ### H3, etc.
                if (line.startsWith("# ")) {
                    safeSetSpan(spannable, RelativeSizeSpan(2.0f), lineStart, lineEnd)
                    safeSetSpan(spannable, StyleSpan(Typeface.BOLD), lineStart, lineEnd)
                } else if (line.startsWith("## ")) {
                    safeSetSpan(spannable, RelativeSizeSpan(1.7f), lineStart, lineEnd)
                    safeSetSpan(spannable, StyleSpan(Typeface.BOLD), lineStart, lineEnd)
                } else if (line.startsWith("### ")) {
                    safeSetSpan(spannable, RelativeSizeSpan(1.5f), lineStart, lineEnd)
                    safeSetSpan(spannable, StyleSpan(Typeface.BOLD), lineStart, lineEnd)
                } else if (line.startsWith("#### ")) {
                    safeSetSpan(spannable, RelativeSizeSpan(1.3f), lineStart, lineEnd)
                    safeSetSpan(spannable, StyleSpan(Typeface.BOLD), lineStart, lineEnd)
                }
                
                // Bold: **text** or __text__
                applyPattern(spannable, lineStart, lineEnd, "\\*\\*(.+?)\\*\\*") { start, end ->
                    safeSetSpan(spannable, StyleSpan(Typeface.BOLD), start, end)
                }
                applyPattern(spannable, lineStart, lineEnd, "__(.+?)__") { start, end ->
                    safeSetSpan(spannable, StyleSpan(Typeface.BOLD), start, end)
                }
                
                // Italic: *text* or _text_
                applyPattern(spannable, lineStart, lineEnd, "\\*(.+?)\\*") { start, end ->
                    safeSetSpan(spannable, StyleSpan(Typeface.ITALIC), start, end)
                }
                applyPattern(spannable, lineStart, lineEnd, "_(.+?)_") { start, end ->
                    safeSetSpan(spannable, StyleSpan(Typeface.ITALIC), start, end)
                }
                
                // Code: `code`
                applyPattern(spannable, lineStart, lineEnd, "`(.+?)`") { start, end ->
                    safeSetSpan(spannable, TypefaceSpan("monospace"), start, end)
                    safeSetSpan(spannable, BackgroundColorSpan(0x33888888), start, end)
                }
                
                // Strikethrough: ~~text~~
                applyPattern(spannable, lineStart, lineEnd, "~~(.+?)~~") { start, end ->
                    safeSetSpan(spannable, StrikethroughSpan(), start, end)
                }
                
                // Bullet lists: - item or * item
                if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ")) {
                    safeSetSpan(spannable, BulletSpan(20), lineStart, lineEnd)
                }
                
                currentPos = lineEnd + 1 // +1 for newline
            }
        } catch (e: Exception) {
            Log.e("RichEditText", "Error in markdown formatting", e)
        }
    }
    
    private fun safeSetSpan(spannable: Spannable, span: Any, start: Int, end: Int) {
        try {
            if (start >= 0 && end <= spannable.length && start < end) {
                spannable.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } catch (e: Exception) {
            // Ignore span errors
        }
    }
    
    private fun applyHtmlFormatting(spannable: Spannable) {
        try {
            val htmlText = spannable.toString()
            val spanned = HtmlCompat.fromHtml(htmlText, HtmlCompat.FROM_HTML_MODE_COMPACT)
            
            // Copy spans from parsed HTML
            if (spanned is Spannable) {
                val spans = spanned.getSpans(0, spanned.length, Any::class.java)
                for (span in spans) {
                    val start = spanned.getSpanStart(span)
                    val end = spanned.getSpanEnd(span)
                    val flags = spanned.getSpanFlags(span)
                    
                    if (start >= 0 && end <= spannable.length) {
                        spannable.setSpan(span, start, end, flags)
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback to markdown if HTML parsing fails
            applyMarkdownFormatting(spannable)
        }
    }
    
    private fun applyPattern(spannable: Spannable, lineStart: Int, lineEnd: Int, pattern: String, applySpan: (Int, Int) -> Unit) {
        try {
            // Safety check
            if (lineStart < 0 || lineEnd > spannable.length || lineStart >= lineEnd) return
            
            val regex = Regex(pattern)
            val lineText = spannable.substring(lineStart, lineEnd.coerceAtMost(spannable.length))
            val matches = regex.findAll(lineText)
            
            for (match in matches) {
                val start = lineStart + match.range.first
                val end = lineStart + match.range.last + 1
                if (start >= 0 && end <= spannable.length && start < end) {
                    applySpan(start, end)
                }
            }
        } catch (e: Exception) {
            // Ignore regex errors
        }
    }

    /**
     * Inserts a bitmap at the given cursor position.
     */
    fun insertImageAtCursor(bitmap: Bitmap, cursorPos: Int) {
        val density = resources.displayMetrics.density
        val maxWidthPx = (120 * density).toInt()
        val maxHeightPx = (120 * density).toInt()

        val scaledBitmap = scaleBitmap(bitmap, maxWidthPx, maxHeightPx)
        val imgW = scaledBitmap.width
        val imgH = scaledBitmap.height

        val drawable = BitmapDrawable(resources, scaledBitmap).apply {
            setBounds(0, 0, imgW, imgH)
        }

        val currentText = text ?: return
        val sb = if (currentText is SpannableStringBuilder) currentText else SpannableStringBuilder(currentText)
        val pos = cursorPos.coerceIn(0, sb.length)

        val prefix = if (pos > 0 && sb.isNotEmpty() && sb[pos - 1] != '\n') "\n" else ""
        val imgChar = " "
        val suffix = "\n"
        val insertStr = "$prefix$imgChar$suffix"
        
        sb.insert(pos, insertStr)
        val imgPos = pos + prefix.length

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
}
