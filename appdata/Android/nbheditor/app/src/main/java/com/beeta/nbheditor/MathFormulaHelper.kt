package com.beeta.nbheditor

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import ru.noties.jlatexmath.JLatexMathDrawable

object MathFormulaHelper {
    
    private var mathFonts: List<Typeface> = emptyList()
    
    fun initialize(context: Context) {
        if (mathFonts.isNotEmpty()) return
        
        val fonts = mutableListOf<Typeface>()
        try {
            fonts.add(Typeface.createFromAsset(context.assets, "math_fonts/NotoSansMath.ttf"))
            fonts.add(Typeface.createFromAsset(context.assets, "math_fonts/NotoSansSymbols.ttf"))
            fonts.add(Typeface.createFromAsset(context.assets, "math_fonts/NotoSansSymbols2.ttf"))
        } catch (e: Exception) {
            android.util.Log.e("MathFormula", "Error loading math fonts", e)
        }
        mathFonts = fonts
    }
    
    fun showMathFormulaDialog(context: Context, onInsert: (String) -> Unit) {
        initialize(context)
        
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_math_formula, null)
        val mathInput = dialogView.findViewById<EditText>(R.id.mathInput)
        val mathPreview = dialogView.findViewById<ImageView>(R.id.mathPreview)
        val controlsContainer = dialogView.findViewById<LinearLayout>(R.id.controlsContainer)
        val categoryTabs = dialogView.findViewById<android.widget.HorizontalScrollView>(R.id.categoryTabs)
        val tabsLayout = dialogView.findViewById<LinearLayout>(R.id.tabsLayout)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)
        val insertButton = dialogView.findViewById<Button>(R.id.insertButton)
        
        var currentLatex = ""
        
        // Setup category tabs
        val categories = listOf(
            "Basic", "Fractions", "Exponents", "Roots", "Calculus",
            "Trigonometry", "Geometry", "Algebra", "Functions",
            "Matrices", "Greek", "Symbols", "Logic", "Sets"
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
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 8
                }
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
                    updateControls(context, controlsContainer, mathInput, category)
                }
            }
            tabsLayout.addView(tab)
        }
        
        // Load initial controls
        updateControls(context, controlsContainer, mathInput, categories[0])
        
        // Live LaTeX preview
        mathInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentLatex = s?.toString() ?: ""
                updateLatexPreview(mathPreview, currentLatex)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        
        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()
        
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        
        insertButton.setOnClickListener {
            val formula = mathInput.text.toString()
            if (formula.isNotBlank()) {
                onInsert(formula)
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
            "Calculus" -> addCalculusControls(context, container, input)
            "Trigonometry" -> addTrigonometryControls(context, container, input)
            "Geometry" -> addGeometryControls(context, container, input)
            "Algebra" -> addAlgebraControls(context, container, input)
            "Functions" -> addFunctionControls(context, container, input)
            "Matrices" -> addMatrixControls(context, container, input)
            "Greek" -> addGreekControls(context, container, input)
            "Symbols" -> addSymbolControls(context, container, input)
            "Logic" -> addLogicControls(context, container, input)
            "Sets" -> addSetControls(context, container, input)
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
        addControlButton(context, container, "=", "=", input)
        addControlButton(context, container, "≠", "\\neq", input)
        addControlButton(context, container, "≈", "\\approx", input)
        addControlButton(context, container, "<", "<", input)
        addControlButton(context, container, ">", ">", input)
        addControlButton(context, container, "≤", "\\leq", input)
        addControlButton(context, container, "≥", "\\geq", input)
    }

    private fun addFractionControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "a/b", "\\frac{a}{b}", input)
        addControlButton(context, container, "∂/∂x", "\\frac{\\partial}{\\partial x}", input)
        addControlButton(context, container, "dy/dx", "\\frac{dy}{dx}", input)
        addControlButton(context, container, "d²y/dx²", "\\frac{d^2y}{dx^2}", input)
        addControlButton(context, container, "∂²/∂x²", "\\frac{\\partial^2}{\\partial x^2}", input)
        addControlButton(context, container, "Custom", showCustomDialog = "fraction", input = input)
    }

    private fun addExponentControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "eˣ", "e^{x}", input)
        addControlButton(context, container, "e^(x²)", "e^{x^2}", input)
        addControlButton(context, container, "10ˣ", "10^{x}", input)
        addControlButton(context, container, "aⁿ", "a^{n}", input)
        addControlButton(context, container, "x^(a+b)", "x^{a+b}", input)
        addControlButton(context, container, "2ˣ", "2^{x}", input)
    }

    private fun addRootControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "√x", "\\sqrt{x}", input)
        addControlButton(context, container, "∛x", "\\sqrt[3]{x}", input)
        addControlButton(context, container, "ⁿ√x", "\\sqrt[n]{x}", input)
        addControlButton(context, container, "√(x²+y²)", "\\sqrt{x^2+y^2}", input)
        addControlButton(context, container, "Custom √", showCustomDialog = "root", input = input)
    }

    private fun addCalculusControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "∫ dx", "\\int dx", input)
        addControlButton(context, container, "∫₀¹", "\\int_{0}^{1}", input)
        addControlButton(context, container, "∫ₐᵇ", "\\int_{a}^{b}", input)
        addControlButton(context, container, "∫₋∞^∞", "\\int_{-\\infty}^{\\infty}", input)
        addControlButton(context, container, "∬", "\\iint", input)
        addControlButton(context, container, "∭", "\\iiint", input)
        addControlButton(context, container, "∮", "\\oint", input)
        addControlButton(context, container, "Custom ∫", showCustomDialog = "integral", input = input)
        addControlButton(context, container, "∑", "\\sum", input)
        addControlButton(context, container, "∑ᵢ₌₁ⁿ", "\\sum_{i=1}^{n}", input)
        addControlButton(context, container, "∑ₖ₌₀^∞", "\\sum_{k=0}^{\\infty}", input)
        addControlButton(context, container, "Custom ∑", showCustomDialog = "sum", input = input)
        addControlButton(context, container, "∏", "\\prod", input)
        addControlButton(context, container, "∏ᵢ₌₁ⁿ", "\\prod_{i=1}^{n}", input)
        addControlButton(context, container, "lim", "\\lim", input)
        addControlButton(context, container, "lim(x→0)", "\\lim_{x \\to 0}", input)
        addControlButton(context, container, "lim(x→∞)", "\\lim_{x \\to \\infty}", input)
        addControlButton(context, container, "Custom lim", showCustomDialog = "limit", input = input)
        addControlButton(context, container, "∂", "\\partial", input)
        addControlButton(context, container, "∇", "\\nabla", input)
    }

    private fun addTrigonometryControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "sin", "\\sin", input)
        addControlButton(context, container, "cos", "\\cos", input)
        addControlButton(context, container, "tan", "\\tan", input)
        addControlButton(context, container, "cot", "\\cot", input)
        addControlButton(context, container, "sec", "\\sec", input)
        addControlButton(context, container, "csc", "\\csc", input)
        addControlButton(context, container, "sin⁻¹", "\\sin^{-1}", input)
        addControlButton(context, container, "cos⁻¹", "\\cos^{-1}", input)
        addControlButton(context, container, "tan⁻¹", "\\tan^{-1}", input)
        addControlButton(context, container, "sinh", "\\sinh", input)
        addControlButton(context, container, "cosh", "\\cosh", input)
        addControlButton(context, container, "tanh", "\\tanh", input)
        addControlButton(context, container, "arcsin", "\\arcsin", input)
        addControlButton(context, container, "arccos", "\\arccos", input)
        addControlButton(context, container, "arctan", "\\arctan", input)
    }

    private fun addGeometryControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "∠", "\\angle", input)
        addControlButton(context, container, "°", "^\\circ", input)
        addControlButton(context, container, "△", "\\triangle", input)
        addControlButton(context, container, "⊥", "\\perp", input)
        addControlButton(context, container, "∥", "\\parallel", input)
        addControlButton(context, container, "≅", "\\cong", input)
        addControlButton(context, container, "∼", "\\sim", input)
        addControlButton(context, container, "Area", "A = ", input)
        addControlButton(context, container, "Perimeter", "P = ", input)
        addControlButton(context, container, "Circle", "\\pi r^2", input)
        addControlButton(context, container, "Sphere", "\\frac{4}{3}\\pi r^3", input)
        addControlButton(context, container, "Cylinder", "\\pi r^2 h", input)
    }

    private fun addAlgebraControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "(a+b)²", "(a+b)^2 = a^2 + 2ab + b^2", input)
        addControlButton(context, container, "(a-b)²", "(a-b)^2 = a^2 - 2ab + b^2", input)
        addControlButton(context, container, "a²-b²", "a^2 - b^2 = (a+b)(a-b)", input)
        addControlButton(context, container, "Quadratic", "x = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}", input)
        addControlButton(context, container, "System", "\\begin{cases} a_1x + b_1y = c_1 \\\\ a_2x + b_2y = c_2 \\end{cases}", input)
        addControlButton(context, container, "Inequality", "ax + b < c", input)
    }

    private fun addFunctionControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "f(x)", "f(x) = ", input)
        addControlButton(context, container, "g(x)", "g(x) = ", input)
        addControlButton(context, container, "f∘g", "(f \\circ g)(x)", input)
        addControlButton(context, container, "f⁻¹", "f^{-1}(x)", input)
        addControlButton(context, container, "exp", "\\exp", input)
        addControlButton(context, container, "ln", "\\ln", input)
        addControlButton(context, container, "log", "\\log", input)
        addControlButton(context, container, "log₂", "\\log_2", input)
        addControlButton(context, container, "log₁₀", "\\log_{10}", input)
        addControlButton(context, container, "max", "\\max", input)
        addControlButton(context, container, "min", "\\min", input)
        addControlButton(context, container, "abs", "|x|", input)
        addControlButton(context, container, "floor", "\\lfloor x \\rfloor", input)
        addControlButton(context, container, "ceil", "\\lceil x \\rceil", input)
    }

    private fun addMatrixControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "2×2", "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}", input)
        addControlButton(context, container, "3×3", "\\begin{pmatrix} a & b & c \\\\ d & e & f \\\\ g & h & i \\end{pmatrix}", input)
        addControlButton(context, container, "Vector", "\\begin{pmatrix} x \\\\ y \\\\ z \\end{pmatrix}", input)
        addControlButton(context, container, "Det", "\\begin{vmatrix} a & b \\\\ c & d \\end{vmatrix}", input)
        addControlButton(context, container, "Custom", showCustomDialog = "matrix", input = input)
        addControlButton(context, container, "Transpose", "A^T", input)
        addControlButton(context, container, "Inverse", "A^{-1}", input)
        addControlButton(context, container, "Identity", "I", input)
    }

    private fun addGreekControls(context: Context, container: LinearLayout, input: EditText) {
        val greekLetters = listOf(
            "α" to "\\alpha", "β" to "\\beta", "γ" to "\\gamma", "δ" to "\\delta",
            "ε" to "\\epsilon", "ζ" to "\\zeta", "η" to "\\eta", "θ" to "\\theta",
            "λ" to "\\lambda", "μ" to "\\mu", "π" to "\\pi", "σ" to "\\sigma",
            "τ" to "\\tau", "φ" to "\\phi", "ω" to "\\omega", "ρ" to "\\rho",
            "Γ" to "\\Gamma", "Δ" to "\\Delta", "Θ" to "\\Theta", "Λ" to "\\Lambda",
            "Σ" to "\\Sigma", "Φ" to "\\Phi", "Ω" to "\\Omega", "Π" to "\\Pi"
        )
        greekLetters.forEach { (display, latex) ->
            addControlButton(context, container, display, latex, input)
        }
    }

    private fun addSymbolControls(context: Context, container: LinearLayout, input: EditText) {
        val symbols = listOf(
            "∞" to "\\infty", "∂" to "\\partial", "∇" to "\\nabla",
            "→" to "\\rightarrow", "←" to "\\leftarrow", "⇒" to "\\Rightarrow",
            "⇐" to "\\Leftarrow", "↔" to "\\leftrightarrow", "⇔" to "\\Leftrightarrow",
            "∀" to "\\forall", "∃" to "\\exists", "∄" to "\\nexists",
            "ℕ" to "\\mathbb{N}", "ℤ" to "\\mathbb{Z}", "ℚ" to "\\mathbb{Q}",
            "ℝ" to "\\mathbb{R}", "ℂ" to "\\mathbb{C}"
        )
        symbols.forEach { (display, latex) ->
            addControlButton(context, container, display, latex, input)
        }
    }

    private fun addLogicControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "∧", "\\land", input)
        addControlButton(context, container, "∨", "\\lor", input)
        addControlButton(context, container, "¬", "\\neg", input)
        addControlButton(context, container, "⇒", "\\Rightarrow", input)
        addControlButton(context, container, "⇔", "\\Leftrightarrow", input)
        addControlButton(context, container, "∴", "\\therefore", input)
        addControlButton(context, container, "∵", "\\because", input)
        addControlButton(context, container, "⊤", "\\top", input)
        addControlButton(context, container, "⊥", "\\bot", input)
    }

    private fun addSetControls(context: Context, container: LinearLayout, input: EditText) {
        addControlButton(context, container, "∈", "\\in", input)
        addControlButton(context, container, "∉", "\\notin", input)
        addControlButton(context, container, "⊂", "\\subset", input)
        addControlButton(context, container, "⊃", "\\supset", input)
        addControlButton(context, container, "⊆", "\\subseteq", input)
        addControlButton(context, container, "⊇", "\\supseteq", input)
        addControlButton(context, container, "∪", "\\cup", input)
        addControlButton(context, container, "∩", "\\cap", input)
        addControlButton(context, container, "∅", "\\emptyset", input)
        addControlButton(context, container, "∖", "\\setminus", input)
        addControlButton(context, container, "×", "\\times", input)
    }


    private fun addControlButton(
        context: Context,
        container: LinearLayout,
        label: String,
        latex: String = "",
        input: EditText,
        cursorOffset: Int = 0,
        showCustomDialog: String = ""
    ) {
        val button = Button(context).apply {
            text = label
            textSize = 14f
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
                when (showCustomDialog) {
                    "integral" -> showCustomIntegralDialog(context, input)
                    "sum" -> showCustomSumDialog(context, input)
                    "limit" -> showCustomLimitDialog(context, input)
                    "matrix" -> showCustomMatrixDialog(context, input)
                    "fraction" -> showCustomFractionDialog(context, input)
                    "root" -> showCustomRootDialog(context, input)
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

    private fun showCustomFractionDialog(context: Context, input: EditText) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_custom_fraction, null)
        val numerator = dialogView.findViewById<EditText>(R.id.numerator)
        val denominator = dialogView.findViewById<EditText>(R.id.denominator)
        
        AlertDialog.Builder(context)
            .setTitle("Custom Fraction")
            .setView(dialogView)
            .setPositiveButton("Insert") { _, _ ->
                val num = numerator.text.toString()
                val den = denominator.text.toString()
                
                val latex = "\\frac{$num}{$den}"
                
                val cursor = input.selectionStart
                input.text.insert(cursor, latex)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomRootDialog(context: Context, input: EditText) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_custom_root, null)
        val index = dialogView.findViewById<EditText>(R.id.rootIndex)
        val radicand = dialogView.findViewById<EditText>(R.id.radicand)
        
        AlertDialog.Builder(context)
            .setTitle("Custom Root")
            .setView(dialogView)
            .setPositiveButton("Insert") { _, _ ->
                val idx = index.text.toString()
                val rad = radicand.text.toString()
                
                val latex = if (idx.isNotEmpty() && idx != "2") {
                    "\\sqrt[$idx]{$rad}"
                } else {
                    "\\sqrt{$rad}"
                }
                
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
            android.util.Log.e("MathFormula", "LaTeX preview error", e)
        }
    }
}
