package com.beeta.nbheditor

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object GoogleSignInHelper {
    
    private const val TAG = "GoogleSignIn"
    private const val PREF_SIGNED_IN = "google_signed_in"
    private const val PREF_USER_EMAIL = "google_user_email"
    private const val PREF_USER_NAME = "google_user_name"
    private const val PREF_USER_PHOTO = "google_user_photo"
    
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    
    fun getSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, gso)
    }
    
    fun isSignedIn(context: Context): Boolean {
        return auth.currentUser != null
    }
    
    suspend fun saveSignInState(context: Context, account: GoogleSignInAccount) {
        try {
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential).await()
            
            val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean(PREF_SIGNED_IN, true)
                putString(PREF_USER_EMAIL, account.email)
                putString(PREF_USER_NAME, account.displayName)
                putString(PREF_USER_PHOTO, account.photoUrl?.toString())
                apply()
            }
            
            Log.d(TAG, "Firebase auth successful")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign in with Firebase", e)
            throw e
        }
    }
    
    fun getUserEmail(context: Context): String? {
        return auth.currentUser?.email
    }
    
    fun getUserName(context: Context): String? {
        return auth.currentUser?.displayName
    }
    
    fun getUserPhotoUrl(context: Context): String? {
        return auth.currentUser?.photoUrl?.toString()
    }
    
    fun signOut(context: Context) {
        auth.signOut()
        getSignInClient(context).signOut()
        val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(PREF_SIGNED_IN, false)
            remove(PREF_USER_EMAIL)
            remove(PREF_USER_NAME)
            remove(PREF_USER_PHOTO)
            apply()
        }
    }
    
    suspend fun syncChatToCloud(context: Context, chatData: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val userId = auth.currentUser?.uid ?: return@withContext false
            
            // Store in Firestore
            val chatDoc = hashMapOf(
                "userId" to userId,
                "fileName" to fileName,
                "content" to chatData,
                "timestamp" to System.currentTimeMillis()
            )
            
            firestore.collection("users")
                .document(userId)
                .collection("chats")
                .document(fileName)
                .set(chatDoc)
                .await()
            
            Log.d(TAG, "Chat synced to Firestore: $fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync chat to cloud", e)
            false
        }
    }
    
    suspend fun syncFileToCloud(context: Context, fileContent: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val userId = auth.currentUser?.uid ?: return@withContext false
            
            // Store in Firestore
            val fileDoc = hashMapOf(
                "userId" to userId,
                "fileName" to fileName,
                "content" to fileContent,
                "timestamp" to System.currentTimeMillis()
            )
            
            firestore.collection("users")
                .document(userId)
                .collection("files")
                .document(fileName)
                .set(fileDoc)
                .await()
            
            Log.d(TAG, "File synced to Firestore: $fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync file to cloud", e)
            false
        }
    }
    
    suspend fun getAllCloudChats(context: Context): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val userId = auth.currentUser?.uid ?: return@withContext emptyList()
            
            val snapshot = firestore.collection("users")
                .document(userId)
                .collection("chats")
                .get()
                .await()
            
            snapshot.documents.mapNotNull { doc ->
                val fileName = doc.getString("fileName") ?: return@mapNotNull null
                val content = doc.getString("content") ?: return@mapNotNull null
                Pair(fileName, content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cloud chats", e)
            emptyList()
        }
    }
    
    suspend fun getAllCloudFiles(context: Context): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val userId = auth.currentUser?.uid ?: return@withContext emptyList()
            
            val snapshot = firestore.collection("users")
                .document(userId)
                .collection("files")
                .get()
                .await()
            
            snapshot.documents.mapNotNull { doc ->
                val fileName = doc.getString("fileName") ?: return@mapNotNull null
                val content = doc.getString("content") ?: return@mapNotNull null
                Pair(fileName, content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cloud files", e)
            emptyList()
        }
    }
}
