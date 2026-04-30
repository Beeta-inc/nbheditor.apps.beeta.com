package com.beeta.nbheditor

import android.net.Uri

data class EditorTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    var title: String = "untitled.txt",
    var content: String = "",
    var uri: Uri? = null,
    var isModified: Boolean = false,
    var cursorPosition: Int = 0
)

object TabManager {
    private val tabs = mutableListOf<EditorTab>()
    private var activeTabIndex = 0
    
    init {
        // Start with one empty tab
        tabs.add(EditorTab())
    }
    
    fun getAllTabs(): List<EditorTab> = tabs.toList()
    
    fun getActiveTab(): EditorTab = tabs[activeTabIndex]
    
    fun getActiveTabIndex(): Int = activeTabIndex
    
    fun setActiveTab(index: Int) {
        if (index in tabs.indices) {
            activeTabIndex = index
        }
    }
    
    fun addNewTab(title: String = "untitled.txt", content: String = "", uri: Uri? = null): EditorTab {
        val newTab = EditorTab(title = title, content = content, uri = uri)
        tabs.add(newTab)
        activeTabIndex = tabs.size - 1
        return newTab
    }
    
    fun closeTab(index: Int): Boolean {
        if (tabs.size <= 1) return false // Keep at least one tab
        
        tabs.removeAt(index)
        if (activeTabIndex >= tabs.size) {
            activeTabIndex = tabs.size - 1
        } else if (activeTabIndex > index) {
            activeTabIndex--
        }
        return true
    }
    
    fun updateActiveTab(content: String, cursorPosition: Int) {
        tabs[activeTabIndex].apply {
            this.content = content
            this.cursorPosition = cursorPosition
            this.isModified = true
        }
    }
    
    fun markActiveTabSaved(uri: Uri? = null) {
        tabs[activeTabIndex].apply {
            this.isModified = false
            uri?.let { this.uri = it }
        }
    }
    
    fun getTabCount(): Int = tabs.size
    
    fun hasMultipleTabs(): Boolean = tabs.size > 1
}
