package com.beeta.nbheditor

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

object MathFormulaHelper {
    
    // Comprehensive math symbols organized by category
    private val mathSymbols = mapOf(
        "Greek Letters" to listOf(
            "α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ", "λ", "μ", "ν", "ξ", "ο", "π", "ρ", "σ", "τ", "υ", "φ", "χ", "ψ", "ω",
            "Α", "Β", "Γ", "Δ", "Ε", "Ζ", "Η", "Θ", "Ι", "Κ", "Λ", "Μ", "Ν", "Ξ", "Ο", "Π", "Ρ", "Σ", "Τ", "Υ", "Φ", "Χ", "Ψ", "Ω"
        ),
        "Trigonometry" to listOf(
            "sin", "cos", "tan", "cot", "sec", "csc",
            "sin⁻¹", "cos⁻¹", "tan⁻¹", "cot⁻¹", "sec⁻¹", "csc⁻¹",
            "sinh", "cosh", "tanh", "coth", "sech", "csch",
            "arcsin", "arccos", "arctan", "arccot", "arcsec", "arccsc"
        ),
        "Calculus" to listOf(
            "∫", "∬", "∭", "∮", "∯", "∰", "∱", "∲", "∳",
            "∂", "∇", "∆", "∑", "∏", "∐",
            "lim", "sup", "inf", "max", "min",
            "d/dx", "∂/∂x", "∂²/∂x²", "dy/dx", "d²y/dx²",
            "∫₀¹", "∫₋∞^∞", "lim_{x→∞}", "lim_{x→0}"
        ),
        "Operators" to listOf(
            "+", "−", "×", "÷", "±", "∓", "∗", "∘", "∙",
            "√", "∛", "∜", "ⁿ√",
            "∝", "∞", "∂", "∇", "∆",
            "⊕", "⊖", "⊗", "⊘", "⊙", "⊚", "⊛", "⊜", "⊝"
        ),
        "Relations" to listOf(
            "=", "≠", "≈", "≡", "≢", "<", ">", "≤", "≥", "≪", "≫",
            "∼", "≃", "≅", "≆", "≇", "≉", "≊", "≋", "≌", "≍", "≎", "≏",
            "≐", "≑", "≒", "≓", "≔", "≕", "≖", "≗", "≘", "≙", "≚", "≛", "≜", "≝", "≞", "≟"
        ),
        "Set Theory" to listOf(
            "∈", "∉", "∋", "∌", "⊂", "⊃", "⊄", "⊅", "⊆", "⊇", "⊈", "⊉", "⊊", "⊋",
            "∪", "∩", "∖", "⊕", "⊗", "⊙", "∅", "∀", "∃", "∄",
            "⊎", "⊏", "⊐", "⊑", "⊒", "⊓", "⊔"
        ),
        "Logic" to listOf(
            "∧", "∨", "¬", "⊻", "⊼", "⊽", "∴", "∵",
            "⊤", "⊥", "⊢", "⊣", "⊨", "⊭", "⊩", "⊪", "⊫", "⊬",
            "⊦", "⊧", "⊮", "⊯", "⊰", "⊱", "⊲", "⊳", "⊴", "⊵"
        ),
        "Arrows" to listOf(
            "→", "←", "↑", "↓", "↔", "↕", "⇒", "⇐", "⇑", "⇓", "⇔", "⇕",
            "↗", "↘", "↙", "↖", "⇗", "⇘", "⇙", "⇖",
            "↦", "↤", "↥", "↧", "⇝", "⇜", "⇀", "⇁", "⇂", "⇃", "⇄", "⇅", "⇆", "⇇", "⇈", "⇉", "⇊"
        ),
        "Superscripts" to listOf(
            "⁰", "¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹",
            "⁺", "⁻", "⁼", "⁽", "⁾",
            "ⁿ", "ⁱ", "ˣ", "ʸ", "ᵃ", "ᵇ", "ᶜ", "ᵈ", "ᵉ", "ᶠ", "ᵍ", "ʰ", "ʲ", "ᵏ", "ˡ", "ᵐ", "ⁿ", "ᵒ", "ᵖ", "ʳ", "ˢ", "ᵗ", "ᵘ", "ᵛ", "ʷ", "ᶻ"
        ),
        "Subscripts" to listOf(
            "₀", "₁", "₂", "₃", "₄", "₅", "₆", "₇", "₈", "₉",
            "₊", "₋", "₌", "₍", "₎",
            "ₐ", "ₑ", "ₒ", "ₓ", "ₕ", "ₖ", "ₗ", "ₘ", "ₙ", "ₚ", "ᵣ", "ₛ", "ₜ", "ᵤ", "ᵥ"
        ),
        "Fractions" to listOf(
            "½", "⅓", "⅔", "¼", "¾", "⅕", "⅖", "⅗", "⅘", "⅙", "⅚", "⅛", "⅜", "⅝", "⅞", "⅟",
            "⅐", "⅑", "⅒"
        ),
        "Geometry" to listOf(
            "∠", "∡", "∢", "⊾", "⊿", "∟", "°", "′", "″", "‴",
            "⊥", "∥", "∦", "≅", "∼", "△", "▷", "◁", "⊳", "⊲", "⊴", "⊵",
            "⊿", "∟", "⊾", "⟂", "⫛"
        ),
        "Brackets" to listOf(
            "(", ")", "[", "]", "{", "}",
            "⟨", "⟩", "⌈", "⌉", "⌊", "⌋",
            "⟦", "⟧", "⟪", "⟫",
            "⦃", "⦄", "⦅", "⦆", "⦇", "⦈", "⦉", "⦊",
            "⦋", "⦌", "⦍", "⦎", "⦏", "⦐"
        ),
        "Matrices" to listOf(
            "[", "]", "|", "‖",
            "⎡", "⎤", "⎢", "⎥", "⎣", "⎦",
            "⎧", "⎨", "⎩", "⎪", "⎫", "⎬", "⎭",
            "⎛", "⎜", "⎝", "⎞", "⎟", "⎠"
        ),
        "Number Sets" to listOf(
            "ℕ", "ℤ", "ℚ", "ℝ", "ℂ", "ℙ", "ℍ", "𝔸", "𝔹", "𝔽", "𝔾"
        ),
        "Special Functions" to listOf(
            "exp", "ln", "log", "log₂", "log₁₀",
            "arg", "det", "dim", "ker", "deg",
            "gcd", "lcm", "mod", "sgn",
            "Γ", "ζ", "Β", "Ψ", "Φ"
        ),
        "Probability & Statistics" to listOf(
            "P", "E", "Var", "Cov", "σ", "σ²", "μ",
            "∼", "≈", "∝",
            "ℙ", "𝔼",
            "χ²", "t", "F", "z",
            "H₀", "H₁", "α", "β"
        ),
        "Misc" to listOf(
            "∅", "∞", "ℵ", "ℶ", "ℷ", "ℸ",
            "⊤", "⊥", "∴", "∵", "∎", "□", "■",
            "◊", "◇", "★", "☆", "†", "‡",
            "℧", "℩", "Å", "℮", "ℓ", "№", "℗", "℠", "™", "Ω", "℧", "℈"
        )
    )
    
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
        val mathPreview = dialogView.findViewById<TextView>(R.id.mathPreview)
        val symbolsContainer = dialogView.findViewById<LinearLayout>(R.id.symbolsContainer)
        val categorySpinner = dialogView.findViewById<android.widget.Spinner>(R.id.categorySpinner)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)
        val insertButton = dialogView.findViewById<Button>(R.id.insertButton)
        
        // Apply math font to preview
        if (mathFonts.isNotEmpty()) {
            mathPreview.typeface = mathFonts[0]
        }
        
        // Setup category spinner
        val categories = mathSymbols.keys.toList()
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = adapter
        
        // Load initial category symbols
        updateSymbolButtons(context, symbolsContainer, mathInput, categories[0])
        
        // Category selection listener
        categorySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                updateSymbolButtons(context, symbolsContainer, mathInput, categories[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        // Live preview
        mathInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                mathPreview.text = s?.toString() ?: ""
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
    
    private fun updateSymbolButtons(context: Context, container: LinearLayout, input: EditText, category: String) {
        container.removeAllViews()
        val symbols = mathSymbols[category] ?: emptyList()
        
        symbols.forEach { symbol ->
            val button = Button(context).apply {
                text = symbol
                textSize = 18f
                setTextColor(ContextCompat.getColor(context, R.color.editor_text))
                background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
                setPadding(16, 8, 16, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 8
                }
                
                // Apply math font if available
                if (mathFonts.isNotEmpty()) {
                    typeface = mathFonts[0]
                }
                
                setOnClickListener {
                    val cursor = input.selectionStart
                    val text = input.text
                    text.insert(cursor, symbol)
                }
            }
            container.addView(button)
        }
    }
    
    fun getAllSymbols(): List<String> {
        return mathSymbols.values.flatten()
    }
    
    fun getSymbolsByCategory(category: String): List<String> {
        return mathSymbols[category] ?: emptyList()
    }
}
