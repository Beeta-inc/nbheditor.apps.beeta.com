package com.beeta.nbheditor

import android.content.Context
import android.util.Log
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

data class SessionUser(
    val userId: String = "",
    val userName: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val creator: Boolean = false,
    val cursorPosition: Int = 0,
    val typing: Boolean = false,
    val lastActive: Long = System.currentTimeMillis()
) {
    // Compatibility properties for code that uses isCreator/isTyping
    @get:Exclude
    val isCreator: Boolean get() = creator
    
    @get:Exclude
    val isTyping: Boolean get() = typing
}

data class ChatMessage(
    val messageId: String = "",
    val userId: String = "",
    val userName: String = "",
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isAI: Boolean = false,
    val isImportant: Boolean = false,
    val linkedTaskId: String? = null,
    val reminderId: String? = null,
    val targetType: String = "everyone",
    val targetUserIds: List<String> = emptyList(),
    // attachment fields — null means plain text message
    val attachmentUri: String? = null,   // content URI or file path
    val attachmentType: String? = null,   // "image", "video", "audio", "document"
    val attachmentThumbnail: String? = null  // local path to thumbnail for video
)

data class TaskItem(
    val taskId: String = "",
    val title: String = "",
    val description: String = "",
    val status: String = "next", // "next", "current", "completed"
    val assignedTo: String = "",
    val createdBy: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val order: Int = 0
)

data class ProjectSection(
    val sectionId: String = "",
    val title: String = "",
    val content: String = "",
    val status: String = "next", // "next", "current", "completed"
    val startLine: Int = 0,
    val endLine: Int = 0,
    val assignedTo: String = "",
    val lastModified: Long = System.currentTimeMillis()
)

data class EditorChange(
    val userId: String = "",
    val userName: String = "",
    val position: Int = 0,
    val text: String = "",
    val isDelete: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class CollaborativeSession(
    val sessionId: String = "",
    val creatorId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastActivity: Long = System.currentTimeMillis(),
    val content: String = "",
    val users: Map<String, SessionUser> = emptyMap(),
    val maxUsers: Int = 10,
    val chatMessages: Map<String, ChatMessage> = emptyMap(),
    val tasks: Map<String, TaskItem> = emptyMap(),
    val sections: Map<String, ProjectSection> = emptyMap(),
    val projectTitle: String = "Untitled Project",
    val projectDescription: String = ""
)

object CollaborativeSessionManager {
    private const val TAG = "CollabSession"
    private const val SESSIONS_PATH = "collaborative_sessions"
    private const val SESSION_TIMEOUT = 30 * 60 * 1000L // 30 minutes
    
    // Initialize Firebase Database with fallback URL
    private val database: DatabaseReference by lazy {
        try {
            // Try to get the default instance first
            FirebaseDatabase.getInstance().reference
        } catch (e: Exception) {
            // Fallback: use the confirmed database URL
            Log.w(TAG, "Using fallback database URL: https://nbheditior-default-rtdb.firebaseio.com")
            FirebaseDatabase.getInstance("https://nbheditior-default-rtdb.firebaseio.com").reference
        }
    }
    private var currentSessionId: String? = null
    private var currentUserId: String? = null
    private var currentCreatorId: String? = null  // stored locally at join/create time
    
    // Sanitize user ID for Firebase (replace invalid characters)
    private fun sanitizeUserId(userId: String): String {
        return userId.replace(".", "_")
            .replace("#", "_")
            .replace("$", "_")
            .replace("[", "_")
            .replace("]", "_")
            .replace("/", "_")
    }
    
    // Generate session code: ne + 5 random alphanumeric characters
    private suspend fun generateUniqueSessionCode(): String {
        var attempts = 0
        val maxAttempts = 10
        
        while (attempts < maxAttempts) {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val code = (1..5).map { chars[Random.nextInt(chars.length)] }.joinToString("")
            val sessionId = "NE$code"
            
            // Check if session ID already exists
            val exists = database.child(SESSIONS_PATH).child(sessionId).get().await().exists()
            if (!exists) {
                return sessionId
            }
            
            attempts++
            Log.w(TAG, "Session ID collision detected: $sessionId, retrying... ($attempts/$maxAttempts)")
        }
        
        throw Exception("Failed to generate unique session ID after $maxAttempts attempts")
    }
    
    // Legacy function for backward compatibility
    fun generateSessionCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val code = (1..5).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        return "NE$code"
    }
    
    // Create a new collaborative session
    suspend fun createSession(
        userId: String,
        userName: String,
        email: String,
        initialContent: String = "",
        photoUrl: String = ""
    ): Result<String> {
        return try {
            val sessionId = generateUniqueSessionCode()
            val sanitizedUserId = sanitizeUserId(userId)
            val creator = SessionUser(
                userId = sanitizedUserId,
                userName = userName,
                email = email,
                photoUrl = photoUrl,
                creator = true,
                lastActive = System.currentTimeMillis()
            )
            
            val session = CollaborativeSession(
                sessionId = sessionId,
                creatorId = sanitizedUserId,
                content = initialContent,
                users = mapOf(sanitizedUserId to creator)
            )
            
            database.child(SESSIONS_PATH).child(sessionId).setValue(session).await()
            currentSessionId = sessionId
            currentUserId = sanitizedUserId
            currentCreatorId = sanitizedUserId  // creator is always the one who creates
            
            // Start presence tracking
            startPresenceTracking(sessionId, sanitizedUserId)
            
            Log.d(TAG, "Session created: $sessionId")
            Result.success(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session", e)
            Result.failure(e)
        }
    }
    
    // Join an existing session
    suspend fun joinSession(
        sessionId: String,
        userId: String,
        userName: String,
        email: String,
        photoUrl: String = ""
    ): Result<CollaborativeSession> {
        return try {
            val sanitizedUserId = sanitizeUserId(userId)
            val sessionRef = database.child(SESSIONS_PATH).child(sessionId)
            val snapshot = sessionRef.get().await()
            
            if (!snapshot.exists()) {
                return Result.failure(Exception("Session not found or expired"))
            }
            
            val session = snapshot.getValue(CollaborativeSession::class.java)
                ?: return Result.failure(Exception("Invalid session data"))
            
            if (System.currentTimeMillis() - session.lastActivity > SESSION_TIMEOUT) {
                return Result.failure(Exception("Session expired"))
            }
            
            if (session.users.size >= session.maxUsers) {
                return Result.failure(Exception("Session is full (max ${session.maxUsers} users)"))
            }
            
            val newUser = SessionUser(
                userId = sanitizedUserId,
                userName = userName,
                email = email,
                photoUrl = photoUrl,
                creator = false,
                lastActive = System.currentTimeMillis()
            )
            
            sessionRef.child("users").child(sanitizedUserId).setValue(newUser).await()
            sessionRef.child("lastActivity").setValue(ServerValue.TIMESTAMP).await()
            
            currentSessionId = sessionId
            currentUserId = sanitizedUserId
            currentCreatorId = session.creatorId  // remember who the creator is

            // Start presence tracking
            startPresenceTracking(sessionId, sanitizedUserId)
            
            Log.d(TAG, "Joined session: $sessionId")
            Result.success(session)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to join session", e)
            Result.failure(e)
        }
    }
    
    // Leave current session
    suspend fun leaveSession() {
        try {
            val sessionId = currentSessionId ?: return
            val userId = currentUserId ?: return
            
            val sessionRef = database.child(SESSIONS_PATH).child(sessionId)
            
            // Remove user from session
            sessionRef.child("users").child(userId).removeValue().await()
            
            // Check if session is empty and delete if so
            val snapshot = sessionRef.child("users").get().await()
            if (!snapshot.exists() || !snapshot.hasChildren()) {
                // Last user left - delete entire session
                sessionRef.removeValue().await()
                Log.d(TAG, "Session deleted (no users left): $sessionId")
            }
            
            // Clear local state
            currentSessionId = null
            currentUserId = null
            currentCreatorId = null
            
            Log.d(TAG, "Left session: $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to leave session", e)
        }
    }
    
    suspend fun updateContent(content: String) {
        try {
            val sessionId = currentSessionId ?: return
            database.child(SESSIONS_PATH).child(sessionId).child("content").setValue(content).await()
            database.child(SESSIONS_PATH).child(sessionId).child("lastActivity")
                .setValue(ServerValue.TIMESTAMP).await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) return  // debounce cancel — ignore
            Log.e(TAG, "Failed to update content", e)
        }
    }

    suspend fun updateCursorPosition(position: Int, isTyping: Boolean) {
        try {
            val sessionId = currentSessionId ?: return
            val userId = currentUserId ?: return
            val updates = mapOf(
                "cursorPosition" to position,
                "typing" to isTyping,
                "lastActive" to ServerValue.TIMESTAMP
            )
            database.child(SESSIONS_PATH).child(sessionId)
                .child("users").child(userId).updateChildren(updates).await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) return  // debounce cancel — ignore
            Log.e(TAG, "Failed to update cursor", e)
        }
    }
    
    // Listen to session changes
    fun observeSession(sessionId: String): Flow<CollaborativeSession?> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val session = snapshot.getValue(CollaborativeSession::class.java)
                trySend(session)
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Session observation cancelled", error.toException())
                close(error.toException())
            }
        }
        
        val ref = database.child(SESSIONS_PATH).child(sessionId)
        ref.addValueEventListener(listener)
        
        awaitClose { ref.removeEventListener(listener) }
    }
    
    // Listen to content changes
    fun observeContent(sessionId: String): Flow<String> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val content = snapshot.getValue(String::class.java) ?: ""
                trySend(content)
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Content observation cancelled", error.toException())
                close(error.toException())
            }
        }
        
        val ref = database.child(SESSIONS_PATH).child(sessionId).child("content")
        ref.addValueEventListener(listener)
        
        awaitClose { ref.removeEventListener(listener) }
    }
    
    // Listen to users changes
    fun observeUsers(sessionId: String): Flow<Map<String, SessionUser>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val users = mutableMapOf<String, SessionUser>()
                snapshot.children.forEach { child ->
                    child.getValue(SessionUser::class.java)?.let { user ->
                        users[child.key ?: ""] = user
                    }
                }
                trySend(users)
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Users observation cancelled", error.toException())
                close(error.toException())
            }
        }
        
        val ref = database.child(SESSIONS_PATH).child(sessionId).child("users")
        ref.addValueEventListener(listener)
        
        awaitClose { ref.removeEventListener(listener) }
    }
    
    // Kick user (creator only)
    suspend fun kickUser(sessionId: String, userId: String): Result<Unit> {
        return try {
            val sanitizedUserId = sanitizeUserId(userId)
            // Use locally stored creatorId — no Firebase read needed
            if (currentUserId != currentCreatorId) {
                return Result.failure(Exception("Only creator can kick users"))
            }
            // Write kicked flag first so the target client detects it
            database.child(SESSIONS_PATH).child(sessionId)
                .child("kicked").child(sanitizedUserId).setValue(true).await()
            // Then remove from users
            database.child(SESSIONS_PATH).child(sessionId)
                .child("users").child(sanitizedUserId).removeValue().await()
            Log.d(TAG, "Kicked user: $sanitizedUserId from $sessionId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kick user", e)
            Result.failure(e)
        }
    }

    // Observe whether the current user has been kicked
    fun observeKicked(sessionId: String, userId: String): Flow<Boolean> = callbackFlow {
        val sanitized = sanitizeUserId(userId)
        val ref = database.child(SESSIONS_PATH).child(sessionId).child("kicked").child(sanitized)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(snapshot.getValue(Boolean::class.java) == true)
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }
    
    // End session (creator only) — deletes entire session from Firebase
    suspend fun endSession(sessionId: String): Result<Unit> {
        return try {
            if (currentUserId != currentCreatorId) {
                return Result.failure(Exception("Only creator can end session"))
            }
            // Hard delete the entire session node
            database.child(SESSIONS_PATH).child(sessionId).removeValue().await()
            Log.d(TAG, "Session deleted from Firebase: $sessionId")
            currentSessionId = null
            currentUserId = null
            currentCreatorId = null
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end session", e)
            Result.failure(e)
        }
    }
    
    // Get current user info
    private suspend fun getCurrentUser(sessionId: String): SessionUser? {
        return try {
            val userId = currentUserId ?: return null
            val snapshot = database.child(SESSIONS_PATH).child(sessionId)
                .child("users").child(userId).get().await()
            snapshot.getValue(SessionUser::class.java)
        } catch (e: Exception) {
            null
        }
    }
    
    // Start presence tracking
    private fun startPresenceTracking(sessionId: String, userId: String) {
        val userRef = database.child(SESSIONS_PATH).child(sessionId).child("users").child(userId)
        
        // Update last active timestamp every 10 seconds
        val presenceRef = database.child(".info/connected")
        presenceRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    userRef.child("lastActive").setValue(ServerValue.TIMESTAMP)
                    userRef.child("lastActive").onDisconnect().setValue(ServerValue.TIMESTAMP)
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Presence tracking cancelled", error.toException())
            }
        })
    }
    
    // Check if user is in a session
    fun isInSession(): Boolean = currentSessionId != null
    
    // Get current session ID
    fun getCurrentSessionId(): String? = currentSessionId
    
    // Get current user ID
    fun getCurrentUserId(): String? = currentUserId
    
    // ── Chat Functions ────────────────────────────────────────────────────────
    
    // Send a chat message
    suspend fun sendChatMessage(
        message: String,
        isAI: Boolean = false,
        isImportant: Boolean = false,
        linkedTaskId: String? = null,
        targetType: String = "everyone",
        targetUserIds: List<String> = emptyList(),
        attachmentUri: String? = null,
        attachmentType: String? = null
    ): Result<String> {
        return try {
            val sessionId = currentSessionId ?: return Result.failure(Exception("Not in a session"))
            val userId = currentUserId ?: return Result.failure(Exception("User ID not found"))
            
            val userSnapshot = database.child(SESSIONS_PATH).child(sessionId)
                .child("users").child(userId).get().await()
            val user = userSnapshot.getValue(SessionUser::class.java)
                ?: return Result.failure(Exception("User not found"))
            
            val messageId = database.child(SESSIONS_PATH).child(sessionId)
                .child("chatMessages").push().key ?: return Result.failure(Exception("Failed to generate message ID"))
            
            val chatMessage = ChatMessage(
                messageId = messageId,
                userId = userId,
                userName = user.userName,
                message = message,
                timestamp = System.currentTimeMillis(),
                isAI = isAI,
                isImportant = isImportant,
                linkedTaskId = linkedTaskId,
                targetType = targetType,
                targetUserIds = targetUserIds,
                attachmentUri = attachmentUri,
                attachmentType = attachmentType
            )
            
            database.child(SESSIONS_PATH).child(sessionId)
                .child("chatMessages").child(messageId).setValue(chatMessage).await()
            
            Result.success(messageId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send chat message", e)
            Result.failure(e)
        }
    }
    
    // Observe chat messages
    fun observeChatMessages(sessionId: String): Flow<List<ChatMessage>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val messages = mutableListOf<ChatMessage>()
                snapshot.children.forEach { child ->
                    child.getValue(ChatMessage::class.java)?.let { messages.add(it) }
                }
                trySend(messages.sortedBy { it.timestamp })
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Chat observation cancelled", error.toException())
                close(error.toException())
            }
        }
        
        val ref = database.child(SESSIONS_PATH).child(sessionId).child("chatMessages")
        ref.addValueEventListener(listener)
        
        awaitClose { ref.removeEventListener(listener) }
    }
    
    // ── Task Functions ────────────────────────────────────────────────────────
    
    // Create a task
    suspend fun createTask(title: String, description: String, status: String = "next"): Result<String> {
        return try {
            val sessionId = currentSessionId ?: return Result.failure(Exception("Not in a session"))
            val userId = currentUserId ?: return Result.failure(Exception("User ID not found"))
            
            val taskId = database.child(SESSIONS_PATH).child(sessionId)
                .child("tasks").push().key ?: return Result.failure(Exception("Failed to generate task ID"))
            
            // Get current task count for ordering
            val tasksSnapshot = database.child(SESSIONS_PATH).child(sessionId)
                .child("tasks").get().await()
            val order = tasksSnapshot.childrenCount.toInt()
            
            val task = TaskItem(
                taskId = taskId,
                title = title,
                description = description,
                status = status,
                createdBy = userId,
                order = order
            )
            
            database.child(SESSIONS_PATH).child(sessionId)
                .child("tasks").child(taskId).setValue(task).await()
            
            Result.success(taskId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create task", e)
            Result.failure(e)
        }
    }
    
    // Update task status
    suspend fun updateTaskStatus(taskId: String, status: String): Result<Unit> {
        return try {
            val sessionId = currentSessionId ?: return Result.failure(Exception("Not in a session"))
            
            val updates = mutableMapOf<String, Any>("status" to status)
            if (status == "completed") {
                updates["completedAt"] = ServerValue.TIMESTAMP
            }
            
            database.child(SESSIONS_PATH).child(sessionId)
                .child("tasks").child(taskId).updateChildren(updates).await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update task status", e)
            Result.failure(e)
        }
    }
    
    // Assign task to user
    suspend fun assignTask(taskId: String, userId: String): Result<Unit> {
        return try {
            val sessionId = currentSessionId ?: return Result.failure(Exception("Not in a session"))
            
            database.child(SESSIONS_PATH).child(sessionId)
                .child("tasks").child(taskId).child("assignedTo").setValue(userId).await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to assign task", e)
            Result.failure(e)
        }
    }
    
    // Delete task
    suspend fun deleteTask(taskId: String): Result<Unit> {
        return try {
            val sessionId = currentSessionId ?: return Result.failure(Exception("Not in a session"))
            
            database.child(SESSIONS_PATH).child(sessionId)
                .child("tasks").child(taskId).removeValue().await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete task", e)
            Result.failure(e)
        }
    }
    
    // Observe tasks
    fun observeTasks(sessionId: String): Flow<List<TaskItem>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val tasks = mutableListOf<TaskItem>()
                snapshot.children.forEach { child ->
                    child.getValue(TaskItem::class.java)?.let { tasks.add(it) }
                }
                trySend(tasks.sortedBy { it.order })
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Tasks observation cancelled", error.toException())
                close(error.toException())
            }
        }
        
        val ref = database.child(SESSIONS_PATH).child(sessionId).child("tasks")
        ref.addValueEventListener(listener)
        
        awaitClose { ref.removeEventListener(listener) }
    }
    
    // ── Section Functions ─────────────────────────────────────────────────────
    
    // Create a section
    suspend fun createSection(title: String, content: String, startLine: Int, endLine: Int): Result<String> {
        return try {
            val sessionId = currentSessionId ?: return Result.failure(Exception("Not in a session"))
            
            val sectionId = database.child(SESSIONS_PATH).child(sessionId)
                .child("sections").push().key ?: return Result.failure(Exception("Failed to generate section ID"))
            
            val section = ProjectSection(
                sectionId = sectionId,
                title = title,
                content = content,
                startLine = startLine,
                endLine = endLine
            )
            
            database.child(SESSIONS_PATH).child(sessionId)
                .child("sections").child(sectionId).setValue(section).await()
            
            Result.success(sectionId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create section", e)
            Result.failure(e)
        }
    }
    
    // Update section status
    suspend fun updateSectionStatus(sectionId: String, status: String): Result<Unit> {
        return try {
            val sessionId = currentSessionId ?: return Result.failure(Exception("Not in a session"))
            
            database.child(SESSIONS_PATH).child(sessionId)
                .child("sections").child(sectionId).child("status").setValue(status).await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update section status", e)
            Result.failure(e)
        }
    }
    
    // Convert chat message to task
    suspend fun createTaskFromMessage(messageId: String): Result<String> {
        return try {
            val sessionId = currentSessionId ?: return Result.failure(Exception("Not in a session"))
            
            val messageSnapshot = database.child(SESSIONS_PATH).child(sessionId)
                .child("chatMessages").child(messageId).get().await()
            val message = messageSnapshot.getValue(ChatMessage::class.java)
                ?: return Result.failure(Exception("Message not found"))
            
            val result = createTask(message.message, "", "next")
            if (result.isSuccess) {
                val taskId = result.getOrNull()!!
                database.child(SESSIONS_PATH).child(sessionId)
                    .child("chatMessages").child(messageId)
                    .child("linkedTaskId").setValue(taskId).await()
                Result.success(taskId)
            } else {
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create task from message", e)
            Result.failure(e)
        }
    }
    
    // Mark message as important
    suspend fun markMessageImportant(messageId: String, important: Boolean): Result<Unit> {
        return try {
            val sessionId = currentSessionId ?: return Result.failure(Exception("Not in a session"))
            
            database.child(SESSIONS_PATH).child(sessionId)
                .child("chatMessages").child(messageId)
                .child("isImportant").setValue(important).await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark message", e)
            Result.failure(e)
        }
    }
    
    // Set reminder for message
    suspend fun setMessageReminder(messageId: String, reminderTime: Long): Result<String> {
        return try {
            val sessionId = currentSessionId ?: return Result.failure(Exception("Not in a session"))
            val userId = currentUserId ?: return Result.failure(Exception("User ID not found"))
            
            val reminderId = "reminder_${System.currentTimeMillis()}"
            
            val reminderData = mapOf(
                "messageId" to messageId,
                "time" to reminderTime,
                "userId" to userId
            )
            
            database.child(SESSIONS_PATH).child(sessionId)
                .child("reminders").child(reminderId).setValue(reminderData).await()
            
            database.child(SESSIONS_PATH).child(sessionId)
                .child("chatMessages").child(messageId)
                .child("reminderId").setValue(reminderId).await()
            
            Result.success(reminderId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set reminder", e)
            Result.failure(e)
        }
    }
    
    // Ask AI in chat context - integrated with existing AI system
    suspend fun askAIInChat(question: String, context: String, aiCallback: suspend (String) -> String?): Result<String> {
        return try {
            val aiResponse = aiCallback(question) ?: "Sorry, I couldn't process that request."
            // Use fixed AI userId so it never matches any real user
            val sessionId = currentSessionId ?: return Result.failure(Exception("Not in a session"))
            val messageId = database.child(SESSIONS_PATH).child(sessionId)
                .child("chatMessages").push().key ?: return Result.failure(Exception("Failed to generate message ID"))
            val chatMessage = ChatMessage(
                messageId = messageId,
                userId = "__ai__",
                userName = "Beeta AI",
                message = aiResponse,
                timestamp = System.currentTimeMillis(),
                isAI = true
            )
            database.child(SESSIONS_PATH).child(sessionId)
                .child("chatMessages").child(messageId).setValue(chatMessage).await()
            Result.success(aiResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ask AI", e)
            Result.failure(e)
        }
    }
    
    // Observe sections
    fun observeSections(sessionId: String): Flow<List<ProjectSection>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sections = mutableListOf<ProjectSection>()
                snapshot.children.forEach { child ->
                    child.getValue(ProjectSection::class.java)?.let { sections.add(it) }
                }
                trySend(sections.sortedBy { it.startLine })
            }
            
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Sections observation cancelled", error.toException())
                close(error.toException())
            }
        }
        
        val ref = database.child(SESSIONS_PATH).child(sessionId).child("sections")
        ref.addValueEventListener(listener)
        
        awaitClose { ref.removeEventListener(listener) }
    }
    
    // Save session content to temp cache
    fun saveToTempCache(context: Context, content: String) {
        try {
            val cacheFile = java.io.File(context.cacheDir, "collab_session_temp.txt")
            cacheFile.writeText(content)
            Log.d(TAG, "Saved to temp cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to temp cache", e)
        }
    }
    
    // Load from temp cache
    fun loadFromTempCache(context: Context): String? {
        return try {
            val cacheFile = java.io.File(context.cacheDir, "collab_session_temp.txt")
            if (cacheFile.exists()) {
                cacheFile.readText()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from temp cache", e)
            null
        }
    }
    
    // Clear temp cache
    fun clearTempCache(context: Context) {
        try {
            val cacheFile = java.io.File(context.cacheDir, "collab_session_temp.txt")
            if (cacheFile.exists()) {
                cacheFile.delete()
                Log.d(TAG, "Cleared temp cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear temp cache", e)
        }
    }
    
    // Clear all session data (call when ending/leaving session)
    fun clearSessionCache(context: Context) {
        clearTempCache(context)
        currentSessionId = null
        currentUserId = null
        currentCreatorId = null
        Log.d(TAG, "Cleared all session cache")
    }
}
