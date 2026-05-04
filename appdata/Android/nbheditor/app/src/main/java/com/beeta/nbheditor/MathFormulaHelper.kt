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
    private var currentLatex = ""
    
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
        val mathPreview = dialogView.findViewById<ImageView>(R.id.mathPreview)
        val controlsContainer = dialogView.findViewById<LinearLayout>(R.id.controlsContainer)
        val categoryTabs = dialogView.findViewById<android.widget.HorizontalScrollView>(R.id.categoryTabs)
        val tabsLayout = dialogView.findViewById<LinearLayout>(R.id.tabsLayout)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)
        val insertButton = dialogView.findViewById<Button>(R.id.insertButton)
        val clearButton = dialogView.findViewById<Button>(R.id.clearButton)
        
        currentLatex = ""
        
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
                    updateControls(context, controlsContainer, mathPreview, category)
                }
            }
            tabsLayout.addView(tab)
        }
        
        // Load initial controls
        updateControls(context, controlsContainer, mathPreview, categories[0])
        
        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()
        
        clearButton.setOnClickListener {
            currentLatex = ""
            updateLatexPreview(mathPreview, currentLatex)
        }
        
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        
        insertButton.setOnClickListener {
            if (currentLatex.isNotBlank()) {
                onInsert(currentLatex)
                dialog.dismiss()
            }
        }
        
        dialog.show()
    }
    
    private fun updateControls(context: Context, container: LinearLayout, preview: ImageView, category: String) {
        container.removeAllViews()
        
        when (category) {
            "Basic" -> addBasicControls(context, container, preview)
            "Fractions" -> addFractionControls(context, container, preview)
            "Exponents" -> addExponentControls(context, container, preview)
            "Roots" -> addRootControls(context, container, preview)
            "Calculus" -> addCalculusControls(context, container, preview)
            "Trigonometry" -> addTrigonometryControls(context, container, preview)
            "Geometry" -> addGeometryControls(context, container, preview)
            "Algebra" -> addAlgebraControls(context, container, preview)
            "Functions" -> addFunctionControls(context, container, preview)
            "Matrices" -> addMatrixControls(context, container, preview)
            "Greek" -> addGreekControls(context, container, preview)
            "Symbols" -> addSymbolControls(context, container, preview)
            "Logic" -> addLogicControls(context, container, preview)
            "Sets" -> addSetControls(context, container, preview)
        }
    }

    private fun appendToFormula(latex: String, preview: ImageView) {
        currentLatex += latex
        updateLatexPreview(preview, currentLatex)
    }

    private fun addBasicControls(context: Context, container: LinearLayout, preview: ImageView) {
        addControlButton(context, container, "x²", "x^{2}", preview)
        addControlButton(context, container, "xⁿ", "x^{n}", preview)
        addControlButton(context, container, "x₂", "x_{2}", preview)
        addControlButton(context, container, "xₙ", "x_{n}", preview)
        addControlButton(context, container, "√", "\\sqrt{x}", preview)
        addControlButton(context, container, "±", "\\pm ", preview)
        addControlButton(context, container, "×", "\\times ", preview)
        addControlButton(context, container, "÷", "\\div ", preview)
        addControlButton(context, container, "=", "=", preview)
        addControlButton(context, container, "≠", "\\neq ", preview)
        addControlButton(context, container, "≈", "\\approx ", preview)
        addControlButton(context, container, "<", "<", preview)
        addControlButton(context, container, ">", ">", preview)
        addControlButton(context, container, "≤", "\\leq ", preview)
        addControlButton(context, container, "≥", "\\geq ", preview)
    }

    private fun addFractionControls(context: Context, container: LinearLayout, preview: ImageView) {
        addControlButton(context, container, "Simple Fraction", showWizard = "fraction", preview = preview)
        addControlButton(context, container, "∂/∂x", showWizard = "partial", preview = preview)
        addControlButton(context, container, "dy/dx", showWizard = "derivative", preview = preview)
    }

    private fun addExponentControls(context: Context, container: LinearLayout, preview: ImageView) {
        addControlButton(context, container, "eˣ", "e^{x}", preview)
        addControlButton(context, container, "10ˣ", "10^{x}", preview)
        addControlButton(context, container, "Custom Power", showWizard = "power", preview = preview)
    }

    private fun addRootControls(context: Context, container: LinearLayout, preview: ImageView) {
        addControlButton(context, container, "√x", "\\sqrt{x}", preview)
        addControlButton(context, container, "∛x", "\\sqrt[3]{x}", preview)
        addControlButton(context, container, "Custom Root", showWizard = "root", preview = preview)
    }

    private fun addCalculusControls(context: Context, container: LinearLayout, preview: ImageView) {
        addControlButton(context, container, "∫ Integral", showWizard = "integral", preview = preview)
        addControlButton(context, container, "∑ Sum", showWizard = "sum", preview = preview)
        addControlButton(context, container, "lim Limit", showWizard = "limit", preview = preview)
        addControlButton(context, container, "∂", "\\partial ", preview)
        addControlButton(context, container, "∇", "\\nabla ", preview)
    }

    private fun addTrigonometryControls(context: Context, container: LinearLayout, preview: ImageView) {
        addControlButton(context, container, "sin", "\\sin(", preview)
        addControlButton(context, container, "cos", "\\cos(", preview)
        addControlButton(context, container, "tan", "\\tan(", preview)
        addControlButton(context, container, "cot", "\\cot(", preview)
        addControlButton(context, container, "sec", "\\sec(", preview)
        addControlButton(context, container, "csc", "\\csc(", preview)
        addControlButton(context, container, ")", ")", preview)
    }

    private fun addGeometryControls(context: Context, container: LinearLayout, preview: ImageView) {
        addControlButton(context, container, "∠", "\\angle ", preview)
        addControlButton(context, container, "°", "^\\circ", preview)
        addControlButton(context, container, "△", "\\triangle ", preview)
        addControlButton(context, container, "⊥", "\\perp ", preview)
        addControlButton(context, container, "∥", "\\parallel ", preview)
    }

    private fun addAlgebraControls(context: Context, container: LinearLayout, preview: ImageView) {
        addControlButton(context, container, "Quadratic Formula", showWizard = "quadratic", preview = preview)
        addControlButton(context, container, "System of Equations", showWizard = "system", preview = preview)
    }

    private fun addFunctionControls(context: Context, container: LinearLayout, preview: ImageView) {
        addControlButton(context, container, "f(x)=", "f(x)=", preview)
        addControlButton(context, container, "ln", "\\ln(", preview)
        addControlButton(context, container, "log", "\\log(", preview)
        addControlButton(context, container, ")", ")", preview)
    }

    private fun addMatrixControls(context: Context, container: LinearLayout, preview: ImageView) {
        addControlButton(context, container, "2×2 Matrix", showWizard = "matrix", preview = preview)
    }

    private fun addGreekControls(context: Context, container: LinearLayout, preview: ImageView) {
        val greekLetters = listOf(
            "α" to "\\alpha ", "β" to "\\beta ", "γ" to "\\gamma ", "δ" to "\\delta ",
            "ε" to "\\epsilon ", "θ" to "\\theta ", "λ" to "\\lambda ", "μ" to "\\mu ",
            "π" to "\\pi ", "σ" to "\\sigma ", "τ" to "\\tau ", "φ" to "\\phi ", "ω" to "\\omega "
        )
        greekLetters.forEach { (display, latex) ->
            addControlButton(context, container, display, latex, preview)
        }
    }

    private fun addSymbolControls(context: Context, container: LinearLayout, preview: ImageView) {
        val symbols = listOf(
            "∞" to "\\infty ", "→" to "\\rightarrow ", "←" to "\\leftarrow ",
            "∀" to "\\forall ", "∃" to "\\exists "
        )
        symbols.forEach { (display, latex) ->
            addControlButton(context, container, display, latex, preview)
        }
    }

    private fun addLogicControls(context: Context, container: LinearLayout, preview: ImageView) {
        addControlButton(context, container, "∧", "\\land ", preview)
        addControlButton(context, container, "∨", "\\lor ", preview)
        addControlButton(context, container, "¬", "\\neg ", preview)
    }

    private fun addSetControls(context: Context, container: LinearLayout, preview: ImageView) {
        addControlButton(context, container, "∈", "\\in ", preview)
        addControlButton(context, container, "∉", "\\notin ", preview)
        addControlButton(context, container, "∪", "\\cup ", preview)
        addControlButton(context, container, "∩", "\\cap ", preview)
        addControlButton(context, container, "∅", "\\emptyset ", preview)
    }


    private fun addControlButton(
        context: Context,
        container: LinearLayout,
        label: String,
        latex: String = "",
        preview: ImageView? = null,
        showWizard: String = ""
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
                when (showWizard) {
                    "integral" -> showIntegralWizard(context, preview!!)
                    "sum" -> showSumWizard(context, preview!!)
                    "limit" -> showLimitWizard(context, preview!!)
                    "fraction" -> showFractionWizard(context, preview!!)
                    "partial" -> showPartialDerivativeWizard(context, preview!!)
                    "derivative" -> showDerivativeWizard(context, preview!!)
                    "power" -> showPowerWizard(context, preview!!)
                    "root" -> showRootWizard(context, preview!!)
                    "matrix" -> showMatrixWizard(context, preview!!)
                    "quadratic" -> appendToFormula("x = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}", preview!!)
                    "system" -> appendToFormula("\\begin{cases} a_1x + b_1y = c_1 \\\\ a_2x + b_2y = c_2 \\end{cases}", preview!!)
                    else -> {
                        if (preview != null && latex.isNotEmpty()) {
                            appendToFormula(latex, preview)
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


    // ═══════════════════════════════════════════════════════════════════════════
    // STEP-BY-STEP WIZARDS - No LaTeX code visible to user
    // ═══════════════════════════════════════════════════════════════════════════

    private fun showIntegralWizard(context: Context, preview: ImageView) {
        // Step 1: Ask for function
        val step1View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input1 = step1View.findViewById<EditText>(R.id.wizardInput)
        val title1 = step1View.findViewById<TextView>(R.id.wizardTitle)
        val hint1 = step1View.findViewById<TextView>(R.id.wizardHint)
        
        title1.text = "Step 1: Enter the function f(x)"
        hint1.text = "Example: x^2, sin(x), e^x"
        input1.hint = "Enter function..."
        
        AlertDialog.Builder(context)
            .setView(step1View)
            .setPositiveButton("Next") { _, _ ->
                val function = input1.text.toString()
                if (function.isNotEmpty()) {
                    showIntegralStep2(context, preview, function)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showIntegralStep2(context: Context, preview: ImageView, function: String) {
        // Step 2: Ask for lower limit
        val step2View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input2 = step2View.findViewById<EditText>(R.id.wizardInput)
        val title2 = step2View.findViewById<TextView>(R.id.wizardTitle)
        val hint2 = step2View.findViewById<TextView>(R.id.wizardHint)
        
        title2.text = "Step 2: Enter lower limit (optional)"
        hint2.text = "Leave empty for indefinite integral\nExamples: 0, a, -\\infty"
        input2.hint = "Lower limit..."
        
        AlertDialog.Builder(context)
            .setView(step2View)
            .setPositiveButton("Next") { _, _ ->
                val lower = input2.text.toString()
                showIntegralStep3(context, preview, function, lower)
            }
            .setNegativeButton("Back") { _, _ ->
                showIntegralWizard(context, preview)
            }
            .show()
    }
    
    private fun showIntegralStep3(context: Context, preview: ImageView, function: String, lower: String) {
        // Step 3: Ask for upper limit
        val step3View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input3 = step3View.findViewById<EditText>(R.id.wizardInput)
        val title3 = step3View.findViewById<TextView>(R.id.wizardTitle)
        val hint3 = step3View.findViewById<TextView>(R.id.wizardHint)
        
        title3.text = "Step 3: Enter upper limit (optional)"
        hint3.text = "Leave empty for indefinite integral\nExamples: 1, b, \\infty"
        input3.hint = "Upper limit..."
        
        AlertDialog.Builder(context)
            .setView(step3View)
            .setPositiveButton("Next") { _, _ ->
                val upper = input3.text.toString()
                showIntegralStep4(context, preview, function, lower, upper)
            }
            .setNegativeButton("Back") { _, _ ->
                showIntegralStep2(context, preview, function)
            }
            .show()
    }
    
    private fun showIntegralStep4(context: Context, preview: ImageView, function: String, lower: String, upper: String) {
        // Step 4: Ask for variable
        val step4View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input4 = step4View.findViewById<EditText>(R.id.wizardInput)
        val title4 = step4View.findViewById<TextView>(R.id.wizardTitle)
        val hint4 = step4View.findViewById<TextView>(R.id.wizardHint)
        
        title4.text = "Step 4: Enter variable of integration"
        hint4.text = "Usually 'x', 't', 'u', etc."
        input4.hint = "Variable..."
        input4.setText("x")
        
        AlertDialog.Builder(context)
            .setView(step4View)
            .setPositiveButton("Done") { _, _ ->
                val variable = input4.text.toString().ifEmpty { "x" }
                
                // Generate LaTeX (user never sees this!)
                val latex = if (lower.isNotEmpty() || upper.isNotEmpty()) {
                    "\\int_{$lower}^{$upper} $function \\, d$variable"
                } else {
                    "\\int $function \\, d$variable"
                }
                
                appendToFormula(latex, preview)
            }
            .setNegativeButton("Back") { _, _ ->
                showIntegralStep3(context, preview, function, lower)
            }
            .show()
    }

    private fun showSumWizard(context: Context, preview: ImageView) {
        val step1View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input1 = step1View.findViewById<EditText>(R.id.wizardInput)
        val title1 = step1View.findViewById<TextView>(R.id.wizardTitle)
        val hint1 = step1View.findViewById<TextView>(R.id.wizardHint)
        
        title1.text = "Step 1: Enter the expression"
        hint1.text = "Example: i^2, a_i, 1/n"
        input1.hint = "Expression..."
        
        AlertDialog.Builder(context)
            .setView(step1View)
            .setPositiveButton("Next") { _, _ ->
                val expression = input1.text.toString()
                if (expression.isNotEmpty()) {
                    showSumStep2(context, preview, expression)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showSumStep2(context: Context, preview: ImageView, expression: String) {
        val step2View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input2 = step2View.findViewById<EditText>(R.id.wizardInput)
        val title2 = step2View.findViewById<TextView>(R.id.wizardTitle)
        val hint2 = step2View.findViewById<TextView>(R.id.wizardHint)
        
        title2.text = "Step 2: Enter lower limit"
        hint2.text = "Example: i=1, k=0, n=1"
        input2.hint = "Lower limit..."
        
        AlertDialog.Builder(context)
            .setView(step2View)
            .setPositiveButton("Next") { _, _ ->
                val lower = input2.text.toString()
                showSumStep3(context, preview, expression, lower)
            }
            .setNegativeButton("Back") { _, _ ->
                showSumWizard(context, preview)
            }
            .show()
    }
    
    private fun showSumStep3(context: Context, preview: ImageView, expression: String, lower: String) {
        val step3View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input3 = step3View.findViewById<EditText>(R.id.wizardInput)
        val title3 = step3View.findViewById<TextView>(R.id.wizardTitle)
        val hint3 = step3View.findViewById<TextView>(R.id.wizardHint)
        
        title3.text = "Step 3: Enter upper limit"
        hint3.text = "Example: n, \\infty, 100"
        input3.hint = "Upper limit..."
        
        AlertDialog.Builder(context)
            .setView(step3View)
            .setPositiveButton("Done") { _, _ ->
                val upper = input3.text.toString()
                val latex = "\\sum_{$lower}^{$upper} $expression"
                appendToFormula(latex, preview)
            }
            .setNegativeButton("Back") { _, _ ->
                showSumStep2(context, preview, expression)
            }
            .show()
    }

    private fun showLimitWizard(context: Context, preview: ImageView) {
        val step1View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input1 = step1View.findViewById<EditText>(R.id.wizardInput)
        val title1 = step1View.findViewById<TextView>(R.id.wizardTitle)
        val hint1 = step1View.findViewById<TextView>(R.id.wizardHint)
        
        title1.text = "Step 1: Enter the expression"
        hint1.text = "Example: (sin(x))/x, x^2"
        input1.hint = "Expression..."
        
        AlertDialog.Builder(context)
            .setView(step1View)
            .setPositiveButton("Next") { _, _ ->
                val expression = input1.text.toString()
                if (expression.isNotEmpty()) {
                    showLimitStep2(context, preview, expression)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showLimitStep2(context: Context, preview: ImageView, expression: String) {
        val step2View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input2 = step2View.findViewById<EditText>(R.id.wizardInput)
        val title2 = step2View.findViewById<TextView>(R.id.wizardTitle)
        val hint2 = step2View.findViewById<TextView>(R.id.wizardHint)
        
        title2.text = "Step 2: Enter variable"
        hint2.text = "Usually 'x', 'n', 't', etc."
        input2.hint = "Variable..."
        input2.setText("x")
        
        AlertDialog.Builder(context)
            .setView(step2View)
            .setPositiveButton("Next") { _, _ ->
                val variable = input2.text.toString().ifEmpty { "x" }
                showLimitStep3(context, preview, expression, variable)
            }
            .setNegativeButton("Back") { _, _ ->
                showLimitWizard(context, preview)
            }
            .show()
    }
    
    private fun showLimitStep3(context: Context, preview: ImageView, expression: String, variable: String) {
        val step3View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input3 = step3View.findViewById<EditText>(R.id.wizardInput)
        val title3 = step3View.findViewById<TextView>(R.id.wizardTitle)
        val hint3 = step3View.findViewById<TextView>(R.id.wizardHint)
        
        title3.text = "Step 3: Variable approaches..."
        hint3.text = "Example: 0, \\infty, a"
        input3.hint = "Approaches..."
        input3.setText("0")
        
        AlertDialog.Builder(context)
            .setView(step3View)
            .setPositiveButton("Done") { _, _ ->
                val approaches = input3.text.toString().ifEmpty { "0" }
                val latex = "\\lim_{$variable \\to $approaches} $expression"
                appendToFormula(latex, preview)
            }
            .setNegativeButton("Back") { _, _ ->
                showLimitStep2(context, preview, expression)
            }
            .show()
    }

    private fun showFractionWizard(context: Context, preview: ImageView) {
        val step1View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input1 = step1View.findViewById<EditText>(R.id.wizardInput)
        val title1 = step1View.findViewById<TextView>(R.id.wizardTitle)
        val hint1 = step1View.findViewById<TextView>(R.id.wizardHint)
        
        title1.text = "Step 1: Enter numerator"
        hint1.text = "Top part of the fraction"
        input1.hint = "Numerator..."
        
        AlertDialog.Builder(context)
            .setView(step1View)
            .setPositiveButton("Next") { _, _ ->
                val numerator = input1.text.toString()
                if (numerator.isNotEmpty()) {
                    showFractionStep2(context, preview, numerator)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showFractionStep2(context: Context, preview: ImageView, numerator: String) {
        val step2View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input2 = step2View.findViewById<EditText>(R.id.wizardInput)
        val title2 = step2View.findViewById<TextView>(R.id.wizardTitle)
        val hint2 = step2View.findViewById<TextView>(R.id.wizardHint)
        
        title2.text = "Step 2: Enter denominator"
        hint2.text = "Bottom part of the fraction"
        input2.hint = "Denominator..."
        
        AlertDialog.Builder(context)
            .setView(step2View)
            .setPositiveButton("Done") { _, _ ->
                val denominator = input2.text.toString()
                if (denominator.isNotEmpty()) {
                    val latex = "\\frac{$numerator}{$denominator}"
                    appendToFormula(latex, preview)
                }
            }
            .setNegativeButton("Back") { _, _ ->
                showFractionWizard(context, preview)
            }
            .show()
    }

    private fun showPartialDerivativeWizard(context: Context, preview: ImageView) {
        val stepView = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input = stepView.findViewById<EditText>(R.id.wizardInput)
        val title = stepView.findViewById<TextView>(R.id.wizardTitle)
        val hint = stepView.findViewById<TextView>(R.id.wizardHint)
        
        title.text = "Enter variable"
        hint.text = "Variable for partial derivative (e.g., x, y, z)"
        input.hint = "Variable..."
        input.setText("x")
        
        AlertDialog.Builder(context)
            .setView(stepView)
            .setPositiveButton("Done") { _, _ ->
                val variable = input.text.toString().ifEmpty { "x" }
                val latex = "\\frac{\\partial}{\\partial $variable}"
                appendToFormula(latex, preview)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDerivativeWizard(context: Context, preview: ImageView) {
        val stepView = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input = stepView.findViewById<EditText>(R.id.wizardInput)
        val title = stepView.findViewById<TextView>(R.id.wizardTitle)
        val hint = stepView.findViewById<TextView>(R.id.wizardHint)
        
        title.text = "Enter variables"
        hint.text = "Format: y/x for dy/dx"
        input.hint = "y/x"
        input.setText("y/x")
        
        AlertDialog.Builder(context)
            .setView(stepView)
            .setPositiveButton("Done") { _, _ ->
                val vars = input.text.toString().split("/")
                if (vars.size == 2) {
                    val latex = "\\frac{d${vars[0]}}{d${vars[1]}}"
                    appendToFormula(latex, preview)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPowerWizard(context: Context, preview: ImageView) {
        val step1View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input1 = step1View.findViewById<EditText>(R.id.wizardInput)
        val title1 = step1View.findViewById<TextView>(R.id.wizardTitle)
        val hint1 = step1View.findViewById<TextView>(R.id.wizardHint)
        
        title1.text = "Step 1: Enter base"
        hint1.text = "The number or expression being raised to a power"
        input1.hint = "Base..."
        
        AlertDialog.Builder(context)
            .setView(step1View)
            .setPositiveButton("Next") { _, _ ->
                val base = input1.text.toString()
                if (base.isNotEmpty()) {
                    showPowerStep2(context, preview, base)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showPowerStep2(context: Context, preview: ImageView, base: String) {
        val step2View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input2 = step2View.findViewById<EditText>(R.id.wizardInput)
        val title2 = step2View.findViewById<TextView>(R.id.wizardTitle)
        val hint2 = step2View.findViewById<TextView>(R.id.wizardHint)
        
        title2.text = "Step 2: Enter exponent"
        hint2.text = "The power to raise the base to"
        input2.hint = "Exponent..."
        
        AlertDialog.Builder(context)
            .setView(step2View)
            .setPositiveButton("Done") { _, _ ->
                val exponent = input2.text.toString()
                if (exponent.isNotEmpty()) {
                    val latex = "$base^{$exponent}"
                    appendToFormula(latex, preview)
                }
            }
            .setNegativeButton("Back") { _, _ ->
                showPowerWizard(context, preview)
            }
            .show()
    }

    private fun showRootWizard(context: Context, preview: ImageView) {
        val step1View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input1 = step1View.findViewById<EditText>(R.id.wizardInput)
        val title1 = step1View.findViewById<TextView>(R.id.wizardTitle)
        val hint1 = step1View.findViewById<TextView>(R.id.wizardHint)
        
        title1.text = "Step 1: Enter root index"
        hint1.text = "Leave empty for square root (2)\nExamples: 3 for cube root, n for nth root"
        input1.hint = "Root index (optional)..."
        
        AlertDialog.Builder(context)
            .setView(step1View)
            .setPositiveButton("Next") { _, _ ->
                val index = input1.text.toString()
                showRootStep2(context, preview, index)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showRootStep2(context: Context, preview: ImageView, index: String) {
        val step2View = LayoutInflater.from(context).inflate(R.layout.dialog_wizard_step, null)
        val input2 = step2View.findViewById<EditText>(R.id.wizardInput)
        val title2 = step2View.findViewById<TextView>(R.id.wizardTitle)
        val hint2 = step2View.findViewById<TextView>(R.id.wizardHint)
        
        title2.text = "Step 2: Enter radicand"
        hint2.text = "The expression under the root"
        input2.hint = "Radicand..."
        
        AlertDialog.Builder(context)
            .setView(step2View)
            .setPositiveButton("Done") { _, _ ->
                val radicand = input2.text.toString()
                if (radicand.isNotEmpty()) {
                    val latex = if (index.isNotEmpty() && index != "2") {
                        "\\sqrt[$index]{$radicand}"
                    } else {
                        "\\sqrt{$radicand}"
                    }
                    appendToFormula(latex, preview)
                }
            }
            .setNegativeButton("Back") { _, _ ->
                showRootWizard(context, preview)
            }
            .show()
    }

    private fun showMatrixWizard(context: Context, preview: ImageView) {
        val stepView = LayoutInflater.from(context).inflate(R.layout.dialog_custom_matrix, null)
        val rows = stepView.findViewById<EditText>(R.id.matrixRows)
        val cols = stepView.findViewById<EditText>(R.id.matrixCols)
        
        AlertDialog.Builder(context)
            .setTitle("Matrix Size")
            .setView(stepView)
            .setPositiveButton("Done") { _, _ ->
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
                appendToFormula(latex, preview)
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
