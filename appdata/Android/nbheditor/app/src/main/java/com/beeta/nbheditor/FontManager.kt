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
        
        // Load all fonts from assets
        context?.let { ctx ->
            // Sans Serif Fonts
            loadCustomFont(ctx, "Roboto", "roboto.ttf", "Sans Serif")
            loadCustomFont(ctx, "Open Sans", "open_sans.ttf", "Sans Serif")
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
            loadCustomFont(ctx, "Karla", "karla.ttf", "Sans Serif")
            loadCustomFont(ctx, "PT Sans", "pt_sans.ttf", "Sans Serif")
            loadCustomFont(ctx, "Barlow", "barlow.ttf", "Sans Serif")
            loadCustomFont(ctx, "Exo 2", "exo_2.ttf", "Sans Serif")
            loadCustomFont(ctx, "Cabin", "cabin.ttf", "Sans Serif")
            
            // Serif Fonts
            loadCustomFont(ctx, "Playfair Display", "playfair_display.ttf", "Serif")
            loadCustomFont(ctx, "Roboto Slab", "roboto_slab.ttf", "Serif")
            loadCustomFont(ctx, "Crimson Text", "crimson_text.ttf", "Serif")
            loadCustomFont(ctx, "Merriweather", "merriweather.ttf", "Serif")
            
            // Monospace Fonts
            loadCustomFont(ctx, "JetBrains Mono", "jetbrains_mono.ttf", "Monospace")
            loadCustomFont(ctx, "Source Code Pro", "source_code_pro.ttf", "Monospace")
            loadCustomFont(ctx, "Space Mono", "space_mono.ttf", "Monospace")
            loadCustomFont(ctx, "Inconsolata", "inconsolata.ttf", "Monospace")
            
            // Handwriting Fonts
            loadCustomFont(ctx, "Dancing Script", "dancing_script.ttf", "Handwriting")
            loadCustomFont(ctx, "Pacifico", "pacifico.ttf", "Handwriting")
            loadCustomFont(ctx, "Indie Flower", "indie_flower.ttf", "Handwriting")
            loadCustomFont(ctx, "Caveat", "caveat.ttf", "Handwriting")
            loadCustomFont(ctx, "Permanent Marker", "permanent_marker.ttf", "Handwriting")
            
            // Display Fonts
            loadCustomFont(ctx, "Comfortaa", "comfortaa.ttf", "Display")
            loadCustomFont(ctx, "Bebas Neue", "bebas_neue.ttf", "Display")
            loadCustomFont(ctx, "Righteous", "righteous.ttf", "Display")
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
