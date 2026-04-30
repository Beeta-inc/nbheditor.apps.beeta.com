package com.beeta.nbheditor

import android.content.Context
import android.graphics.Typeface
import android.util.Log

object FontManager {
    
    data class FontInfo(
        val name: String,
        val typeface: Typeface,
        val category: String
    )
    
    private val fontList = mutableListOf<FontInfo>()
    private var context: Context? = null
    
    fun initialize(ctx: Context? = null) {
        if (fontList.isNotEmpty()) return
        
        ctx?.let { context = it.applicationContext }
        
        // Core System Fonts (always available)
        fontList.add(FontInfo("Default", Typeface.DEFAULT, "System"))
        fontList.add(FontInfo("Default Bold", Typeface.DEFAULT_BOLD, "System"))
        fontList.add(FontInfo("Sans Serif", Typeface.SANS_SERIF, "System"))
        fontList.add(FontInfo("Serif", Typeface.SERIF, "System"))
        fontList.add(FontInfo("Monospace", Typeface.MONOSPACE, "System"))
        
        // Android System Font Variants
        fontList.add(FontInfo("Roboto", Typeface.create("sans-serif", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Bold", Typeface.create("sans-serif", Typeface.BOLD), "Sans Serif"))
        fontList.add(FontInfo("Roboto Light", Typeface.create("sans-serif-light", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Thin", Typeface.create("sans-serif-thin", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Medium", Typeface.create("sans-serif-medium", Typeface.NORMAL), "Sans Serif"))
        fontList.add(FontInfo("Roboto Condensed", Typeface.create("sans-serif-condensed", Typeface.NORMAL), "Sans Serif"))
        
        fontList.add(FontInfo("Serif Bold", Typeface.create("serif", Typeface.BOLD), "Serif"))
        fontList.add(FontInfo("Serif Italic", Typeface.create("serif", Typeface.ITALIC), "Serif"))
        
        fontList.add(FontInfo("Monospace Bold", Typeface.create("monospace", Typeface.BOLD), "Monospace"))
        
        fontList.add(FontInfo("Casual", Typeface.create("casual", Typeface.NORMAL), "Casual"))
        fontList.add(FontInfo("Cursive", Typeface.create("cursive", Typeface.NORMAL), "Casual"))
        
        // Load custom fonts from assets if context is available
        context?.let { ctx ->
            loadCustomFont(ctx, "Lato", "lato.ttf", "Sans Serif")
            loadCustomFont(ctx, "Montserrat", "montserrat.ttf", "Sans Serif")
            loadCustomFont(ctx, "Poppins", "poppins.ttf", "Sans Serif")
            loadCustomFont(ctx, "Nunito", "nunito.ttf", "Sans Serif")
            loadCustomFont(ctx, "Ubuntu", "ubuntu.ttf", "Sans Serif")
            loadCustomFont(ctx, "Work Sans", "work_sans.ttf", "Sans Serif")
            loadCustomFont(ctx, "Fira Sans", "fira_sans.ttf", "Sans Serif")
            loadCustomFont(ctx, "Noto Sans", "noto_sans.ttf", "Sans Serif")
            loadCustomFont(ctx, "Oswald", "oswald.ttf", "Sans Serif")
            loadCustomFont(ctx, "Raleway", "raleway.ttf", "Sans Serif")
            loadCustomFont(ctx, "Quicksand", "quicksand.ttf", "Sans Serif")
            loadCustomFont(ctx, "Archivo", "archivo.ttf", "Sans Serif")
            
            loadCustomFont(ctx, "Playfair Display", "playfair_display.ttf", "Serif")
            loadCustomFont(ctx, "Roboto Slab", "roboto_slab.ttf", "Serif")
            loadCustomFont(ctx, "Crimson Text", "crimson_text.ttf", "Serif")
            
            loadCustomFont(ctx, "JetBrains Mono", "jetbrains_mono.ttf", "Monospace")
            loadCustomFont(ctx, "Source Code Pro", "source_code_pro.ttf", "Monospace")
            
            loadCustomFont(ctx, "Dancing Script", "dancing_script.ttf", "Handwriting")
            loadCustomFont(ctx, "Pacifico", "pacifico.ttf", "Handwriting")
            loadCustomFont(ctx, "Indie Flower", "indie_flower.ttf", "Handwriting")
            loadCustomFont(ctx, "Caveat", "caveat.ttf", "Handwriting")
            
            loadCustomFont(ctx, "Comfortaa", "comfortaa.ttf", "Display")
            loadCustomFont(ctx, "Bebas Neue", "bebas_neue.ttf", "Display")
        }
        
        Log.d("FontManager", "Loaded ${fontList.size} fonts")
    }
    
    private fun loadCustomFont(ctx: Context, name: String, fileName: String, category: String) {
        try {
            val typeface = Typeface.createFromAsset(ctx.assets, "fonts/$fileName")
            fontList.add(FontInfo(name, typeface, category))
        } catch (e: Exception) {
            Log.e("FontManager", "Failed to load font: $fileName", e)
        }
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
