package com.beeta.nbheditor

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import ru.noties.jlatexmath.JLatexMathDrawable

object LatexMathEditor {

    private var mathFonts: List<Typeface> = emptyList()

    fun initialize(context: Context) {
        if (mathFonts.isNotEmpty()) return
        val fonts = mutableListOf<Typeface>()
        try {
            fonts.add(Typeface.createFromAsset(context.assets, "math_fonts/NotoSansMath.ttf"))
            fonts.add(Typeface.createFromAsset(context.assets, "math_fonts/NotoSansSymbols.ttf"))
            fonts.add(Typeface.createFromAsset(context.assets, "math_fonts/NotoSansSymbols2.ttf"))
        } catch (e: Exception) {
            android.util.Log.e("LatexMath", "Error loading fonts", e)
        }
        mathFonts = fonts
    }

    fun showLatexMathDialog(context: Context, onInsert: (String) -> Unit) {
        initialize(context)
        
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_latex_math, null)
        val latexInput = dialogView.findViewById<EditText>(R.id.latexInput)
        val latexPreview = dialogView.findViewById<ImageView>(R.id.latexPreview)
        val controlsContainer = dialogView.findViewById<LinearLayout>(R.id.controlsContainer)
        val categoryTabs = dialogView.findViewById<HorizontalScrollView>(R.id.categoryTabs)
        val tabsLayout = dialogView.findViewById<LinearLayout>(R.id.tabsLayout)
        
        var currentLatex = ""
        
        // Setup category tabs
        val categories = listOf(
            "Basic", "Fractions", "Exponents", "Roots", "Integrals", 
            "Sums", "Limits", "Matrices", "Greek", "Symbols"
        )
        
        categories.forEachIndexed { index, category ->
            val tab = Button(context).apply {
                text = category
                textSize = 14f
                setPadding(24, 12, 24, 12)
                background = if (index == 0) {
                    ContextCompat.getDrawable(context, R.drawable.bg_tab_selected)
                } else {
                    ContextCompat.getDrawable(context, R.drawable.bg_tab_unselected)
                }
                setTextColor(ContextCompat.getColor(context, R.color.editor_text))
                setOnClickListener {
                    // Update tab selection
                    for (i in 0 until tabsLayout.childCount) {
                        val btn = tabsLayout.getChildAt(i) as Button
                        btn.background = if (i == tabsLayout.indexOfChild(this)) {
                            ContextCompat.getDrawable(context, R.drawable.bg_tab_selected)
                        } else {
                            ContextCompat.getDrawable(context, R.drawable.bg_tab_unselected)
                        }
                    }
                    updateControls(context, controlsContainer, latexInput, category)
                }
            }
            tabsLayout.addView(tab)
        }
        
        // Load initial controls
        updateControls(context, controlsContainer, latexInput, categories[0])
        
        // Live LaTeX preview
        latexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentLatex = s?.toString() ?: ""
                updateLatexPreview(latexPreview, currentLatex)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        
        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<Button>(R.id.cancelButton).setOnClickListener {
            dialog.dismiss()
        }
        
        dialogView.findViewById<Button>(R.id.insertButton).setOnClickListener {
            if (currentLatex.isNotBlank()) {
                onInsert(currentLatex)
                dialog.dismiss()
            }
        }
        
        dialog.show()
    }

    private fun updateControls(context: Context, container: LinearLayout, input: EditText, category: String) {
        container.removeAllViews()
        
        when (category) {
            "Basic" -> addBasicControls(context, container, input)
            "Fractions" -> addFractionControls(context, container, input)
            "Exponents" -> addExponentControls(context, container, input)
            "Roots" -> addRootControls(context, container, input)
            "Integrals" -> addIntegralControls(context, container, input)
            "Sums" -> addSumControls(context, container, input)
            "Limits" -> addLimitControls(context, container, input)
            "Matrices" -> addMatrixControls(context, container, input)
            "Greek" -> addGreekControls(context, container, input)
            "Symbols" -> addSymbolControls(context, container, input)
        }
    }

    private fun addBasicControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "x²", "x^{2}", input)
        addControlButton(context, container, "xⁿ", "x^{n}", input)
        addControlButton(context, container, "x₂", "x_{2}", input)
        addControlButton(context, container, "xₙ", "x_{n}", input)
        addControlButton(context, container, "√", "\\sqrt{}", input, -1)
        addControlButton(context, container, "±", "\\pm", input)
        addControlButton(context, container, "×", "\\times", input)
        addControlButton(context, container, "÷", "\\div", input)
    }

    private fun addFractionControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "a/b", "\\frac{a}{b}", input)
        addControlButton(context, container, "∂/∂x", "\\frac{\\partial}{\\partial x}", input)
        addControlButton(context, container, "dy/dx", "\\frac{dy}{dx}", input)
        addControlButton(context, container, "d²y/dx²", "\\frac{d^2y}{dx^2}", input)
        addControlButton(context, container, "∂²/∂x²", "\\frac{\\partial^2}{\\partial x^2}", input)
    }

    private fun addExponentControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "eˣ", "e^{x}", input)
        addControlButton(context, container, "e^(x²)", "e^{x^2}", input)
        addControlButton(context, container, "10ˣ", "10^{x}", input)
        addControlButton(context, container, "aⁿ", "a^{n}", input)
        addControlButton(context, container, "x^(a+b)", "x^{a+b}", input)
    }

    private fun addRootControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "√x", "\\sqrt{x}", input)
        addControlButton(context, container, "∛x", "\\sqrt[3]{x}", input)
        addControlButton(context, container, "ⁿ√x", "\\sqrt[n]{x}", input)
        addControlButton(context, container, "√(x²+y²)", "\\sqrt{x^2+y^2}", input)
    }

    private fun addIntegralControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "∫ dx", "\\int dx", input)
        addControlButton(context, container, "∫₀¹", "\\int_{0}^{1}", input)
        addControlButton(context, container, "∫ₐᵇ", "\\int_{a}^{b}", input)
        addControlButton(context, container, "∫₋∞^∞", "\\int_{-\\infty}^{\\infty}", input)
        addControlButton(context, container, "∬", "\\iint", input)
        addControlButton(context, container, "∭", "\\iiint", input)
        addControlButton(context, container, "∮", "\\oint", input)
        addControlButton(context, container, "Custom ∫", showIntegralDialog = true, input = input)
    }

    private fun addSumControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "∑", "\\sum", input)
        addControlButton(context, container, "∑ᵢ₌₁ⁿ", "\\sum_{i=1}^{n}", input)
        addControlButton(context, container, "∑ₖ₌₀^∞", "\\sum_{k=0}^{\\infty}", input)
        addControlButton(context, container, "∏", "\\prod", input)
        addControlButton(context, container, "∏ᵢ₌₁ⁿ", "\\prod_{i=1}^{n}", input)
        addControlButton(context, container, "Custom ∑", showSumDialog = true, input = input)
    }

    private fun addLimitControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "lim", "\\lim", input)
        addControlButton(context, container, "lim(x→0)", "\\lim_{x \\to 0}", input)
        addControlButton(context, container, "lim(x→∞)", "\\lim_{x \\to \\infty}", input)
        addControlButton(context, container, "lim(n→∞)", "\\lim_{n \\to \\infty}", input)
        addControlButton(context, container, "Custom lim", showLimitDialog = true, input = input)
    }

    private fun addMatrixControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "2×2 Matrix", "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}", input)
        addControlButton(context, container, "3×3 Matrix", "\\begin{pmatrix} a & b & c \\\\ d & e & f \\\\ g & h & i \\end{pmatrix}", input)
        addControlButton(context, container, "Column Vector", "\\begin{pmatrix} x \\\\ y \\\\ z \\end{pmatrix}", input)
        addControlButton(context, container, "Determinant", "\\begin{vmatrix} a & b \\\\ c & d \\end{vmatrix}", input)
        addControlButton(context, container, "Custom Matrix", showMatrixDialog = true, input = input)
    }

    private fun addGreekControls(context: Context, container: LinearLayout, input: EditText) {
        val greekLetters = listOf(
            "α" to "\\alpha", "β" to "\\beta", "γ" to "\\gamma", "δ" to "\\delta",
            "ε" to "\\epsilon", "ζ" to "\\zeta", "η" to "\\eta", "θ" to "\\theta",
            "λ" to "\\lambda", "μ" to "\\mu", "π" to "\\pi", "σ" to "\\sigma",
            "τ" to "\\tau", "φ" to "\\phi", "ω" to "\\omega",
            "Γ" to "\\Gamma", "Δ" to "\\Delta", "Θ" to "\\Theta", "Λ" to "\\Lambda",
            "Σ" to "\\Sigma", "Φ" to "\\Phi", "Ω" to "\\Omega"
        )
        greekLetters.forEach { (display, latex) ->
            addControlButton(context, container, display, latex, input)
        }
    }

    private fun addSymbolControls(context: Context, container: LinearLayout, input: EditText) {
        val symbols = listOf(
            "∞" to "\\infty", "∂" to "\\partial", "∇" to "\\nabla",
            "≤" to "\\leq", "≥" to "\\geq", "≠" to "\\neq", "≈" to "\\approx",
            "∈" to "\\in", "∉" to "\\notin", "⊂" to "\\subset", "⊃" to "\\supset",
            "∪" to "\\cup", "∩" to "\\cap", "∅" to "\\emptyset",
            "→" to "\\rightarrow", "←" to "\\leftarrow", "⇒" to "\\Rightarrow", "⇐" to "\\Leftarrow"
        )
        symbols.forEach { (display, latex) ->
            addControlButton(context, container, display, latex, input)
        }
    }

    private fun addControlButton(
        context: Context,
        container: LinearLayout,
        label: String,
        latex: String = "",
        input: EditText,
        cursorOffset: Int = 0,
        showIntegralDialog: Boolean = false,
        showSumDialog: Boolean = false,
        showLimitDialog: Boolean = false,
        showMatrixDialog: Boolean = false
    ) {
        val button = Button(context).apply {
            text = label
            textSize = 16f
            setPadding(20, 12, 20, 12)
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
            setTextColor(ContextCompat.getColor(context, R.color.editor_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8
                bottomMargin = 8
            }
            
            setOnClickListener {
                when {
                    showIntegralDialog -> showCustomIntegralDialog(context, input)
                    showSumDialog -> showCustomSumDialog(context, input)
                    showLimitDialog -> showCustomLimitDialog(context, input)
                    showMatrixDialog -> showCustomMatrixDialog(context, input)
                    else -> {
                        val cursor = input.selectionStart
                        input.text.insert(cursor, latex)
                        if (cursorOffset != 0) {
                            input.setSelection(cursor + latex.length + cursorOffset)
                        }
                    }
                }
            }
        }
        container.addView(button)
    }

    private fun showCustomIntegralDialog(context: Context, input: EditText) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_custom_integral, null)
        val lowerLimit = dialogView.findViewById<EditText>(R.id.lowerLimit)
        val upperLimit = dialogView.findViewById<EditText>(R.id.upperLimit)
        val integrand = dialogView.findViewById<EditText>(R.id.integrand)
        val variable = dialogView.findViewById<EditText>(R.id.variable)
        
        AlertDialog.Builder(context)
            .setTitle("Custom Integral")
            .setView(dialogView)
            .setPositiveButton("Insert") { _, _ ->
                val lower = lowerLimit.text.toString()
                val upper = upperLimit.text.toString()
                val func = integrand.text.toString()
                val v = variable.text.toString().ifEmpty { "x" }
                
                val latex = if (lower.isNotEmpty() || upper.isNotEmpty()) {
                    "\\int_{$lower}^{$upper} $func \\, d$v"
                } else {
                    "\\int $func \\, d$v"
                }
                
                val cursor = input.selectionStart
                input.text.insert(cursor, latex)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomSumDialog(context: Context, input: EditText) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_custom_sum, null)
        val lowerLimit = dialogView.findViewById<EditText>(R.id.sumLowerLimit)
        val upperLimit = dialogView.findViewById<EditText>(R.id.sumUpperLimit)
        val expression = dialogView.findViewById<EditText>(R.id.sumExpression)
        
        AlertDialog.Builder(context)
            .setTitle("Custom Sum")
            .setView(dialogView)
            .setPositiveButton("Insert") { _, _ ->
                val lower = lowerLimit.text.toString()
                val upper = upperLimit.text.toString()
                val expr = expression.text.toString()
                
                val latex = if (lower.isNotEmpty() || upper.isNotEmpty()) {
                    "\\sum_{$lower}^{$upper} $expr"
                } else {
                    "\\sum $expr"
                }
                
                val cursor = input.selectionStart
                input.text.insert(cursor, latex)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomLimitDialog(context: Context, input: EditText) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_custom_limit, null)
        val variable = dialogView.findViewById<EditText>(R.id.limitVariable)
        val approaches = dialogView.findViewById<EditText>(R.id.limitApproaches)
        val expression = dialogView.findViewById<EditText>(R.id.limitExpression)
        
        AlertDialog.Builder(context)
            .setTitle("Custom Limit")
            .setView(dialogView)
            .setPositiveButton("Insert") { _, _ ->
                val v = variable.text.toString().ifEmpty { "x" }
                val app = approaches.text.toString().ifEmpty { "0" }
                val expr = expression.text.toString()
                
                val latex = "\\lim_{$v \\to $app} $expr"
                
                val cursor = input.selectionStart
                input.text.insert(cursor, latex)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomMatrixDialog(context: Context, input: EditText) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_custom_matrix, null)
        val rows = dialogView.findViewById<EditText>(R.id.matrixRows)
        val cols = dialogView.findViewById<EditText>(R.id.matrixCols)
        
        AlertDialog.Builder(context)
            .setTitle("Custom Matrix")
            .setView(dialogView)
            .setPositiveButton("Insert") { _, _ ->
                val r = rows.text.toString().toIntOrNull() ?: 2
                val c = cols.text.toString().toIntOrNull() ?: 2
                
                val matrixContent = StringBuilder()
                for (i in 0 until r) {
                    for (j in 0 until c) {
                        matrixContent.append("a_{${i+1}${j+1}}")
                        if (j < c - 1) matrixContent.append(" & ")
                    }
                    if (i < r - 1) matrixContent.append(" \\\\ ")
                }
                
                val latex = "\\begin{pmatrix} $matrixContent \\end{pmatrix}"
                
                val cursor = input.selectionStart
                input.text.insert(cursor, latex)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateLatexPreview(imageView: ImageView, latex: String) {
        try {
            if (latex.isBlank()) {
                imageView.setImageDrawable(null)
                return
            }
            
            val drawable = JLatexMathDrawable.builder(latex)
                .textSize(60f)
                .padding(8)
                .background(0x00000000)
                .align(JLatexMathDrawable.ALIGN_LEFT)
                .build()
            
            imageView.setImageDrawable(drawable)
        } catch (e: Exception) {
            android.util.Log.e("LatexMath", "Preview error", e)
        }
    }
}
