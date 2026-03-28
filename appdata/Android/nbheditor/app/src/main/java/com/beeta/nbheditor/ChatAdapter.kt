package com.beeta.nbheditor

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class ChatAdapter(
    private val onInsert: (String) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val messages = mutableListOf<MainActivity.ChatMessage>()

    fun addMessage(message: MainActivity.ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (messages[position].role == "user") 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val layout = if (viewType == 0) R.layout.item_chat_user else R.layout.item_chat_ai
        return ChatViewHolder(
            LayoutInflater.from(parent.context).inflate(layout, parent, false),
            viewType, onInsert
        )
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) =
        holder.bind(messages[position])

    override fun getItemCount() = messages.size

    class ChatViewHolder(
        itemView: View,
        private val viewType: Int,
        private val onInsert: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val insertBtn: MaterialButton? = itemView.findViewById(R.id.insertBtn)
        private val codeBlockContainer: LinearLayout? = itemView.findViewById(R.id.codeBlockContainer)

        private val ctx get() = itemView.context

        // Colors
        private val colorAccent   get() = ContextCompat.getColor(ctx, R.color.accent_primary)
        private val colorSecond   get() = ContextCompat.getColor(ctx, R.color.accent_secondary)
        private val colorPurple   get() = ContextCompat.getColor(ctx, R.color.accent_purple)
        private val colorPeach    get() = ContextCompat.getColor(ctx, R.color.accent_peach)
        private val colorText     get() = ContextCompat.getColor(ctx, R.color.chat_ai_text)
        private val colorSurface  get() = ContextCompat.getColor(ctx, R.color.editor_line_numbers_bg)
        private val colorDivider  get() = ContextCompat.getColor(ctx, R.color.divider)

        fun bind(message: MainActivity.ChatMessage) {
            if (viewType == 0) {
                messageText.text = message.content
            } else {
                renderFormattedText(message.content)
                insertBtn?.setOnClickListener { onInsert(message.content) }
            }
        }

        // ── Main renderer ─────────────────────────────────────────────────────

        private fun renderFormattedText(raw: String) {
            codeBlockContainer?.removeAllViews()

            // Segment into code blocks vs text
            data class Segment(val content: String, val isCode: Boolean, val lang: String = "")
            val segments = mutableListOf<Segment>()
            val codeRegex = Regex("```([\\w]*)?\\n?([\\s\\S]*?)```", RegexOption.MULTILINE)
            var lastEnd = 0
            for (match in codeRegex.findAll(raw)) {
                if (match.range.first > lastEnd)
                    segments.add(Segment(raw.substring(lastEnd, match.range.first), false))
                segments.add(Segment(match.groupValues[2].trimEnd(), true, match.groupValues[1].trim()))
                lastEnd = match.range.last + 1
            }
            if (lastEnd < raw.length) segments.add(Segment(raw.substring(lastEnd), false))

            if (segments.size == 1 && !segments[0].isCode) {
                messageText.text = buildRichText(segments[0].content)
                messageText.visibility = View.VISIBLE
                codeBlockContainer?.visibility = View.GONE
            } else {
                messageText.visibility = View.GONE
                codeBlockContainer?.visibility = View.VISIBLE
                for (seg in segments) {
                    if (seg.content.isBlank()) continue
                    if (seg.isCode) addCodeBlock(seg.content, seg.lang)
                    else addTextBlock(seg.content.trim())
                }
            }
        }

        // ── Code block view ───────────────────────────────────────────────────

        private fun addCodeBlock(code: String, lang: String) {
            val wrapper = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(colorSurface)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 10, 0, 10) }
            }

            // Language label bar
            if (lang.isNotEmpty()) {
                val label = TextView(ctx).apply {
                    text = lang.uppercase()
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    setTextColor(colorAccent)
                    setBackgroundColor(colorDivider)
                    setPadding(20, 6, 20, 6)
                }
                wrapper.addView(label)
            }

            // Code text
            val tv = TextView(ctx).apply {
                text = code
                typeface = Typeface.MONOSPACE
                textSize = 12.5f
                setTextColor(colorSecond)
                setPadding(20, 14, 20, 14)
                setLineSpacing(0f, 1.4f)
            }
            wrapper.addView(tv)
            codeBlockContainer?.addView(wrapper)
        }

        // ── Text block view ───────────────────────────────────────────────────

        private fun addTextBlock(text: String) {
            val tv = TextView(ctx).apply {
                this.text = buildRichText(text)
                textSize = 14f
                setTextColor(colorText)
                setLineSpacing(0f, 1.6f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
            }
            codeBlockContainer?.addView(tv)
        }

        // ── Rich text builder ─────────────────────────────────────────────────

        private fun buildRichText(text: String): SpannableStringBuilder {
            val sb = SpannableStringBuilder()
            val lines = text.split("\n")
            var i = 0
            while (i < lines.size) {
                val line = lines[i]

                when {
                    // ── Horizontal rule ──────────────────────────────────────
                    line.matches(Regex("^[-*_]{3,}\\s*$")) -> {
                        sb.append("\n─────────────────────────\n")
                        val start = sb.length - 27
                        sb.setSpan(ForegroundColorSpan(colorDivider), start, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }

                    // ── Math block: $$...$$ ──────────────────────────────────
                    line.trim().startsWith("$$") && line.trim().endsWith("$$") && line.trim().length > 4 -> {
                        val math = line.trim().removeSurrounding("$$").trim()
                        appendMath(sb, math, block = true)
                    }

                    // ── Headings ─────────────────────────────────────────────
                    line.startsWith("#### ") -> appendHeading(sb, line.removePrefix("#### "), 14f, colorPurple)
                    line.startsWith("### ")  -> appendHeading(sb, line.removePrefix("### "),  15f, colorAccent)
                    line.startsWith("## ")   -> appendHeading(sb, line.removePrefix("## "),   17f, colorAccent)
                    line.startsWith("# ")    -> appendHeading(sb, line.removePrefix("# "),    20f, colorAccent)

                    // ── Table row ────────────────────────────────────────────
                    line.startsWith("|") && line.endsWith("|") -> {
                        // Skip separator rows like |---|---|
                        if (!line.matches(Regex("^[|\\-: ]+$"))) {
                            appendTableRow(sb, line)
                        }
                    }

                    // ── Blockquote ───────────────────────────────────────────
                    line.startsWith("> ") -> {
                        val content = line.removePrefix("> ")
                        val start = sb.length
                        sb.append("  ")
                        appendInline(sb, content)
                        sb.setSpan(QuoteSpan(colorAccent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(colorPurple), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }

                    // ── Bullet list ──────────────────────────────────────────
                    line.matches(Regex("^(\\s*)[-*+] .*")) -> {
                        val indent = line.length - line.trimStart().length
                        val bullet = if (indent >= 4) "      ◦ " else "  • "
                        sb.append(bullet)
                        appendInline(sb, line.trimStart().drop(2))
                    }

                    // ── Numbered list ────────────────────────────────────────
                    line.matches(Regex("^\\d+\\.\\s.*")) -> {
                        val dot = line.indexOf(". ")
                        val start = sb.length
                        sb.append("  ${line.substring(0, dot + 1)} ")
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(colorAccent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        appendInline(sb, line.substring(dot + 2))
                    }

                    // ── Inline math: $...$ ───────────────────────────────────
                    line.contains(Regex("\\$[^$]+\\$")) -> {
                        appendLineWithInlineMath(sb, line)
                    }

                    // ── Normal line ──────────────────────────────────────────
                    else -> appendInline(sb, line)
                }

                if (i < lines.size - 1) sb.append("\n")
                i++
            }
            return sb
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private fun appendHeading(sb: SpannableStringBuilder, text: String, size: Float, color: Int) {
            val start = sb.length
            sb.append(text)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(AbsoluteSizeSpan(size.toInt(), true), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun appendTableRow(sb: SpannableStringBuilder, line: String) {
            val cells = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            val start = sb.length
            sb.append(cells.joinToString("  │  "))
            sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(colorSecond), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        /** Renders a math expression with monospace + color + surrounding spaces */
        private fun appendMath(sb: SpannableStringBuilder, expr: String, block: Boolean) {
            val display = if (block) "\n  ∫ $expr\n" else " [$expr] "
            val start = sb.length
            sb.append(display)
            sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(colorPeach), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (block) {
                sb.setSpan(AbsoluteSizeSpan(15, true), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        private fun appendLineWithInlineMath(sb: SpannableStringBuilder, line: String) {
            val mathRegex = Regex("\\$([^$]+)\\$")
            var last = 0
            for (match in mathRegex.findAll(line)) {
                if (match.range.first > last) appendInline(sb, line.substring(last, match.range.first))
                appendMath(sb, match.groupValues[1], block = false)
                last = match.range.last + 1
            }
            if (last < line.length) appendInline(sb, line.substring(last))
        }

        private fun appendInline(sb: SpannableStringBuilder, text: String) {
            // **bold**, *italic*, ~~strikethrough~~, `code`, $math$
            val regex = Regex("(\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|~~(.+?)~~|`(.+?)`|\\$([^$]+)\\$)")
            var last = 0
            for (match in regex.findAll(text)) {
                if (match.range.first > last) sb.append(text.substring(last, match.range.first))
                val start = sb.length
                when {
                    match.value.startsWith("**") -> {
                        sb.append(match.groupValues[2])
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    match.value.startsWith("~~") -> {
                        sb.append(match.groupValues[4])
                        sb.setSpan(StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(colorDivider), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    match.value.startsWith("*") -> {
                        sb.append(match.groupValues[3])
                        sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    match.value.startsWith("`") -> {
                        sb.append(match.groupValues[5])
                        sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(colorSecond), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(BackgroundColorSpan(colorSurface), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    match.value.startsWith("$") -> {
                        appendMath(sb, match.groupValues[6], block = false)
                    }
                }
                last = match.range.last + 1
            }
            if (last < text.length) sb.append(text.substring(last))
        }
    }
}
