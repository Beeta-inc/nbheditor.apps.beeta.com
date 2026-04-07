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
    val isCreator: Boolean = false,
    val cursorPosition: Int = 0,
    val isTyping: Boolean = false,
    val lastActive: Long = System.currentTimeMillis()
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
    val maxUsers: Int = 10
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
        initialContent: String = ""
    ): Result<String> {
        return try {
            val sessionId = generateUniqueSessionCode()
            val sanitizedUserId = sanitizeUserId(userId)
            val creator = SessionUser(
                userId = sanitizedUserId,
                userName = userName,
                email = email,
                isCreator = true,
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
        email: String
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
            
            // Check if session is expired
            if (System.currentTimeMillis() - session.lastActivity > SESSION_TIMEOUT) {
                return Result.failure(Exception("Session expired"))
            }
            
            // Check if session is full
            if (session.users.size >= session.maxUsers) {
                return Result.failure(Exception("Session is full (max ${session.maxUsers} users)"))
            }
            
            // Add user to session
            val newUser = SessionUser(
                userId = sanitizedUserId,
                userName = userName,
                email = email,
                isCreator = false,
                lastActive = System.currentTimeMillis()
            )
            
            sessionRef.child("users").child(sanitizedUserId).setValue(newUser).await()
            sessionRef.child("lastActivity").setValue(ServerValue.TIMESTAMP).await()
            
            currentSessionId = sessionId
            currentUserId = sanitizedUserId
            
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
                sessionRef.removeValue().await()
                Log.d(TAG, "Session deleted (no users left): $sessionId")
            }
            
            currentSessionId = null
            currentUserId = null
            
            Log.d(TAG, "Left session: $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to leave session", e)
        }
    }
    
    // Update editor content
    suspend fun updateContent(content: String) {
        try {
            val sessionId = currentSessionId ?: return
            database.child(SESSIONS_PATH).child(sessionId).child("content").setValue(content).await()
            database.child(SESSIONS_PATH).child(sessionId).child("lastActivity")
                .setValue(ServerValue.TIMESTAMP).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update content", e)
        }
    }
    
    // Update cursor position
    suspend fun updateCursorPosition(position: Int, isTyping: Boolean) {
        try {
            val sessionId = currentSessionId ?: return
            val userId = currentUserId ?: return
            
            val updates = mapOf(
                "cursorPosition" to position,
                "isTyping" to isTyping,
                "lastActive" to ServerValue.TIMESTAMP
            )
            
            database.child(SESSIONS_PATH).child(sessionId)
                .child("users").child(userId).updateChildren(updates).await()
        } catch (e: Exception) {
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
            val currentUser = getCurrentUser(sessionId)
            if (currentUser?.isCreator != true) {
                return Result.failure(Exception("Only creator can kick users"))
            }
            
            database.child(SESSIONS_PATH).child(sessionId)
                .child("users").child(sanitizedUserId).removeValue().await()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kick user", e)
            Result.failure(e)
        }
    }
    
    // End session (creator only)
    suspend fun endSession(sessionId: String): Result<Unit> {
        return try {
            val currentUser = getCurrentUser(sessionId)
            if (currentUser?.isCreator != true) {
                return Result.failure(Exception("Only creator can end session"))
            }
            
            database.child(SESSIONS_PATH).child(sessionId).removeValue().await()
            currentSessionId = null
            currentUserId = null
            
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
}
