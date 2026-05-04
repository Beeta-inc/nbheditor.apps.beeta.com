package com.beeta.nbheditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * Complete Math Formula Helper with:
 * - All math categories
 * - Step-by-step wizards (no LaTeX visible)
 * - Renders formulas as images when inserted
 */
object SimpleMathHelper {

    private var currentLatex = ""

    fun showMathDialog(context: Context, onInsert: (Bitmap) -> Unit) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_simple_math, null)
        val preview = dialogView.findViewById<ImageView>(R.id.mathPreview)
        val categoryTabs = dialogView.findViewById<android.widget.HorizontalScrollView>(R.id.categoryTabs)
        val tabsLayout = dialogView.findViewById<LinearLayout>(R.id.tabsLayout)
        val buttonsContainer = dialogView.findViewById<LinearLayout>(R.id.mathButtons)
        
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
                textSize = 12f
                setPadding(20, 10, 20, 10)
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
                    loadCategoryButtons(context, buttonsContainer, preview, category)
                }
            }
            tabsLayout.addView(tab)
        }
        
        // Load initial category
        loadCategoryButtons(context, buttonsContainer, preview, categories[0])
        
        val dialog = AlertDialog.Builder(context)
            .setTitle("Math Formula Builder")
            .setView(dialogView)
            .setPositiveButton("Insert") { _, _ ->
                if (currentLatex.isNotEmpty()) {
                    try {
                        val bitmap = renderLatexToBitmap(currentLatex)
                        onInsert(bitmap)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error rendering formula", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton("Clear") { _, _ ->
                currentLatex = ""
                updatePreview(preview, "")
            }
            .setNegativeButton("Cancel", null)
            .create()
        
        dialog.show()
        
        // Make Clear button not dismiss dialog
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            currentLatex = ""
            updatePreview(preview, "")
        }
    }

    private fun loadCategoryButtons(context: Context, container: LinearLayout, preview: ImageView, category: String) {
        container.removeAllViews()
        
        when (category) {
            "Basic" -> addBasicButtons(context, container, preview)
            "Fractions" -> addFractionButtons(context, container, preview)
            "Exponents" -> addExponentButtons(context, container, preview)
            "Roots" -> addRootButtons(context, container, preview)
            "Calculus" -> addCalculusButtons(context, container, preview)
            "Trigonometry" -> addTrigButtons(context, container, preview)
            "Geometry" -> addGeometryButtons(context, container, preview)
            "Algebra" -> addAlgebraButtons(context, container, preview)
            "Functions" -> addFunctionButtons(context, container, preview)
            "Matrices" -> addMatrixButtons(context, container, preview)
            "Greek" -> addGreekButtons(context, container, preview)
            "Symbols" -> addSymbolButtons(context, container, preview)
            "Logic" -> addLogicButtons(context, container, preview)
            "Sets" -> addSetButtons(context, container, preview)
        }
    }

    private fun addBasicButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "x²" to { appendLatex("x^{2}", preview) },
            "xⁿ" to { showPowerWizard(context, preview) },
            "√" to { showRootWizard(context, preview) },
            "±" to { appendLatex("\\pm ", preview) }
        ))
        addButtonRow(context, container, listOf(
            "=" to { appendLatex("=", preview) },
            "≠" to { appendLatex("\\neq ", preview) },
            "<" to { appendLatex("<", preview) },
            ">" to { appendLatex(">", preview) }
        ))
        addButtonRow(context, container, listOf(
            "≤" to { appendLatex("\\leq ", preview) },
            "≥" to { appendLatex("\\geq ", preview) },
            "×" to { appendLatex("\\times ", preview) },
            "÷" to { appendLatex("\\div ", preview) }
        ))
    }

    private fun addFractionButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "a/b" to { showFractionWizard(context, preview) },
            "∂/∂x" to { showPartialWizard(context, preview) },
            "dy/dx" to { showDerivativeWizard(context, preview) }
        ))
    }

    private fun addExponentButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "eˣ" to { appendLatex("e^{x}", preview) },
            "10ˣ" to { appendLatex("10^{x}", preview) },
            "2ˣ" to { appendLatex("2^{x}", preview) },
            "Custom" to { showPowerWizard(context, preview) }
        ))
    }

    private fun addRootButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "√x" to { appendLatex("\\sqrt{x}", preview) },
            "∛x" to { appendLatex("\\sqrt[3]{x}", preview) },
            "Custom" to { showRootWizard(context, preview) }
        ))
    }

    private fun addCalculusButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "∫ Integral" to { showIntegralWizard(context, preview) },
            "∑ Sum" to { showSumWizard(context, preview) },
            "lim Limit" to { showLimitWizard(context, preview) }
        ))
        addButtonRow(context, container, listOf(
            "∂" to { appendLatex("\\partial ", preview) },
            "∇" to { appendLatex("\\nabla ", preview) },
            "∆" to { appendLatex("\\Delta ", preview) }
        ))
    }

    private fun addTrigButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "sin" to { appendLatex("\\sin(", preview) },
            "cos" to { appendLatex("\\cos(", preview) },
            "tan" to { appendLatex("\\tan(", preview) },
            ")" to { appendLatex(")", preview) }
        ))
        addButtonRow(context, container, listOf(
            "sinh" to { appendLatex("\\sinh(", preview) },
            "cosh" to { appendLatex("\\cosh(", preview) },
            "tanh" to { appendLatex("\\tanh(", preview) },
            "arcsin" to { appendLatex("\\arcsin(", preview) }
        ))
    }

    private fun addGeometryButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "∠" to { appendLatex("\\angle ", preview) },
            "°" to { appendLatex("^\\circ", preview) },
            "△" to { appendLatex("\\triangle ", preview) },
            "⊥" to { appendLatex("\\perp ", preview) }
        ))
        addButtonRow(context, container, listOf(
            "∥" to { appendLatex("\\parallel ", preview) },
            "≅" to { appendLatex("\\cong ", preview) },
            "∼" to { appendLatex("\\sim ", preview) },
            "π" to { appendLatex("\\pi ", preview) }
        ))
    }

    private fun addAlgebraButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "Quadratic" to { appendLatex("x = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}", preview) }
        ))
    }

    private fun addFunctionButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "f(x)=" to { appendLatex("f(x)=", preview) },
            "ln" to { appendLatex("\\ln(", preview) },
            "log" to { appendLatex("\\log(", preview) },
            ")" to { appendLatex(")", preview) }
        ))
    }

    private fun addMatrixButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "2×2" to { appendLatex("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}", preview) },
            "3×3" to { appendLatex("\\begin{pmatrix} a & b & c \\\\ d & e & f \\\\ g & h & i \\end{pmatrix}", preview) }
        ))
        addButtonRow(context, container, listOf(
            "Custom" to { showMatrixWizard(context, preview) }
        ))
    }

    private fun addGreekButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "α" to { appendLatex("\\alpha ", preview) },
            "β" to { appendLatex("\\beta ", preview) },
            "γ" to { appendLatex("\\gamma ", preview) },
            "δ" to { appendLatex("\\delta ", preview) }
        ))
        addButtonRow(context, container, listOf(
            "θ" to { appendLatex("\\theta ", preview) },
            "λ" to { appendLatex("\\lambda ", preview) },
            "μ" to { appendLatex("\\mu ", preview) },
            "π" to { appendLatex("\\pi ", preview) }
        ))
        addButtonRow(context, container, listOf(
            "σ" to { appendLatex("\\sigma ", preview) },
            "φ" to { appendLatex("\\phi ", preview) },
            "ω" to { appendLatex("\\omega ", preview) },
            "Σ" to { appendLatex("\\Sigma ", preview) }
        ))
    }

    private fun addSymbolButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "∞" to { appendLatex("\\infty ", preview) },
            "→" to { appendLatex("\\rightarrow ", preview) },
            "←" to { appendLatex("\\leftarrow ", preview) },
            "⇒" to { appendLatex("\\Rightarrow ", preview) }
        ))
        addButtonRow(context, container, listOf(
            "∀" to { appendLatex("\\forall ", preview) },
            "∃" to { appendLatex("\\exists ", preview) },
            "ℕ" to { appendLatex("\\mathbb{N}", preview) },
            "ℝ" to { appendLatex("\\mathbb{R}", preview) }
        ))
    }

    private fun addLogicButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "∧" to { appendLatex("\\land ", preview) },
            "∨" to { appendLatex("\\lor ", preview) },
            "¬" to { appendLatex("\\neg ", preview) },
            "⇒" to { appendLatex("\\Rightarrow ", preview) }
        ))
    }

    private fun addSetButtons(context: Context, container: LinearLayout, preview: ImageView) {
        addButtonRow(context, container, listOf(
            "∈" to { appendLatex("\\in ", preview) },
            "∉" to { appendLatex("\\notin ", preview) },
            "∪" to { appendLatex("\\cup ", preview) },
            "∩" to { appendLatex("\\cap ", preview) }
        ))
        addButtonRow(context, container, listOf(
            "⊂" to { appendLatex("\\subset ", preview) },
            "⊃" to { appendLatex("\\supset ", preview) },
            "∅" to { appendLatex("\\emptyset ", preview) },
            "×" to { appendLatex("\\times ", preview) }
        ))
    }

    private fun addButtonRow(context: Context, container: LinearLayout, buttons: List<Pair<String, () -> Unit>>) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        buttons.forEach { (label, action) ->
            val button = Button(context).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    setMargins(4, 4, 4, 4)
                }
                setOnClickListener { action() }
            }
            row.addView(button)
        }
        
        container.addView(row)
    }

    private fun appendLatex(latex: String, preview: ImageView) {
        currentLatex += latex
        updatePreview(preview, currentLatex)
    }

    private fun updatePreview(preview: ImageView, latex: String) {
        try {
            if (latex.isEmpty()) {
                preview.setImageDrawable(null)
                return
            }
            
            val drawable = JLatexMathDrawable.builder(latex)
                .textSize(60f)
                .padding(16)
                .background(Color.WHITE)
                .build()
            
            preview.setImageDrawable(drawable)
        } catch (e: Exception) {
            // Show error placeholder
            val bitmap = Bitmap.createBitmap(400, 100, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.LTGRAY)
            val paint = Paint().apply {
                color = Color.RED
                textSize = 24f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Invalid formula", 200f, 50f, paint)
            preview.setImageBitmap(bitmap)
        }
    }

    private fun renderLatexToBitmap(latex: String): Bitmap {
        val drawable = JLatexMathDrawable.builder(latex)
            .textSize(60f)
            .padding(16)
            .background(Color.WHITE)
            .build()
        
        return drawable.toBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STEP-BY-STEP WIZARDS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun showFractionWizard(context: Context, preview: ImageView) {
        val input = EditText(context).apply {
            hint = "Enter numerator"
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Step 1: Numerator")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val numerator = input.text.toString()
                if (numerator.isNotEmpty()) {
                    showFractionStep2(context, preview, numerator)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFractionStep2(context: Context, preview: ImageView, numerator: String) {
        val input = EditText(context).apply {
            hint = "Enter denominator"
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Step 2: Denominator")
            .setView(input)
            .setPositiveButton("Done") { _, _ ->
                val denominator = input.text.toString()
                if (denominator.isNotEmpty()) {
                    appendLatex("\\frac{$numerator}{$denominator}", preview)
                }
            }
            .setNegativeButton("Back") { _, _ ->
                showFractionWizard(context, preview)
            }
            .show()
    }

    private fun showPowerWizard(context: Context, preview: ImageView) {
        val input = EditText(context).apply {
            hint = "Enter base"
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Step 1: Base")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val base = input.text.toString()
                if (base.isNotEmpty()) {
                    showPowerStep2(context, preview, base)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPowerStep2(context: Context, preview: ImageView, base: String) {
        val input = EditText(context).apply {
            hint = "Enter exponent"
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Step 2: Exponent")
            .setView(input)
            .setPositiveButton("Done") { _, _ ->
                val exponent = input.text.toString()
                if (exponent.isNotEmpty()) {
                    appendLatex("$base^{$exponent}", preview)
                }
            }
            .setNegativeButton("Back") { _, _ ->
                showPowerWizard(context, preview)
            }
            .show()
    }

    private fun showRootWizard(context: Context, preview: ImageView) {
        val input = EditText(context).apply {
            hint = "Enter expression (leave empty for square root)"
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Root")
            .setMessage("Enter the expression under the root")
            .setView(input)
            .setPositiveButton("Done") { _, _ ->
                val expr = input.text.toString()
                if (expr.isNotEmpty()) {
                    appendLatex("\\sqrt{$expr}", preview)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showIntegralWizard(context: Context, preview: ImageView) {
        val input = EditText(context).apply {
            hint = "Enter function"
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Step 1: Function")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val function = input.text.toString()
                if (function.isNotEmpty()) {
                    showIntegralStep2(context, preview, function)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showIntegralStep2(context: Context, preview: ImageView, function: String) {
        val input = EditText(context).apply {
            hint = "Lower limit (optional)"
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Step 2: Lower Limit")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val lower = input.text.toString()
                showIntegralStep3(context, preview, function, lower)
            }
            .setNegativeButton("Back") { _, _ ->
                showIntegralWizard(context, preview)
            }
            .show()
    }

    private fun showIntegralStep3(context: Context, preview: ImageView, function: String, lower: String) {
        val input = EditText(context).apply {
            hint = "Upper limit (optional)"
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Step 3: Upper Limit")
            .setView(input)
            .setPositiveButton("Done") { _, _ ->
                val upper = input.text.toString()
                val latex = if (lower.isNotEmpty() || upper.isNotEmpty()) {
                    "\\int_{$lower}^{$upper} $function \\, dx"
                } else {
                    "\\int $function \\, dx"
                }
                appendLatex(latex, preview)
            }
            .setNegativeButton("Back") { _, _ ->
                showIntegralStep2(context, preview, function)
            }
            .show()
    }

    private fun showSumWizard(context: Context, preview: ImageView) {
        val input = EditText(context).apply {
            hint = "Enter expression"
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Step 1: Expression")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val expression = input.text.toString()
                if (expression.isNotEmpty()) {
                    showSumStep2(context, preview, expression)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSumStep2(context: Context, preview: ImageView, expression: String) {
        val input = EditText(context).apply {
            hint = "e.g., i=1"
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Step 2: Lower Limit")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val lower = input.text.toString()
                showSumStep3(context, preview, expression, lower)
            }
            .setNegativeButton("Back") { _, _ ->
                showSumWizard(context, preview)
            }
            .show()
    }

    private fun showSumStep3(context: Context, preview: ImageView, expression: String, lower: String) {
        val input = EditText(context).apply {
            hint = "e.g., n"
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Step 3: Upper Limit")
            .setView(input)
            .setPositiveButton("Done") { _, _ ->
                val upper = input.text.toString()
                appendLatex("\\sum_{$lower}^{$upper} $expression", preview)
            }
            .setNegativeButton("Back") { _, _ ->
                showSumStep2(context, preview, expression)
            }
            .show()
    }

    private fun showLimitWizard(context: Context, preview: ImageView) {
        val input = EditText(context).apply {
            hint = "Enter expression"
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Step 1: Expression")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val expression = input.text.toString()
                if (expression.isNotEmpty()) {
                    showLimitStep2(context, preview, expression)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLimitStep2(context: Context, preview: ImageView, expression: String) {
        val input = EditText(context).apply {
            hint = "e.g., x"
            setText("x")
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Step 2: Variable")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val variable = input.text.toString()
                showLimitStep3(context, preview, expression, variable)
            }
            .setNegativeButton("Back") { _, _ ->
                showLimitWizard(context, preview)
            }
            .show()
    }

    private fun showLimitStep3(context: Context, preview: ImageView, expression: String, variable: String) {
        val input = EditText(context).apply {
            hint = "e.g., 0, \\infty"
            setText("0")
            isFocusable = false
            setOnClickListener { showNestedMathBuilder(context, this) }
        }
        
        AlertDialog.Builder(context)
            .setTitle("Step 3: Approaches")
            .setView(input)
            .setPositiveButton("Done") { _, _ ->
                val approaches = input.text.toString()
                appendLatex("\\lim_{$variable \\to $approaches} $expression", preview)
            }
            .setNegativeButton("Back") { _, _ ->
                showLimitStep2(context, preview, expression)
            }
            .show()
    }

    private fun showPartialWizard(context: Context, preview: ImageView) {
        val input = EditText(context).apply {
            hint = "Variable (e.g., x)"
            setText("x")
        }
        
        AlertDialog.Builder(context)
            .setTitle("Partial Derivative")
            .setView(input)
            .setPositiveButton("Done") { _, _ ->
                val variable = input.text.toString()
                appendLatex("\\frac{\\partial}{\\partial $variable}", preview)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDerivativeWizard(context: Context, preview: ImageView) {
        val input = EditText(context).apply {
            hint = "Format: y/x for dy/dx"
            setText("y/x")
        }
        
        AlertDialog.Builder(context)
            .setTitle("Derivative")
            .setView(input)
            .setPositiveButton("Done") { _, _ ->
                val vars = input.text.toString().split("/")
                if (vars.size == 2) {
                    appendLatex("\\frac{d${vars[0]}}{d${vars[1]}}", preview)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMatrixWizard(context: Context, preview: ImageView) {
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        
        val rowsInput = EditText(context).apply {
            hint = "Rows"
            setText("2")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val colsInput = EditText(context).apply {
            hint = "Columns"
            setText("2")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        
        dialogView.addView(TextView(context).apply { text = "Rows:" })
        dialogView.addView(rowsInput)
        dialogView.addView(TextView(context).apply { text = "Columns:" })
        dialogView.addView(colsInput)
        
        AlertDialog.Builder(context)
            .setTitle("Matrix Size")
            .setView(dialogView)
            .setPositiveButton("Done") { _, _ ->
                val r = rowsInput.text.toString().toIntOrNull() ?: 2
                val c = colsInput.text.toString().toIntOrNull() ?: 2
                
                val matrixContent = StringBuilder()
                for (i in 0 until r) {
                    for (j in 0 until c) {
                        matrixContent.append("a_{${i+1}${j+1}}")
                        if (j < c - 1) matrixContent.append(" & ")
                    }
                    if (i < r - 1) matrixContent.append(" \\\\ ")
                }
                
                appendLatex("\\begin{pmatrix} $matrixContent \\end{pmatrix}", preview)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNestedMathBuilder(context: Context, targetEditText: EditText) {
        var nestedLatex = targetEditText.text.toString()
        
        val nestedDialog = android.app.Dialog(context)
        val nestedView = LayoutInflater.from(context).inflate(R.layout.dialog_simple_math, null)
        nestedDialog.setContentView(nestedView)
        nestedDialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        
        val nestedPreview = nestedView.findViewById<ImageView>(R.id.mathPreview)
        val nestedContainer = nestedView.findViewById<LinearLayout>(R.id.mathButtons)
        
        fun updateNested(latex: String) {
            nestedLatex += latex
            try {
                val drawable = JLatexMathDrawable.builder(nestedLatex)
                    .textSize(40f)
                    .padding(8)
                    .background(Color.WHITE)
                    .build()
                nestedPreview.setImageDrawable(drawable)
            } catch (e: Exception) {
                nestedPreview.setImageDrawable(null)
            }
        }
        
        nestedContainer.removeAllViews()
        addButtonRow(context, nestedContainer, listOf(
            "x" to { updateNested("x") },
            "y" to { updateNested("y") },
            "x²" to { updateNested("x^2") },
            "+" to { updateNested("+") }
        ))
        addButtonRow(context, nestedContainer, listOf(
            "sin" to { updateNested("\\sin(") },
            "cos" to { updateNested("\\cos(") },
            "log" to { updateNested("\\log(") },
            ")" to { updateNested(")") }
        ))
        addButtonRow(context, nestedContainer, listOf(
            "(" to { updateNested("(") },
            "-" to { updateNested("-") },
            "×" to { updateNested("\\times") },
            "÷" to { updateNested("\\div") }
        ))
        
        val insertBtn = Button(context).apply {
            text = "Insert"
            setOnClickListener {
                targetEditText.setText(nestedLatex)
                nestedDialog.dismiss()
            }
        }
        
        val clearBtn = Button(context).apply {
            text = "Clear"
            setOnClickListener {
                nestedLatex = ""
                nestedPreview.setImageDrawable(null)
            }
        }
        
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(insertBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        
        nestedContainer.addView(btnRow)
        nestedDialog.show()
    }
}
