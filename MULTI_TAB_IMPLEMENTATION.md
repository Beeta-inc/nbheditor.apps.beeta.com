# Multi-Tab Document System Implementation

## Summary

I've updated the editor layout to include:
1. **Tab bar** - Horizontal scrollable tabs below the toolbar
2. **Status bar** - Bottom bar showing word count, line/column info, file type, and encoding

## Layout Changes Made

### Added Components:
- `tabsScrollView` - Horizontal scroll view for document tabs
- `tabsContainer` - Linear layout to hold tab views
- `statusBar` - Bottom status bar with 4 sections:
  - `statusWordCount` - Shows word count
  - `statusLineInfo` - Shows line and column number
  - `statusFileType` - Shows file type (Plain Text, Markdown, etc.)
  - `statusEncoding` - Shows encoding (UTF-8)

## Implementation Needed in MainActivity.kt

### 1. Document Tab Data Class
```kotlin
data class DocumentTab(
    val id: String = java.util.UUID.randomUUID().toString(),
    val fileName: String = "untitled",
    val fileUri: android.net.Uri? = null,
    val content: String = "",
    val cursorPosition: Int = 0,
    var isModified: Boolean = false
)
```

### 2. Tab Management Variables
```kotlin
private val openDocuments = mutableListOf<DocumentTab>()
private var currentTabIndex = 0
```

### 3. Key Functions to Add

#### createNewTab()
- Creates a new empty document tab
- Adds tab view to tabsContainer
- Switches to the new tab

#### switchToTab(index: Int)
- Saves current tab's content and cursor position
- Loads selected tab's content
- Updates UI to show active tab

#### closeTab(index: Int)
- Prompts to save if modified
- Removes tab from list
- Switches to adjacent tab or creates new if last tab closed

#### createTabView(tab: DocumentTab, index: Int)
- Creates a tab view with:
  - File name label
  - Close button (X)
  - Active/inactive styling

#### updateStatusBar()
- Word count: Split by whitespace
- Line/Column: Calculate from cursor position
- File type: Detect from extension or content
- Encoding: Always UTF-8

### 4. Text Watcher for Status Bar
```kotlin
editorBinding.textArea.addTextChangedListener(object : TextWatcher {
    override fun afterTextChanged(s: Editable?) {
        updateStatusBar()
    }
})

editorBinding.textArea.setOnClickListener {
    updateStatusBar() // Update line/col on cursor move
}
```

## Status Bar Calculations

### Word Count:
```kotlin
val words = text.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
```

### Line/Column:
```kotlin
val cursor = editText.selectionStart
val textBeforeCursor = text.substring(0, cursor)
val line = textBeforeCursor.count { it == '\n' } + 1
val lastNewline = textBeforeCursor.lastIndexOf('\n')
val column = cursor - lastNewline
```

### File Type Detection:
```kotlin
val extension = fileName.substringAfterLast('.', "")
val fileType = when (extension.lowercase()) {
    "md", "markdown" -> "Markdown"
    "txt" -> "Plain Text"
    "java" -> "Java"
    "kt" -> "Kotlin"
    "py" -> "Python"
    "js" -> "JavaScript"
    "html" -> "HTML"
    "css" -> "CSS"
    "json" -> "JSON"
    "xml" -> "XML"
    else -> "Plain Text"
}
```

## Tab Styling

### Active Tab:
- Background: accent_primary color
- Text: white
- Border bottom: 2dp accent color

### Inactive Tab:
- Background: editor_surface
- Text: editor_line_number_text
- Border: none

## Benefits

1. **Multiple Documents**: Work on multiple files simultaneously
2. **Quick Switching**: Tabs for easy navigation
3. **Status Information**: Real-time document statistics
4. **Professional UI**: Similar to VS Code, Sublime Text, etc.

## Size Impact

- Layout changes: ~2KB
- Implementation code: ~15-20KB
- Total APK increase: <50KB

The app will still be well under 50MB (approximately 24-25MB).
