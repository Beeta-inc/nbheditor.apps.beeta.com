package com.beeta.nbheditor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class ChatAdapter(
    private val onInsert: (String) -> Unit,
    private val onInsertImage: ((String) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_USER = 0
        const val TYPE_AI = 1
        const val TYPE_IMAGE = 2
    }

    data class ImageMessage(val prompt: String, val base64: String)

    fun getMessages(): List<Any> = messages.toList()

    private val messages = mutableListOf<Any>()

    fun addMessage(message: MainActivity.ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun addImageMessage(msg: ImageMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (val m = messages[position]) {
        is ImageMessage -> TYPE_IMAGE
        is MainActivity.ChatMessage -> if (m.role == "user") TYPE_USER else TYPE_AI
        else -> TYPE_AI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserViewHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            TYPE_IMAGE -> ImageViewHolder(inflater.inflate(R.layout.item_chat_image, parent, false), onInsertImage)
            else -> AiViewHolder(inflater.inflate(R.layout.item_chat_ai, parent, false), onInsert)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is UserViewHolder -> holder.bind(messages[position] as MainActivity.ChatMessage)
            is AiViewHolder -> holder.bind(messages[position] as MainActivity.ChatMessage)
            is ImageViewHolder -> holder.bind(messages[position] as ImageMessage)
        }
    }

    override fun getItemCount() = messages.size

    // ── User ViewHolder ───────────────────────────────────────────────────────

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        fun bind(msg: MainActivity.ChatMessage) { messageText.text = msg.content }
    }

    // ── Image ViewHolder ──────────────────────────────────────────────────────

    class ImageViewHolder(itemView: View, private val onInsertImage: ((String) -> Unit)? = null) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.generatedImage)
        private val promptLabel: TextView = itemView.findViewById(R.id.imagePromptLabel)
        private val insertBtn: com.google.android.material.button.MaterialButton? = itemView.findViewById(R.id.insertImageBtn)
        fun bind(msg: ImageMessage) {
            promptLabel.text = msg.prompt
            try {
                val bytes = Base64.decode(msg.base64, Base64.DEFAULT)
                imageView.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            } catch (_: Exception) {
                imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            insertBtn?.setOnClickListener { onInsertImage?.invoke(msg.base64) }
        }
    }

    // ── AI ViewHolder ─────────────────────────────────────────────────────────

    class AiViewHolder(
        itemView: View,
        private val onInsert: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val insertBtn: MaterialButton? = itemView.findViewById(R.id.insertBtn)
        private val copyBtn: MaterialButton? = itemView.findViewById(R.id.copyBtn)
        private val codeBlockContainer: LinearLayout? = itemView.findViewById(R.id.codeBlockContainer)
        private val ctx get() = itemView.context

        // Semantic colors
        private val colorAccent   get() = ContextCompat.getColor(ctx, R.color.accent_primary)
        private val colorSecond   get() = ContextCompat.getColor(ctx, R.color.accent_secondary)
        private val colorPurple   get() = ContextCompat.getColor(ctx, R.color.accent_purple)
        private val colorPeach    get() = ContextCompat.getColor(ctx, R.color.accent_peach)
        private val colorText     get() = ContextCompat.getColor(ctx, R.color.chat_ai_text)
        private val colorSurface  get() = ContextCompat.getColor(ctx, R.color.editor_line_numbers_bg)
        private val colorDivider  get() = ContextCompat.getColor(ctx, R.color.divider)
        private val colorCodeBg   get() = ContextCompat.getColor(ctx, R.color.editor_surface)
        private val colorCodeBorder get() = ContextCompat.getColor(ctx, R.color.accent_primary)
        private val colorLineNumberActive get() = ContextCompat.getColor(ctx, R.color.accent_primary)
        private val colorLineNumberInactive get() = ContextCompat.getColor(ctx, R.color.editor_hint)

        // Syntax highlight palette (dark-friendly, enhanced)
        private val synKeyword  get() = ContextCompat.getColor(ctx, R.color.accent_purple)   // purple
        private val synString   get() = ContextCompat.getColor(ctx, R.color.accent_secondary) // green
        private val synComment  get() = ContextCompat.getColor(ctx, R.color.editor_hint)      // muted
        private val synNumber   get() = ContextCompat.getColor(ctx, R.color.accent_peach)     // orange
        private val synType     get() = ContextCompat.getColor(ctx, R.color.accent_primary)   // blue
        private val synDefault  get() = ContextCompat.getColor(ctx, R.color.editor_text)
        private val synSymbol   get() = ContextCompat.getColor(ctx, R.color.accent_secondary) // green
        private val synBuiltIn  get() = ContextCompat.getColor(ctx, R.color.accent_peach)     // orange

        fun bind(message: MainActivity.ChatMessage) {
            renderFormattedText(message.content)
            insertBtn?.setOnClickListener { onInsert(message.content) }
            copyBtn?.setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AI response", message.content))
                // Visual feedback for copy
                copyBtn.alpha = 0.7f
                copyBtn.postDelayed({ copyBtn.alpha = 1f }, 100)
                Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        private fun renderFormattedText(raw: String) {
            codeBlockContainer?.removeAllViews()
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

        private fun addCodeBlock(code: String, lang: String) {
            // Main container for the code block with enhanced terminal-like appearance
            val mainContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(colorCodeBg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 16, 0, 16) }
                setPadding(20, 20, 20, 20)
                
                 // Add subtle border with rounded corners
                 val border = GradientDrawable().apply {
                     setColor(colorCodeBg)
                     setStroke(2, colorCodeBorder)
                     setCornerRadius(12f)
                 }
                 background = border
            }

             // Header: language label + copy button with enhanced terminal styling
             val header = LinearLayout(ctx).apply {
                 orientation = LinearLayout.HORIZONTAL
                 setBackgroundColor(adjustAlpha(colorDivider, 0.95f))
                 setPadding(24, 16, 24, 16)
                 gravity = android.view.Gravity.CENTER_VERTICAL
             }
             
             val langLabel = TextView(ctx).apply {
                 text = if (lang.isNotEmpty()) lang.uppercase() else "TERMINAL"
                 textSize = 12f
                 typeface = Typeface.MONOSPACE
                 setTextColor(colorAccent)
                 letterSpacing = 0.2f
                 layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
             }
             
             val copyCodeBtn = TextView(ctx).apply {
                 text = "⎘ Copy Code"
                 textSize = 13f
                 setTextColor(colorSecond)
                 setPadding(24, 12, 24, 12)
                 // Add subtle background for button
                 val btnBg = GradientDrawable().apply {
                     setColor(adjustAlpha(colorSurface, 0.2f))
                     setCornerRadius(6f)
                 }
                 background = btnBg
             }
             
             // Set up the click listener for copyCodeBtn
             copyCodeBtn.setOnClickListener {
                 val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                 cm.setPrimaryClip(ClipData.newPlainText("code", code))
                 // Visual feedback
                 copyCodeBtn.alpha = 0.7f
                 copyCodeBtn.postDelayed({ copyCodeBtn.alpha = 1f }, 150)
                 Toast.makeText(ctx, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
             }
            
            header.addView(langLabel)
            header.addView(copyCodeBtn)
            mainContainer.addView(header)

            // Code container with enhanced line numbers
            val codeContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 16)
            }

            // Enhanced line numbers view with alternating background
            val lineNumbersView = TextView(ctx).apply {
                val lines = code.split("\n").size
                val lineNumbers = buildLineNumbers(lines)
                text = lineNumbers
                typeface = Typeface.MONOSPACE
                textSize = 13f
                setTextColor(colorLineNumberInactive)
                setPadding(0, 20, 16, 20)
                setLineSpacing(2.2f, 1.8f)
                setIncludeFontPadding(false)
                setBackgroundColor(adjustAlpha(colorCodeBg, 0.85f))
                // Add subtle right border to separate from code
                setBackground(createLineNumberBackground())
            }
            
            // Enhanced code view with syntax highlighting
            val codeView = TextView(ctx).apply {
                text = syntaxHighlight(code, lang)
                typeface = Typeface.MONOSPACE
                textSize = 13f
                setPadding(28, 20, 28, 20)
                setLineSpacing(2.2f, 1.8f)
                setHorizontallyScrolling(true)
                setIncludeFontPadding(false)
                setTextColor(synDefault)
                setBackgroundColor(colorCodeBg)
                // Add subtle inner shadow effect
                setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            }

            codeContainer.addView(lineNumbersView)
            codeContainer.addView(codeView)
            mainContainer.addView(codeContainer)
            
            codeBlockContainer?.addView(mainContainer)
        }

        private fun buildLineNumbers(count: Int): String {
            return (1..count).joinToString("\n") { "%3d".format(it) }
        }

        private fun createLineNumberBackground(): GradientDrawable {
            return GradientDrawable().apply {
                setColor(adjustAlpha(colorCodeBg, 0.85f))
                setGradientType(GradientDrawable.LINEAR_GRADIENT)
                setGradientCenter(0.8f, 0.5f) // More color on the left
                setGradientRadius(120f)
            }
        }

        /** Enhanced token-based syntax highlighter for common languages. */
        private fun syntaxHighlight(code: String, lang: String): SpannableStringBuilder {
            val sb = SpannableStringBuilder(code)

            // Default color first (lowest priority — overridden by all spans below)
            sb.setSpan(ForegroundColorSpan(synDefault), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

            val keywords = when (lang.lowercase()) {
                "kotlin", "java" -> setOf(
                    "fun", "val", "var", "class", "object", "interface", "if", "else", "when",
                    "for", "while", "return", "import", "package", "override", "private",
                    "public", "protected", "internal", "data", "sealed", "suspend", "null",
                    "true", "false", "this", "super", "new", "static", "void", "final",
                    "abstract", "extends", "implements", "try", "catch", "throw", "throws"
                )
                "python", "py" -> setOf(
                    "def", "class", "if", "elif", "else", "for", "while", "return", "import",
                    "from", "as", "with", "try", "except", "finally", "raise", "pass", "break",
                    "continue", "lambda", "yield", "None", "True", "False", "and", "or", "not",
                    "in", "is", "del", "global", "nonlocal", "async", "await"
                )
                "js", "javascript", "ts", "typescript" -> setOf(
                    "const", "let", "var", "function", "class", "if", "else", "for", "while",
                    "return", "import", "export", "from", "default", "new", "this", "typeof",
                    "instanceof", "null", "undefined", "true", "false", "async", "await",
                    "try", "catch", "throw", "interface", "type", "extends", "implements"
                )
                "c", "cpp", "c++" -> setOf(
                    "int", "float", "double", "char", "void", "bool", "if", "else", "for",
                    "while", "return", "include", "define", "struct", "class", "public",
                    "private", "protected", "new", "delete", "nullptr", "true", "false",
                    "const", "static", "auto", "template", "typename", "namespace", "using"
                )
                "sh", "bash", "shell" -> setOf(
                    "if", "then", "else", "elif", "fi", "for", "do", "done", "while",
                    "case", "esac", "function", "return", "export", "local", "echo",
                    "exit", "source", "in", "true", "false"
                )
                "html", "xml" -> setOf(
                    "div", "span", "p", "h1", "h2", "h3", "h4", "h5", "h6", "a", "img",
                    "ul", "ol", "li", "table", "tr", "td", "th", "form", "input", "button"
                )
                "css" -> setOf(
                    "color", "background", "margin", "padding", "border", "width", "height",
                    "display", "position", "float", "clear", "overflow", "z-index", "font",
                    "text", "line", "letter", "vertical", "align"
                )
                else -> emptySet()
            }

            val builtIns = when (lang.lowercase()) {
                "kotlin" -> setOf("println", "print", "readLine", "arrayListOf", "hashMapOf", "listOf", "mapOf", "setOf")
                "java" -> setOf("System", "out", "println", "ArrayList", "HashMap", "List", "Map", "Set")
                "python" -> setOf("print", "input", "len", "range", "list", "dict", "set", "str", "int", "float")
                "js", "javascript" -> setOf("console", "log", "document", "window", "Array", "Object", "String", "Number", "Boolean", "Function")
                else -> emptySet()
            }

            // 1. Comments (// … or # … or /* … */)
            val commentPatterns = listOf(
                Regex("//[^\\n]*"),
                Regex("#[^\\n]*"),
                Regex("/\\*[\\s\\S]*?\\*/")
            )
            for (pat in commentPatterns) {
                for (m in pat.findAll(code)) {
                    sb.setSpan(ForegroundColorSpan(synComment), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            // 2. Strings ("…" and '…')
            val stringPat = Regex("(\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"|'[^'\\\\]*(?:\\\\.[^'\\\\]*)*'|`[^`]*`)")
            for (m in stringPat.findAll(code)) {
                sb.setSpan(ForegroundColorSpan(synString), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            // 3. Numbers
            val numPat = Regex("\\b(0x[0-9a-fA-F]+|\\d+\\.?\\d*[fFdDlL]?)\\b")
            for (m in numPat.findAll(code)) {
                sb.setSpan(ForegroundColorSpan(synNumber), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            // 4. Keywords
            if (keywords.isNotEmpty()) {
                val kwPat = Regex("\\b(${keywords.joinToString("|")})\\b")
                for (m in kwPat.findAll(code)) {
                    sb.setSpan(ForegroundColorSpan(synKeyword), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            // 5. Built-in functions
            if (builtIns.isNotEmpty()) {
                val builtInPat = Regex("\\b(${builtIns.joinToString("|")})\\b")
                for (m in builtInPat.findAll(code)) {
                    sb.setSpan(ForegroundColorSpan(synBuiltIn), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            // 6. Type names (PascalCase identifiers)
            val typePat = Regex("\\b[A-Z][a-zA-Z0-9_]+\\b")
            for (m in typePat.findAll(code)) {
                sb.setSpan(ForegroundColorSpan(synType), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            // 7. Symbols (operators, brackets, etc.)
            val symbolPat = Regex("[{}()\\[\\];,.]")
            for (m in symbolPat.findAll(code)) {
                sb.setSpan(ForegroundColorSpan(synSymbol), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            return sb
        }

        private fun addTextBlock(text: String) {
            val tv = TextView(ctx).apply {
                this.text = buildRichText(text)
                textSize = 15f
                setTextColor(colorText)
                setLineSpacing(4f, 1.8f)
                setPadding(28, 22, 28, 22)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                // Use a readable font for better text experience
                typeface = Typeface.DEFAULT
                // Add subtle background for text blocks
                setBackgroundColor(adjustAlpha(colorSurface, 0.15f))
                // Add rounded corners
                 val bgDrawable = GradientDrawable().apply {
                     setColor(adjustAlpha(colorSurface, 0.15f))
                     setCornerRadius(14f)
                 }
                background = bgDrawable
            }
            codeBlockContainer?.addView(tv)
        }

        private fun buildRichText(text: String): SpannableStringBuilder {
            val sb = SpannableStringBuilder()
            val lines = text.split("\n")
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                when {
                    line.matches(Regex("^[-*_]{3,}\\s*$")) -> {
                        sb.append("\n──────────────────────────────────────────────\n")
                        val start = sb.length - 66
                        sb.setSpan(ForegroundColorSpan(colorDivider), start, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    line.trim().startsWith("\$\$") && line.trim().endsWith("\$\$") && line.trim().length > 4 ->
                        appendMath(sb, line.trim().removeSurrounding("\$\$").trim(), block = true)
                    line.startsWith("#### ") -> appendHeading(sb, line.removePrefix("#### "), 14f, colorPurple)
                    line.startsWith("### ")  -> appendHeading(sb, line.removePrefix("### "),  15f, colorAccent)
                    line.startsWith("## ")   -> appendHeading(sb, line.removePrefix("## "),   17f, colorAccent)
                    line.startsWith("# ")    -> appendHeading(sb, line.removePrefix("# "),    20f, colorAccent)
                    line.startsWith("|") && line.endsWith("|") -> {
                        if (!line.matches(Regex("^[|\\-: ]+$"))) appendTableRow(sb, line)
                    }
                    line.startsWith("> ") -> {
                        val content = line.removePrefix("> ")
                        val start = sb.length
                        sb.append("  ")
                        appendInline(sb, content)
                        sb.setSpan(QuoteSpan(colorAccent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(colorPurple), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    line.matches(Regex("^(\\s*)[-*+] .*")) -> {
                        val indent = line.length - line.trimStart().length
                        val bullet = if (indent >= 4) "      ◦ " else "  • "
                        val start = sb.length
                        sb.append(bullet)
                        sb.setSpan(ForegroundColorSpan(colorAccent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        appendInline(sb, line.trimStart().drop(2))
                    }
                    line.matches(Regex("^\\d+\\.\\s.*")) -> {
                        val dot = line.indexOf(". ")
                        val start = sb.length
                        sb.append("  ${line.substring(0, dot + 1)} ")
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(colorAccent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        appendInline(sb, line.substring(dot + 2))
                    }
                    line.contains(Regex("\\$[^$]+\\$")) -> appendLineWithInlineMath(sb, line)
                    line.isBlank() -> sb.append("\n")
                    else -> appendInline(sb, line)
                }
                if (i < lines.size - 1 && !line.isBlank()) sb.append("\n")
                i++
            }
            return sb
        }

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

        private fun appendMath(sb: SpannableStringBuilder, expr: String, block: Boolean) {
            val display = if (block) "\n  ∫ $expr\n" else " [$expr] "
            val start = sb.length
            sb.append(display)
            sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(colorPeach), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (block) sb.setSpan(AbsoluteSizeSpan(15, true), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
            val regex = Regex("(\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|~~(.+?)~~|`(.+?)`|\\$([^$]+)\\$)")
            var last = 0
            for (match in regex.findAll(text)) {
                if (match.range.first > last) sb.append(text.substring(last, match.range.first))
                val start = sb.length
                when {
                    match.value.startsWith("**") -> {
                        sb.append(match.groupValues[2])
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(colorAccent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
                        sb.setSpan(AbsoluteSizeSpan(13, true), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    match.value.startsWith("\$") -> appendMath(sb, match.groupValues[6], block = false)
                }
                last = match.range.last + 1
            }
            if (last < text.length) sb.append(text.substring(last))
        }

        private fun adjustAlpha(color: Int, factor: Float): Int {
            val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        }
    }
}