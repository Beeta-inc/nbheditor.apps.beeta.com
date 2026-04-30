package com.beeta.nbheditor

import android.content.Context
import android.util.Log
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.*
import kotlin.random.Random

data class SessionUser(
    val userId: String = "",
    val userName: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val creator: Boolean = false,
    val cursorPosition: Int = 0,
    val typing: Boolean = false,
    val lastActive: Long = System.currentTimeMillis(),
    val status: String = "active" // active, waiting, rejected
) {
    // Compatibility properties for code that uses isCreator/isTyping
    @get:Exclude
    val isCreator: Boolean get() = creator
    
    @get:Exclude
    val isTyping: Boolean get() = typing
}

data class JoinRequest(
    val userId: String = "",
    val userName: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val requestTime: Long = System.currentTimeMillis(),
    val status: String = "pending" // pending, approved, rejected
)

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
    val attachmentThumbnail: String? = null,  // local path to thumbnail for video
    // reply fields
    val replyToMessageId: String? = null,
    val replyToUserName: String? = null,
    val replyToMessage: String? = null
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
    internal var currentSessionId: String? = null
    internal var currentUserId: String? = null
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
    
    // Request to join session (for host approval)
    suspend fun requestToJoinSession(
        sessionId: String,
        userId: String,
        userName: String,
        email: String,
        photoUrl: String = ""
    ): Result<String> {
        return try {
            val sanitizedUserId = sanitizeUserId(userId)
            val sessionRef = database.child(SESSIONS_PATH).child(sessionId)
            val snapshot = sessionRef.get().await()
            
            if (!snapshot.exists()) {
                return Result.failure(Exception("Session not found"))
            }
            
            val joinRequest = JoinRequest(
                userId = sanitizedUserId,
                userName = userName,
                email = email,
                photoUrl = photoUrl,
                requestTime = System.currentTimeMillis(),
                status = "pending"
            )
            
            sessionRef.child("joinRequests").child(sanitizedUserId).setValue(joinRequest).await()
            
            Log.d(TAG, "Join request sent for session: $sessionId")
            Result.success("pending")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send join request", e)
            Result.failure(e)
        }
    }
    
    // Approve join request (host only)
    suspend fun approveJoinRequest(sessionId: String, userId: String): Result<Unit> {
        return try {
            val sanitizedUserId = sanitizeUserId(userId)
            val sessionRef = database.child(SESSIONS_PATH).child(sessionId)
            val requestRef = sessionRef.child("joinRequests").child(sanitizedUserId)
            
            val requestSnapshot = requestRef.get().await()
            if (!requestSnapshot.exists()) {
                return Result.failure(Exception("Join request not found"))
            }
            
            val request = requestSnapshot.getValue(JoinRequest::class.java)
                ?: return Result.failure(Exception("Invalid request data"))
            
            // Clear any existing kicked flag first
            sessionRef.child("kicked").child(sanitizedUserId).removeValue().await()
            
            // Add user to session
            val newUser = SessionUser(
                userId = request.userId,
                userName = request.userName,
                email = request.email,
                photoUrl = request.photoUrl,
                creator = false,
                lastActive = System.currentTimeMillis(),
                status = "active"
            )
            
            sessionRef.child("users").child(sanitizedUserId).setValue(newUser).await()
            
            // Update request status
            requestRef.child("status").setValue("approved").await()
            
            // Remove request after 5 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                requestRef.removeValue()
            }, 5000)
            
            Log.d(TAG, "Approved join request for user: $sanitizedUserId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to approve join request", e)
            Result.failure(e)
        }
    }
    
    // Reject join request (host only)
    suspend fun rejectJoinRequest(sessionId: String, userId: String): Result<Unit> {
        return try {
            val sanitizedUserId = sanitizeUserId(userId)
            val sessionRef = database.child(SESSIONS_PATH).child(sessionId)
            val requestRef = sessionRef.child("joinRequests").child(sanitizedUserId)
            
            requestRef.child("status").setValue("rejected").await()
            
            // Remove request after 2 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                requestRef.removeValue()
            }, 2000)
            
            Log.d(TAG, "Rejected join request for user: $sanitizedUserId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reject join request", e)
            Result.failure(e)
        }
    }
    
    // Observe join requests (for host)
    fun observeJoinRequests(sessionId: String): kotlinx.coroutines.flow.Flow<List<JoinRequest>> = kotlinx.coroutines.flow.callbackFlow {
        val requestsRef = database.child(SESSIONS_PATH).child(sessionId).child("joinRequests")
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val requests = mutableListOf<JoinRequest>()
                for (child in snapshot.children) {
                    child.getValue(JoinRequest::class.java)?.let { requests.add(it) }
                }
                trySend(requests.filter { it.status == "pending" })
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        requestsRef.addValueEventListener(listener)
        awaitClose { requestsRef.removeEventListener(listener) }
    }
    
    // Check join request status (for requester)
    fun observeJoinRequestStatus(sessionId: String, userId: String): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.callbackFlow {
        val sanitizedUserId = sanitizeUserId(userId)
        val requestRef = database.child(SESSIONS_PATH).child(sessionId).child("joinRequests").child(sanitizedUserId)
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend("not_found")
                    return
                }
                val request = snapshot.getValue(JoinRequest::class.java)
                trySend(request?.status ?: "unknown")
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        requestRef.addValueEventListener(listener)
        awaitClose { requestRef.removeEventListener(listener) }
    }
    
    // Check if host is online
    fun isHostOnline(sessionId: String): kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.callbackFlow {
        val sessionRef = database.child(SESSIONS_PATH).child(sessionId)
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val session = snapshot.getValue(CollaborativeSession::class.java)
                if (session != null) {
                    val hostUser = session.users[session.creatorId]
                    val isOnline = hostUser != null && 
                        (System.currentTimeMillis() - hostUser.lastActive) < 30000 // 30 seconds
                    trySend(isOnline)
                } else {
                    trySend(false)
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        sessionRef.addValueEventListener(listener)
        awaitClose { sessionRef.removeEventListener(listener) }
    }
    
    // Leave current session
    suspend fun leaveSession(clearLocal: Boolean = true) {
        try {
            val sessionId = currentSessionId ?: return
            val userId = currentUserId ?: return
            
            val sanitizedUserId = sanitizeUserId(userId)
            val sessionRef = database.child(SESSIONS_PATH).child(sessionId)
            
            // Remove user from session
            sessionRef.child("users").child(sanitizedUserId).removeValue().await()
            
            // Check if session is empty and delete if so
            val snapshot = sessionRef.child("users").get().await()
            if (!snapshot.exists() || !snapshot.hasChildren()) {
                // Last user left - delete entire session
                sessionRef.removeValue().await()
                Log.d(TAG, "Session deleted (no users left): $sessionId")
            }
            
            // Clear local state
            if (clearLocal) {
                currentSessionId = null
                currentUserId = null
                currentCreatorId = null
            }
            
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
            val sanitizedUserId = sanitizeUserId(userId)
            val updates = mapOf(
                "cursorPosition" to position,
                "typing" to isTyping,
                "lastActive" to ServerValue.TIMESTAMP
            )
            database.child(SESSIONS_PATH).child(sessionId)
                .child("users").child(sanitizedUserId).updateChildren(updates).await()
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
                val kicked = snapshot.getValue(Boolean::class.java) == true
                if (kicked) {
                    Log.d(TAG, "User $sanitized has been kicked from session $sessionId")
                }
                trySend(kicked)
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }
    
    // Observe if session still exists (for detecting when host ends session)
    fun observeSessionExists(sessionId: String): Flow<Boolean> = callbackFlow {
        val ref = database.child(SESSIONS_PATH).child(sessionId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val exists = snapshot.exists()
                if (!exists) {
                    Log.d(TAG, "Session $sessionId no longer exists (ended by host)")
                }
                trySend(exists)
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
        val sanitizedUserId = sanitizeUserId(userId)
        val userRef = database.child(SESSIONS_PATH).child(sessionId).child("users").child(sanitizedUserId)
        
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
    
    fun getCurrentSessionId(): String? = currentSessionId
    
    fun getCurrentUserId(): String? = currentUserId
    
    // Get current session data
    suspend fun getCurrentSession(): CollaborativeSession? {
        return try {
            val sessionId = currentSessionId ?: return null
            val snapshot = database.child(SESSIONS_PATH).child(sessionId).get().await()
            snapshot.getValue(CollaborativeSession::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current session", e)
            null
        }
    }
    
    // ── Typing Indicator ─────────────────────────────────────────────────────
    
    suspend fun setTypingInChat(isTyping: Boolean) {
        val sessionId = currentSessionId ?: return
        val userId = currentUserId ?: return
        try {
            if (isTyping) {
                database.child(SESSIONS_PATH).child(sessionId)
                    .child("chatTyping").child(userId).setValue(true).await()
            } else {
                // Remove the key entirely when not typing
                database.child(SESSIONS_PATH).child(sessionId)
                    .child("chatTyping").child(userId).removeValue().await()
            }
        } catch (e: Exception) { Log.e(TAG, "setTypingInChat failed", e) }
    }
    
    fun observeChatTyping(sessionId: String, currentUserId: String): Flow<List<String>> = callbackFlow {
        val ref = database.child(SESSIONS_PATH).child(sessionId).child("chatTyping")
        val usersRef = database.child(SESSIONS_PATH).child(sessionId).child("users")
        
        val listener = ref.addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val typingUserIds = snapshot.children.mapNotNull { child ->
                    if (child.key != currentUserId) child.key else null
                }
                
                // Map user IDs to usernames
                if (typingUserIds.isEmpty()) {
                    trySend(emptyList())
                } else {
                    usersRef.get().addOnSuccessListener { usersSnapshot ->
                        val userNames = typingUserIds.mapNotNull { userId ->
                            val userSnapshot = usersSnapshot.child(userId)
                            if (userSnapshot.exists()) {
                                userSnapshot.child("userName").getValue(String::class.java)
                                    ?: userId.substringBefore("@") // Fallback to email username
                            } else {
                                userId.substringBefore("@") // Fallback to email username
                            }
                        }
                        trySend(userNames)
                    }.addOnFailureListener {
                        // Fallback: extract username from email
                        val userNames = typingUserIds.map { it.substringBefore("@") }
                        trySend(userNames)
                    }
                }
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) { trySend(emptyList()) }
        })
        awaitClose { ref.removeEventListener(listener) }
    }
    
    // ── Chat Functions ────────────────────────────────────────────────────────
    
    // Send a chat message with file upload support
    suspend fun sendChatMessage(
        message: String,
        isAI: Boolean = false,
        isImportant: Boolean = false,
        linkedTaskId: String? = null,
        targetType: String = "everyone",
        targetUserIds: List<String> = emptyList(),
        attachmentUri: String? = null,
        attachmentType: String? = null,
        attachmentFileName: String? = null,
        onProgress: ((Int) -> Unit)? = null,
        replyToMessageId: String? = null,
        replyToUserName: String? = null,
        replyToMessage: String? = null
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
            
            // If there's an attachment, upload it to Firebase Storage first
            var finalAttachmentUri = attachmentUri
            if (!attachmentUri.isNullOrBlank() && !attachmentType.isNullOrBlank()) {
                onProgress?.invoke(0)
                val uploadResult = uploadFileToStorage(sessionId, messageId, attachmentUri, attachmentType, attachmentFileName, onProgress)
                if (uploadResult.isSuccess) {
                    finalAttachmentUri = uploadResult.getOrNull()
                    onProgress?.invoke(100)
                } else {
                    return Result.failure(uploadResult.exceptionOrNull() ?: Exception("Upload failed"))
                }
            }
            
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
                attachmentUri = finalAttachmentUri,
                attachmentType = attachmentType,
                replyToMessageId = replyToMessageId,
                replyToUserName = replyToUserName,
                replyToMessage = replyToMessage
            )
            
            database.child(SESSIONS_PATH).child(sessionId)
                .child("chatMessages").child(messageId).setValue(chatMessage).await()
            
            Result.success(messageId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send chat message", e)
            Result.failure(e)
        }
    }
    
    // Upload file to Firebase Realtime Database as base64 and return the message ID
    private suspend fun uploadFileToStorage(
        sessionId: String,
        messageId: String,
        localUri: String,
        fileType: String,
        fileName: String?,
        onProgress: ((Int) -> Unit)?
    ): Result<String> {
        return try {
            // Read file as bytes
            val uri = android.net.Uri.parse(localUri)
            val context = com.google.firebase.FirebaseApp.getInstance().applicationContext
            
            onProgress?.invoke(5)
            
            var bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                when {
                    uri.scheme == "content" -> {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                    uri.scheme == "file" || localUri.startsWith("/") -> {
                        // Local file path - read directly
                        java.io.File(uri.path ?: localUri).readBytes()
                    }
                    else -> {
                        // Try as file path first, then content URI
                        try {
                            java.io.File(localUri).readBytes()
                        } catch (e: Exception) {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        }
                    }
                }
            } ?: return Result.failure(Exception("Could not read file"))
            
            onProgress?.invoke(15)
            
            // Check file size and compress if needed based on type
            val fileSizeMB = bytes.size / (1024.0 * 1024.0)
            
            when (fileType) {
                "image" -> {
                    if (fileSizeMB > 12) {
                        onProgress?.invoke(20)
                        // Compress image
                        bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            compressImage(context, uri, bytes)
                        }
                        val newSizeMB = bytes.size / (1024.0 * 1024.0)
                        Log.d(TAG, "Image compressed: ${String.format("%.2f", fileSizeMB)}MB -> ${String.format("%.2f", newSizeMB)}MB")
                        onProgress?.invoke(30)
                    }
                }
                "video" -> {
                    if (fileSizeMB > 100) {
                        return Result.failure(Exception("Video too large (${String.format("%.1f", fileSizeMB)}MB). Please compress to under 100MB before sending."))
                    }
                }
                "audio" -> {
                    if (fileSizeMB > 10) {
                        return Result.failure(Exception("Audio too large (${String.format("%.1f", fileSizeMB)}MB). Voice messages are limited to 5 minutes."))
                    }
                }
                "document" -> {
                    if (fileSizeMB > 50) {
                        return Result.failure(Exception("Document too large (${String.format("%.1f", fileSizeMB)}MB). Maximum 50MB allowed."))
                    }
                }
            }
            
            onProgress?.invoke(35)
            
            // Convert to base64
            val base64 = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                onProgress?.invoke(40)
                val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                onProgress?.invoke(50)
                encoded
            }
            
            onProgress?.invoke(55)
            
            // Store in Firebase Realtime Database under attachments
            val finalFileName = fileName ?: uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
            val attachmentData = mapOf(
                "base64" to base64,
                "fileName" to finalFileName,
                "fileType" to fileType,
                "size" to bytes.size,
                "uploadedAt" to com.google.firebase.database.ServerValue.TIMESTAMP
            )
            
            onProgress?.invoke(60)
            
            // Upload to Firebase - split large files into chunks
            val maxChunkSize = 1024 * 1024 // 1MB chunks
            if (base64.length > maxChunkSize) {
                Log.d(TAG, "Large file detected, splitting into chunks...")
                val chunks = base64.chunked(maxChunkSize)
                val totalChunks = chunks.size
                
                onProgress?.invoke(65)
                
                // Upload chunks
                chunks.forEachIndexed { index, chunk ->
                    database.child(SESSIONS_PATH)
                        .child(sessionId)
                        .child("attachments")
                        .child(messageId)
                        .child("chunk_$index")
                        .setValue(chunk)
                        .await()
                    
                    val chunkProgress = 65 + ((index + 1) * 30 / totalChunks)
                    onProgress?.invoke(chunkProgress)
                    Log.d(TAG, "Uploaded chunk ${index + 1}/$totalChunks")
                }
                
                // Store metadata
                val metadata = mapOf(
                    "fileName" to finalFileName,
                    "fileType" to fileType,
                    "size" to bytes.size,
                    "totalChunks" to totalChunks,
                    "uploadedAt" to com.google.firebase.database.ServerValue.TIMESTAMP
                )
                database.child(SESSIONS_PATH)
                    .child(sessionId)
                    .child("attachments")
                    .child(messageId)
                    .child("metadata")
                    .setValue(metadata)
                    .await()
                    
                onProgress?.invoke(95)
                Log.d(TAG, "All chunks uploaded, invoking 95%")
            } else {
                // Small file, upload directly
                Log.d(TAG, "Small file, uploading directly...")
                val attachmentData = mapOf(
                    "base64" to base64,
                    "fileName" to finalFileName,
                    "fileType" to fileType,
                    "size" to bytes.size,
                    "uploadedAt" to com.google.firebase.database.ServerValue.TIMESTAMP
                )
                
                onProgress?.invoke(70)
                database.child(SESSIONS_PATH)
                    .child(sessionId)
                    .child("attachments")
                    .child(messageId)
                    .setValue(attachmentData)
                    .await()
                onProgress?.invoke(95)
                Log.d(TAG, "Small file uploaded, invoking 95%")
            }
            
            Log.d(TAG, "Upload complete, invoking 100%")
            onProgress?.invoke(100)
            
            // Return a reference path that other clients can use
            val referencePath = "firebase://attachments/$sessionId/$messageId"
            
            Log.d(TAG, "File uploaded to Realtime Database: $referencePath (${String.format("%.2f", bytes.size / (1024.0 * 1024.0))}MB)")
            Result.success(referencePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload file to database", e)
            Result.failure(e)
        }
    }
    
    // Compress image to reduce file size
    private fun compressImage(context: android.content.Context, uri: android.net.Uri, originalBytes: ByteArray): ByteArray {
        try {
            // Decode original bitmap
            val originalBitmap = android.graphics.BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
                ?: return originalBytes
            
            // Calculate target dimensions (max 2048x2048)
            val maxDimension = 2048
            val scale = minOf(
                maxDimension.toFloat() / originalBitmap.width,
                maxDimension.toFloat() / originalBitmap.height,
                1.0f
            )
            
            val targetWidth = (originalBitmap.width * scale).toInt()
            val targetHeight = (originalBitmap.height * scale).toInt()
            
            // Resize bitmap
            val resizedBitmap = android.graphics.Bitmap.createScaledBitmap(
                originalBitmap, targetWidth, targetHeight, true
            )
            
            // Compress to JPEG with quality adjustment
            var quality = 85
            var compressedBytes: ByteArray
            
            do {
                val outputStream = java.io.ByteArrayOutputStream()
                resizedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outputStream)
                compressedBytes = outputStream.toByteArray()
                quality -= 10
            } while (compressedBytes.size > 12 * 1024 * 1024 && quality > 20)
            
            originalBitmap.recycle()
            resizedBitmap.recycle()
            
            return compressedBytes
        } catch (e: Exception) {
            Log.e(TAG, "Image compression failed", e)
            return originalBytes
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
