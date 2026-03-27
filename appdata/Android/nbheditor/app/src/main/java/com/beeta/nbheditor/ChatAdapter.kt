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

        fun bind(message: MainActivity.ChatMessage) {
            if (viewType == 0) {
                // User message — plain text
                messageText.text = message.content
            } else {
                // AI message — rich formatted
                renderFormattedText(message.content)
                insertBtn?.setOnClickListener { onInsert(message.content) }
            }
        }

        private fun renderFormattedText(raw: String) {
            val context = itemView.context
            codeBlockContainer?.removeAllViews()

            // Split into segments: code blocks vs normal text
            val segments = mutableListOf<Pair<String, Boolean>>() // content, isCode
            val codeBlockRegex = Regex("```[\\w]*\\n?([\\s\\S]*?)```", RegexOption.MULTILINE)
            var lastEnd = 0
            for (match in codeBlockRegex.findAll(raw)) {
                if (match.range.first > lastEnd) {
                    segments.add(Pair(raw.substring(lastEnd, match.range.first), false))
                }
                segments.add(Pair(match.groupValues[1].trimEnd(), true))
                lastEnd = match.range.last + 1
            }
            if (lastEnd < raw.length) segments.add(Pair(raw.substring(lastEnd), false))

            if (segments.size == 1 && !segments[0].second) {
                // No code blocks — render inline formatting in the main TextView
                messageText.text = buildInlineSpannable(segments[0].first)
                messageText.visibility = View.VISIBLE
                codeBlockContainer?.visibility = View.GONE
            } else {
                // Mixed content — use container
                messageText.visibility = View.GONE
                codeBlockContainer?.visibility = View.VISIBLE
                for ((content, isCode) in segments) {
                    if (content.isBlank()) continue
                    if (isCode) {
                        val tv = TextView(context).apply {
                            text = content
                            typeface = Typeface.MONOSPACE
                            textSize = 12f
                            setTextColor(ContextCompat.getColor(context, R.color.accent_secondary))
                            setBackgroundColor(ContextCompat.getColor(context, R.color.editor_line_numbers_bg))
                            setPadding(24, 16, 24, 16)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { setMargins(0, 8, 0, 8) }
                        }
                        codeBlockContainer?.addView(tv)
                    } else {
                        val tv = TextView(context)
                        tv.text = buildInlineSpannable(content.trim())
                        tv.textSize = 14f
                        tv.setTextColor(ContextCompat.getColor(context, R.color.chat_ai_text))
                        tv.setLineSpacing(0f, 1.5f)
                        tv.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 4, 0, 4) }
                        codeBlockContainer?.addView(tv)
                    }
                }
            }
        }

        private fun buildInlineSpannable(text: String): SpannableStringBuilder {
            val sb = SpannableStringBuilder()
            val lines = text.split("\n")
            for ((i, line) in lines.withIndex()) {
                when {
                    // Blockquote
                    line.startsWith("> ") -> {
                        val content = line.removePrefix("> ")
                        val start = sb.length
                        sb.append(content)
                        sb.setSpan(QuoteSpan(Color.parseColor("#89B4FA")), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(Color.parseColor("#CBA6F7")), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    // Heading
                    line.startsWith("### ") -> appendHeading(sb, line.removePrefix("### "), 15f)
                    line.startsWith("## ") -> appendHeading(sb, line.removePrefix("## "), 17f)
                    line.startsWith("# ") -> appendHeading(sb, line.removePrefix("# "), 19f)
                    // Bullet
                    line.startsWith("- ") || line.startsWith("* ") -> {
                        sb.append("  • ")
                        appendInline(sb, line.drop(2))
                    }
                    // Numbered list
                    line.matches(Regex("^\\d+\\. .*")) -> {
                        val dot = line.indexOf(". ")
                        sb.append("  ${line.substring(0, dot + 1)} ")
                        appendInline(sb, line.substring(dot + 2))
                    }
                    else -> appendInline(sb, line)
                }
                if (i < lines.size - 1) sb.append("\n")
            }
            return sb
        }

        private fun appendHeading(sb: SpannableStringBuilder, text: String, size: Float) {
            val start = sb.length
            sb.append(text)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(AbsoluteSizeSpan(size.toInt(), true), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.parseColor("#89B4FA")), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun appendInline(sb: SpannableStringBuilder, text: String) {
            // Handle **bold**, *italic*, `inline code`
            val regex = Regex("(\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`)")
            var last = 0
            for (match in regex.findAll(text)) {
                if (match.range.first > last) sb.append(text.substring(last, match.range.first))
                val start = sb.length
                when {
                    match.value.startsWith("**") -> {
                        sb.append(match.groupValues[2])
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    match.value.startsWith("*") -> {
                        sb.append(match.groupValues[3])
                        sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    match.value.startsWith("`") -> {
                        sb.append(match.groupValues[4])
                        sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(Color.parseColor("#A6E3A1")), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(BackgroundColorSpan(Color.parseColor("#313244")), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                last = match.range.last + 1
            }
            if (last < text.length) sb.append(text.substring(last))
        }
    }
}
