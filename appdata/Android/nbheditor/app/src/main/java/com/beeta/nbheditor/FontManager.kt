package com.beeta.nbheditor

import android.graphics.Typeface

object FontManager {
    
    data class FontInfo(
        val name: String,
        val typeface: Typeface,
        val category: String
    )
    
    enum class FontCategory {
        SYSTEM,
        SERIF,
        SANS_SERIF,
        MONOSPACE,
        HANDWRITING,
        DISPLAY
    }
    
    private val fontList = mutableListOf<FontInfo>()
    
    fun initialize() {
        if (fontList.isNotEmpty()) return
        
        // System Fonts
        fontList.add(FontInfo("Default", Typeface.DEFAULT, "System"))
        fontList.add(FontInfo("Sans Serif", Typeface.SANS_SERIF, "System"))
        fontList.add(FontInfo("Serif", Typeface.SERIF, "System"))
        fontList.add(FontInfo("Monospace", Typeface.MONOSPACE, "System"))
        
        // Sans Serif Family
        fontList.add(FontInfo("Roboto", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Light", Typeface.create("sans-serif-light", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Thin", Typeface.create("sans-serif-thin", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Medium", Typeface.create("sans-serif-medium", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Black", Typeface.create("sans-serif-black", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Condensed", Typeface.create("sans-serif-condensed", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Condensed Light", Typeface.create("sans-serif-condensed-light", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Condensed Medium", Typeface.create("sans-serif-condensed-medium", Typeface.NORMAL), "Sans Serif"))
        
        // Serif Family
        fontList.add(FontInfo("Noto Serif", Typeface.create("serif", Typeface.NORMAL), "Serif"))
        fontList.add(FontInfo("Droid Serif", Typeface.create("serif", Typeface.NORMAL), "Serif"))
        fontList.add(FontInfo("Serif Bold", Typeface.create("serif", Typeface.BOLD), "Serif"))
        fontList.add(FontInfo("Serif Italic", Typeface.create("serif", Typeface.ITALIC), "Serif"))
        
        // Monospace Family
        fontList.add(FontInfo("Droid Sans Mono", Typeface.create("monospace", Typeface.NORMAL), "Monospace"))
        fontList.add(FontInfo("Courier", Typeface.create("monospace", Typeface.NORMAL), "Monospace"))
        fontList.add(FontInfo("Courier Bold", Typeface.create("monospace", Typeface.BOLD), "Monospace"))
        fontList.add(FontInfo("Source Code Pro", Typeface.create("monospace", Typeface.NORMAL), "Monospace"))
        
        // Handwriting/Casual
        fontList.add(FontInfo("Casual", Typeface.create("casual", Typeface.NORMAL), "Handwriting"))
        fontList.add(FontInfo("Cursive", Typeface.create("cursive", Typeface.NORMAL), "Handwriting"))
        fontList.add(FontInfo("Dancing Script", Typeface.create("cursive", Typeface.NORMAL), "Handwriting"))
        fontList.add(FontInfo("Comic Sans", Typeface.create("casual", Typeface.NORMAL), "Handwriting"))
        
        // Display/Decorative
        fontList.add(FontInfo("Coming Soon", Typeface.create("casual", Typeface.NORMAL), "Display"))
        fontList.add(FontInfo("Carrois Gothic SC", Typeface.create("sans-serif", Typeface.NORMAL), "Display"))
        fontList.add(FontInfo("Cutive Mono", Typeface.create("monospace", Typeface.NORMAL), "Display"))
        
        // Additional Android System Fonts
        fontList.add(FontInfo("Arial", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Helvetica", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Verdana", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Tahoma", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Trebuchet MS", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Georgia", Typeface.create("serif", Typeface.NORMAL), "Serif"))
        fontList.add(FontInfo("Times New Roman", Typeface.create("serif", Typeface.NORMAL), "Serif"))
        fontList.add(FontInfo("Palatino", Typeface.create("serif", Typeface.NORMAL), "Serif"))
        fontList.add(FontInfo("Garamond", Typeface.create("serif", Typeface.NORMAL), "Serif"))
        fontList.add(FontInfo("Bookman", Typeface.create("serif", Typeface.NORMAL), "Serif"))
        
        // More Monospace
        fontList.add(FontInfo("Consolas", Typeface.create("monospace", Typeface.NORMAL), "Monospace"))
        fontList.add(FontInfo("Monaco", Typeface.create("monospace", Typeface.NORMAL), "Monospace"))
        fontList.add(FontInfo("Lucida Console", Typeface.create("monospace", Typeface.NORMAL), "Monospace"))
        fontList.add(FontInfo("Andale Mono", Typeface.create("monospace", Typeface.NORMAL), "Monospace"))
        
        // More Sans Serif variants
        fontList.add(FontInfo("Open Sans", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Lato", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Montserrat", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Raleway", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Ubuntu", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Nunito", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Poppins", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Oswald", Typeface.create("sans-serif-condensed", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Source Sans Pro", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("PT Sans", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
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
