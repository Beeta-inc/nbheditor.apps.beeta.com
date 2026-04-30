package com.beeta.nbheditor

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class TabUI(
    private val context: Context,
    private val tabsScrollView: HorizontalScrollView,
    private val tabsContainer: LinearLayout,
    private val tabsDivider: View,
    private val onTabClick: (Int) -> Unit,
    private val onTabClose: (Int) -> Unit
) {
    
    fun updateTabs() {
        tabsContainer.removeAllViews()
        val tabs = TabManager.getAllTabs()
        val activeIndex = TabManager.getActiveTabIndex()
        
        // Show/hide tab bar
        if (TabManager.hasMultipleTabs()) {
            tabsScrollView.visibility = View.VISIBLE
            tabsDivider.visibility = View.VISIBLE
        } else {
            tabsScrollView.visibility = View.GONE
            tabsDivider.visibility = View.GONE
        }
        
        tabs.forEachIndexed { index, tab ->
            val tabView = createTabView(tab, index, index == activeIndex)
            tabsContainer.addView(tabView)
        }
    }
    
    private fun createTabView(tab: EditorTab, index: Int, isActive: Boolean): View {
        val tabLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 4, 8)
            background = ContextCompat.getDrawable(
                context,
                if (isActive) R.drawable.bg_glass_card else android.R.color.transparent
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                marginEnd = 4
            }
            setOnClickListener { onTabClick(index) }
        }
        
        val titleText = TextView(context).apply {
            text = if (tab.isModified) "● ${tab.title}" else tab.title
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.editor_text))
            if (isActive) {
                setTypeface(null, Typeface.BOLD)
            }
            maxWidth = 200
            isSingleLine = true
            setPadding(0, 0, 8, 0)
        }
        tabLayout.addView(titleText)
        
        // Only show close button if there are multiple tabs
        if (TabManager.hasMultipleTabs()) {
            val closeButton = ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                background = ContextCompat.getDrawable(context, android.R.drawable.btn_default)
                layoutParams = LinearLayout.LayoutParams(32, 32)
                scaleType = ImageButton.ScaleType.CENTER_INSIDE
                setColorFilter(ContextCompat.getColor(context, R.color.editor_hint))
                setOnClickListener { 
                    it.stopPropagation()
                    onTabClose(index) 
                }
            }
            tabLayout.addView(closeButton)
        }
        
        return tabLayout
    }
    
    private fun View.stopPropagation() {
        isClickable = true
    }
}
