package com.beeta.nbheditor

import android.graphics.Typeface

object FontManager {
    
    data class FontInfo(
        val name: String,
        val typeface: Typeface,
        val category: String
    )
    
    private val fontList = mutableListOf<FontInfo>()
    
    fun initialize() {
        if (fontList.isNotEmpty()) return
        
        // Core System Fonts (these actually work)
        fontList.add(FontInfo("Default", Typeface.DEFAULT, "System"))
        fontList.add(FontInfo("Default Bold", Typeface.DEFAULT_BOLD, "System"))
        fontList.add(FontInfo("Sans Serif", Typeface.SANS_SERIF, "System"))
        fontList.add(FontInfo("Serif", Typeface.SERIF, "System"))
        fontList.add(FontInfo("Monospace", Typeface.MONOSPACE, "System"))
        
        // Sans Serif Variants
        fontList.add(FontInfo("Roboto", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Bold", Typeface.create("sans-serif", Typeface.BOLD), "Sans Serif"))
        fontList.add(FontInfo("Roboto Italic", Typeface.create("sans-serif", Typeface.ITALIC), "Sans Serif"))
        fontList.add(FontInfo("Roboto Light", Typeface.create("sans-serif-light", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Thin", Typeface.create("sans-serif-thin", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Medium", Typeface.create("sans-serif-medium", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Black", Typeface.create("sans-serif-black", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Condensed", Typeface.create("sans-serif-condensed", Typeface.NORMAL), "Sans Serif"))
        
        // Serif Variants
        fontList.add(FontInfo("Serif", Typeface.create("serif", Typeface.NORMAL), "Serif"))
        fontList.add(FontInfo("Serif Bold", Typeface.create("serif", Typeface.BOLD), "Serif"))
        fontList.add(FontInfo("Serif Italic", Typeface.create("serif", Typeface.ITALIC), "Serif"))
        fontList.add(FontInfo("Serif Bold Italic", Typeface.create("serif", Typeface.BOLD_ITALIC), "Serif"))
        
        // Monospace Variants
        fontList.add(FontInfo("Monospace", Typeface.create("monospace", Typeface.NORMAL), "Monospace"))
        fontList.add(FontInfo("Monospace Bold", Typeface.create("monospace", Typeface.BOLD), "Monospace"))
        fontList.add(FontInfo("Monospace Italic", Typeface.create("monospace", Typeface.ITALIC), "Monospace"))
        
        // Casual/Handwriting
        fontList.add(FontInfo("Casual", Typeface.create("casual", Typeface.NORMAL), "Casual"))
        fontList.add(FontInfo("Cursive", Typeface.create("cursive", Typeface.NORMAL), "Casual"))
    }
    
    fun getAllFonts(): List<FontInfo> {
        if (fontList.isEmpty()) initialize()
        return fontList
    }
    
    fun getFontsByCategory(category: String): List<FontInfo> {
        if (fontList.isEmpty()) initialize()
        return fontList.filter { it.category == category }
    }
    
    fun getFontByName(name: String): Typeface? {
        if (fontList.isEmpty()) initialize()
        return fontList.find { it.name == name }?.typeface
    }
    
    fun getCategories(): List<String> {
        if (fontList.isEmpty()) initialize()
        return fontList.map { it.category }.distinct().sorted()
    }
}
