package com.beeta.nbheditor

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
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
        
        // Show/hide tab bar based on number of tabs
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
            setPadding(14, 6, 8, 6)
            background = ContextCompat.getDrawable(
                context,
                if (isActive) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 6
            }
            setOnClickListener { onTabClick(index) }
        }
        
        val titleText = TextView(context).apply {
            text = if (tab.isModified) "● ${tab.title}" else tab.title
            textSize = 12f
            val textColorRes = if (isActive) R.color.tab_active_text else R.color.tab_inactive_text
            setTextColor(ContextCompat.getColor(context, textColorRes))
            if (isActive) {
                setTypeface(null, Typeface.BOLD)
            }
            maxWidth = 240
            isSingleLine = true
            setPadding(0, 0, 8, 0)
        }
        tabLayout.addView(titleText)
        
        // Only show close button if there are multiple tabs
        if (TabManager.hasMultipleTabs()) {
            val closeButton = ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                background = null
                layoutParams = LinearLayout.LayoutParams(28, 28)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val iconColorRes = if (isActive) R.color.tab_active_text else R.color.tab_inactive_text
                setColorFilter(ContextCompat.getColor(context, iconColorRes))
                setPadding(2, 2, 2, 2)
                setOnClickListener { event ->
                    event.stopPropagation()
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
