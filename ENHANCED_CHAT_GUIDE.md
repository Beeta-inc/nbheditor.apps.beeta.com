# Enhanced Collaborative Chat System - Implementation Guide

## Overview
This guide shows how to integrate the **planning-focused chat system** into MainActivity. The chat is designed for work discussion, task coordination, and progress tracking.

---

## Key Features

### 1. Basic Messaging
- Real-time chat with team members
- AI assistant integration
- Message history

### 2. Planning Integration
- **Convert messages to tasks** - Turn discussions into actionable items
- **Mark important messages** - Highlight key decisions
- **Set reminders** - Never forget important discussions

### 3. Work Coordination
- Typing indicators
- Active status display
- Quick task creation

---

## Implementation Steps

### Step 1: Add Chat Dialog Function to MainActivity

```kotlin
private fun showCollabChatDialog() {
    val sessionCode = CollaborativeSessionManager.getCurrentSessionId() ?: return
    val currentUserId = CollaborativeSessionManager.getCurrentUserId() ?: return
    
    val dialogView = LayoutInflater.from(this).inflate(R.layout.fragment_collab_chat, null)
    val dialog = AlertDialog.Builder(this)
        .setView(dialogView)
        .create()
    
    // Initialize views
    val rvChatMessages = dialogView.findViewById<RecyclerView>(R.id.rvChatMessages)
    val etChatMessage = dialogView.findViewById<EditText>(R.id.etChatMessage)
    val btnSendMessage = dialogView.findViewById<ImageButton>(R.id.btnSendMessage)
    val btnAskAI = dialogView.findViewById<Button>(R.id.btnAskAI)
    val btnCreateTaskFromChat = dialogView.findViewById<Button>(R.id.btnCreateTaskFromChat)
    val tvTypingIndicator = dialogView.findViewById<TextView>(R.id.tvTypingIndicator)
    val tvChatStatus = dialogView.findViewById<TextView>(R.id.tvChatStatus)
    
    // Setup RecyclerView
    val adapter = CollabChatAdapter(
        messages = emptyList(),
        currentUserId = currentUserId,
        onMarkImportant = { message ->
            lifecycleScope.launch {
                val result = CollaborativeSessionManager.markMessageImportant(
                    message.messageId,
                    !message.isImportant
                )
                if (result.isSuccess) {
                    Toast.makeText(this@MainActivity, 
                        if (message.isImportant) "Unmarked" else "⭐ Marked important", 
                        Toast.LENGTH_SHORT).show()
                }
            }
        },
        onCreateTask = { message ->
            lifecycleScope.launch {
                val result = CollaborativeSessionManager.createTaskFromMessage(message.messageId)
                if (result.isSuccess) {
                    Toast.makeText(this@MainActivity, "✓ Task created", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to create task", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onSetReminder = { message ->
            // Show time picker for reminder
            showReminderTimePicker(message)
        }
    )
    
    rvChatMessages.layoutManager = LinearLayoutManager(this)
    rvChatMessages.adapter = adapter
    
    // Observe messages
    lifecycleScope.launch {
        CollaborativeSessionManager.observeChatMessages(sessionCode).collect { messages ->
            adapter.updateMessages(messages)
            rvChatMessages.scrollToPosition(messages.size - 1)
        }
    }
    
    // Send message
    btnSendMessage.setOnClickListener {
        val message = etChatMessage.text.toString().trim()
        if (message.isNotEmpty()) {
            lifecycleScope.launch {
                val result = CollaborativeSessionManager.sendChatMessage(message)
                if (result.isSuccess) {
                    etChatMessage.text.clear()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to send", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    // Ask AI
    btnAskAI.setOnClickListener {
        val question = etChatMessage.text.toString().trim()
        if (question.isNotEmpty()) {
            lifecycleScope.launch {
                // Get editor context
                val editorContent = binding.editorView.text.toString()
                val result = CollaborativeSessionManager.askAIInChat(question, editorContent)
                if (result.isSuccess) {
                    etChatMessage.text.clear()
                    Toast.makeText(this@MainActivity, "🤖 AI responding...", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "Type a question first", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Create task from selected message
    btnCreateTaskFromChat.setOnClickListener {
        val message = etChatMessage.text.toString().trim()
        if (message.isNotEmpty()) {
            lifecycleScope.launch {
                val result = CollaborativeSessionManager.createTask(message, "", "next")
                if (result.isSuccess) {
                    etChatMessage.text.clear()
                    Toast.makeText(this@MainActivity, "✓ Task created", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    // Typing indicator
    etChatMessage.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            // Update typing status
            lifecycleScope.launch {
                CollaborativeSessionManager.updateCursorPosition(0, s?.isNotEmpty() == true)
            }
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
    
    dialog.show()
}

private fun showReminderTimePicker(message: ChatMessage) {
    val options = arrayOf("In 1 hour", "In 3 hours", "Tomorrow", "Custom")
    AlertDialog.Builder(this)
        .setTitle("Set Reminder")
        .setItems(options) { _, which ->
            val reminderTime = when (which) {
                0 -> System.currentTimeMillis() + 3600000 // 1 hour
                1 -> System.currentTimeMillis() + 10800000 // 3 hours
                2 -> System.currentTimeMillis() + 86400000 // 24 hours
                else -> return@setItems
            }
            
            lifecycleScope.launch {
                val result = CollaborativeSessionManager.setMessageReminder(
                    message.messageId,
                    reminderTime
                )
                if (result.isSuccess) {
                    Toast.makeText(this@MainActivity, "⏰ Reminder set", Toast.LENGTH_SHORT).show()
                }
            }
        }
        .show()
}
```

### Step 2: Update Session Controls Menu

In your existing `showSessionControlsMenu()` function, update the chat option:

```kotlin
private fun showSessionControlsMenu() {
    val options = arrayOf(
        "🔗 Copy Session Code",
        "👥 View Users",
        "💬 Team Chat",  // Updated
        "🗺️ View Roadmap",
        "📋 Copy Content",
        "🚪 Leave Session"
    )
    
    AlertDialog.Builder(this)
        .setTitle("Session Controls")
        .setItems(options) { _, which ->
            when (which) {
                0 -> copySessionCode()
                1 -> showSessionUsersDialog()
                2 -> showCollabChatDialog()  // Call new chat dialog
                3 -> showCollabRoadmapDialog()
                4 -> copySessionContent()
                5 -> leaveCollaborativeSession()
            }
        }
        .show()
}
```

---

## Usage Flow

### For Team Members

1. **Open Chat**: Click 💬 Chat in session controls
2. **Send Messages**: Type and send messages to team
3. **Long Press Message**: Show quick actions (Mark, Task, Remind)
4. **Create Task**: Convert discussion into actionable task
5. **Ask AI**: Get AI assistance in chat context

### For Planning

1. **Discuss Work**: "Let's write the introduction first"
2. **Convert to Task**: Long press → "✓ Task"
3. **Mark Important**: Long press → "⭐ Mark" for key decisions
4. **Set Reminder**: Long press → "⏰ Remind" for follow-ups

---

## Chat System Philosophy

### What Makes This Chat Different

❌ **NOT** just messaging  
✅ **Planning + coordination workspace**

### Design Principles

1. **Help work, don't distract** - Lightweight and focused
2. **Discussion → Action** - Easy conversion to tasks
3. **Context-aware** - AI understands project context
4. **Progress tracking** - See what's done, what's next

---

## Advanced Features (Optional)

### AI Integration

The `askAIInChat()` function is a placeholder. Integrate with your existing AI system:

```kotlin
suspend fun askAIInChat(question: String, context: String): Result<String> {
    return try {
        // TODO: Call your AI API with context
        val aiResponse = yourAIService.ask(question, context)
        sendChatMessage(aiResponse, isAI = true)
        Result.success(aiResponse)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

### Reminder Notifications

Implement reminder system using WorkManager or AlarmManager to notify users when reminders trigger.

---

## Testing Checklist

- [ ] Send and receive messages in real-time
- [ ] AI assistant responds correctly
- [ ] Long press shows action menu
- [ ] Mark important works
- [ ] Create task from message works
- [ ] Set reminder works
- [ ] Typing indicator shows
- [ ] Status indicator updates
- [ ] Messages persist across sessions

---

## Summary

This chat system transforms collaboration from simple messaging into a **planning and coordination workspace**. Users can:

- Discuss work naturally
- Convert discussions into tasks
- Mark important decisions
- Set reminders for follow-ups
- Get AI assistance in context

The result: **Better coordination, clearer planning, faster progress**.
