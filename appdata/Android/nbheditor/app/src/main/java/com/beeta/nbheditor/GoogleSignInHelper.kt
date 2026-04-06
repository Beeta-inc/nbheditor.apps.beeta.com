package com.beeta.nbheditor

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object GoogleSignInHelper {
    
    private const val TAG = "GoogleSignIn"
    private const val PREF_SIGNED_IN = "google_signed_in"
    private const val PREF_USER_EMAIL = "google_user_email"
    private const val PREF_USER_NAME = "google_user_name"
    private const val PREF_USER_PHOTO = "google_user_photo"
    
    private val auth = FirebaseAuth.getInstance()
    private var driveService: Drive? = null
    private var lastAuthException: com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException? = null
    private const val ROOT_FOLDER_NAME = "nbheditordat"
    private const val CHAT_FOLDER_NAME = "chat"
    private const val FILE_FOLDER_NAME = "file"
    
    fun getLastAuthException(): com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException? {
        return lastAuthException
    }
    
    fun clearAuthException() {
        lastAuthException = null
    }
    
    fun getSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .requestScopes(com.google.android.gms.common.api.Scope(DriveScopes.DRIVE_FILE))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }
    
    fun isSignedIn(context: Context): Boolean {
        val signedIn = auth.currentUser != null
        if (signedIn && driveService == null) {
            // Re-initialize Drive service if user is signed in but service is null
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account != null) {
                initializeDriveService(context, account)
            }
        }
        return signedIn
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
            
            // Initialize Drive service
            initializeDriveService(context, account)
            
            // Setup folder structure - this will trigger permission request if needed
            withContext(Dispatchers.Main) {
                setupDriveFolders()
            }
            
            Log.d(TAG, "Firebase auth and Drive setup successful")
        } catch (e: com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException) {
            Log.e(TAG, "Drive permission needed", e)
            lastAuthException = e
            throw e
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
        driveService = null
        val prefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(PREF_SIGNED_IN, false)
            remove(PREF_USER_EMAIL)
            remove(PREF_USER_NAME)
            remove(PREF_USER_PHOTO)
            apply()
        }
    }
    
    private fun initializeDriveService(context: Context, account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account
        
        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("NBH Editor")
            .build()
    }
    
    private suspend fun setupDriveFolders() = withContext(Dispatchers.IO) {
        try {
            val drive = driveService
            if (drive == null) {
                Log.e(TAG, "Drive service is null in setupDriveFolders")
                return@withContext
            }
            
            Log.d(TAG, "Setting up Drive folders...")
            
            // Check if root folder exists
            var rootFolderId = findFolder(ROOT_FOLDER_NAME, null)
            if (rootFolderId == null) {
                rootFolderId = createFolder(ROOT_FOLDER_NAME, null)
                Log.d(TAG, "Created root folder: $ROOT_FOLDER_NAME with ID: $rootFolderId")
            } else {
                Log.d(TAG, "Found existing root folder with ID: $rootFolderId")
            }
            
            if (rootFolderId == null) {
                Log.e(TAG, "Failed to get or create root folder")
                return@withContext
            }
            
            // Check if chat folder exists
            var chatFolderId = findFolder(CHAT_FOLDER_NAME, rootFolderId)
            if (chatFolderId == null) {
                chatFolderId = createFolder(CHAT_FOLDER_NAME, rootFolderId)
                Log.d(TAG, "Created chat folder with ID: $chatFolderId")
            } else {
                Log.d(TAG, "Found existing chat folder with ID: $chatFolderId")
            }
            
            // Check if file folder exists
            var fileFolderId = findFolder(FILE_FOLDER_NAME, rootFolderId)
            if (fileFolderId == null) {
                fileFolderId = createFolder(FILE_FOLDER_NAME, rootFolderId)
                Log.d(TAG, "Created file folder with ID: $fileFolderId")
            } else {
                Log.d(TAG, "Found existing file folder with ID: $fileFolderId")
            }
            
            Log.d(TAG, "Drive folder structure ready")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup Drive folders", e)
            e.printStackTrace()
        }
    }
    
    private suspend fun findFolder(folderName: String, parentId: String?): String? = withContext(Dispatchers.IO) {
        try {
            val drive = driveService ?: return@withContext null
            
            val query = if (parentId != null) {
                "name='$folderName' and mimeType='application/vnd.google-apps.folder' and '$parentId' in parents and trashed=false"
            } else {
                "name='$folderName' and mimeType='application/vnd.google-apps.folder' and trashed=false"
            }
            
            val result: FileList = drive.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()
            
            result.files.firstOrNull()?.id
        } catch (e: com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException) {
            Log.e(TAG, "User needs to grant Drive permission", e)
            // Store the exception to handle in UI
            lastAuthException = e
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find folder: $folderName", e)
            null
        }
    }
    
    private suspend fun createFolder(folderName: String, parentId: String?): String? = withContext(Dispatchers.IO) {
        try {
            val drive = driveService ?: return@withContext null
            
            val folderMetadata = File()
            folderMetadata.name = folderName
            folderMetadata.mimeType = "application/vnd.google-apps.folder"
            if (parentId != null) {
                folderMetadata.parents = listOf(parentId)
            }
            
            val folder = drive.files().create(folderMetadata)
                .setFields("id")
                .execute()
            
            folder.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create folder: $folderName", e)
            null
        }
    }
    
    suspend fun syncChatToCloud(context: Context, chatData: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting chat sync for: $fileName")
            
            // Ensure Drive service is initialized
            if (driveService == null) {
                Log.d(TAG, "Drive service is null, initializing...")
                val account = GoogleSignIn.getLastSignedInAccount(context)
                if (account != null) {
                    Log.d(TAG, "Found account: ${account.email}")
                    initializeDriveService(context, account)
                    setupDriveFolders()
                } else {
                    Log.e(TAG, "No signed in account found")
                    return@withContext false
                }
            }
            
            val drive = driveService
            if (drive == null) {
                Log.e(TAG, "Drive service is still null after initialization")
                return@withContext false
            }
            
            Log.d(TAG, "Finding root folder...")
            var rootFolderId = findFolder(ROOT_FOLDER_NAME, null)
            if (rootFolderId == null) {
                Log.d(TAG, "Root folder not found, creating...")
                rootFolderId = createFolder(ROOT_FOLDER_NAME, null)
                if (rootFolderId == null) {
                    Log.e(TAG, "Failed to create root folder")
                    return@withContext false
                }
                Log.d(TAG, "Created root folder with ID: $rootFolderId")
            }
            Log.d(TAG, "Root folder ID: $rootFolderId")
            
            Log.d(TAG, "Finding chat folder...")
            var chatFolderId = findFolder(CHAT_FOLDER_NAME, rootFolderId)
            if (chatFolderId == null) {
                Log.d(TAG, "Chat folder not found, creating...")
                chatFolderId = createFolder(CHAT_FOLDER_NAME, rootFolderId)
                if (chatFolderId == null) {
                    Log.e(TAG, "Failed to create chat folder")
                    return@withContext false
                }
                Log.d(TAG, "Created chat folder with ID: $chatFolderId")
            }
            Log.d(TAG, "Chat folder ID: $chatFolderId")
            
            // Check if file already exists
            Log.d(TAG, "Checking if chat file exists: $fileName")
            val existingFileId = findFileInFolder(fileName, chatFolderId)
            
            if (existingFileId != null) {
                // Update existing file
                Log.d(TAG, "Updating existing chat file with ID: $existingFileId")
                updateFile(existingFileId, chatData)
            } else {
                // Create new file
                Log.d(TAG, "Creating new chat file: $fileName")
                val newFileId = createFile(fileName, chatData, chatFolderId)
                Log.d(TAG, "Created chat file with ID: $newFileId")
            }
            
            Log.d(TAG, "Chat synced to Drive successfully: $fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync chat to Drive: $fileName", e)
            e.printStackTrace()
            false
        }
    }
    
    private suspend fun findFileInFolder(fileName: String, folderId: String): String? = withContext(Dispatchers.IO) {
        try {
            val drive = driveService ?: return@withContext null
            
            val query = "name='$fileName' and '$folderId' in parents and trashed=false"
            
            val result: FileList = drive.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()
            
            result.files.firstOrNull()?.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find file: $fileName", e)
            null
        }
    }
    
    private suspend fun createFile(fileName: String, content: String, folderId: String): String? = withContext(Dispatchers.IO) {
        try {
            val drive = driveService ?: return@withContext null
            
            val fileMetadata = File()
            fileMetadata.name = fileName
            fileMetadata.parents = listOf(folderId)
            
            val contentStream = java.io.ByteArrayInputStream(content.toByteArray())
            
            val file = drive.files().create(fileMetadata, com.google.api.client.http.InputStreamContent("text/plain", contentStream))
                .setFields("id")
                .execute()
            
            file.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create file: $fileName", e)
            null
        }
    }
    
    private suspend fun updateFile(fileId: String, content: String) = withContext(Dispatchers.IO) {
        try {
            val drive = driveService ?: return@withContext
            
            val contentStream = java.io.ByteArrayInputStream(content.toByteArray())
            
            drive.files().update(fileId, null, com.google.api.client.http.InputStreamContent("text/plain", contentStream))
                .execute()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update file: $fileId", e)
        }
    }
    
    suspend fun syncFileToCloud(context: Context, fileContent: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting file sync for: $fileName")
            
            // Ensure Drive service is initialized
            if (driveService == null) {
                Log.d(TAG, "Drive service is null, initializing...")
                val account = GoogleSignIn.getLastSignedInAccount(context)
                if (account != null) {
                    Log.d(TAG, "Found account: ${account.email}")
                    initializeDriveService(context, account)
                    setupDriveFolders()
                } else {
                    Log.e(TAG, "No signed in account found")
                    return@withContext false
                }
            }
            
            val drive = driveService
            if (drive == null) {
                Log.e(TAG, "Drive service is still null after initialization")
                return@withContext false
            }
            
            Log.d(TAG, "Finding root folder...")
            var rootFolderId = findFolder(ROOT_FOLDER_NAME, null)
            if (rootFolderId == null) {
                Log.d(TAG, "Root folder not found, creating...")
                rootFolderId = createFolder(ROOT_FOLDER_NAME, null)
                if (rootFolderId == null) {
                    Log.e(TAG, "Failed to create root folder")
                    return@withContext false
                }
                Log.d(TAG, "Created root folder with ID: $rootFolderId")
            }
            Log.d(TAG, "Root folder ID: $rootFolderId")
            
            Log.d(TAG, "Finding file folder...")
            var fileFolderId = findFolder(FILE_FOLDER_NAME, rootFolderId)
            if (fileFolderId == null) {
                Log.d(TAG, "File folder not found, creating...")
                fileFolderId = createFolder(FILE_FOLDER_NAME, rootFolderId)
                if (fileFolderId == null) {
                    Log.e(TAG, "Failed to create file folder")
                    return@withContext false
                }
                Log.d(TAG, "Created file folder with ID: $fileFolderId")
            }
            Log.d(TAG, "File folder ID: $fileFolderId")
            
            // Check if file already exists
            Log.d(TAG, "Checking if file exists: $fileName")
            val existingFileId = findFileInFolder(fileName, fileFolderId)
            
            if (existingFileId != null) {
                // Update existing file
                Log.d(TAG, "Updating existing file with ID: $existingFileId")
                updateFile(existingFileId, fileContent)
            } else {
                // Create new file
                Log.d(TAG, "Creating new file: $fileName")
                val newFileId = createFile(fileName, fileContent, fileFolderId)
                Log.d(TAG, "Created file with ID: $newFileId")
            }
            
            Log.d(TAG, "File synced to Drive successfully: $fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync file to Drive: $fileName", e)
            e.printStackTrace()
            false
        }
    }
    
    suspend fun getAllCloudChats(context: Context): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val drive = driveService ?: return@withContext emptyList()
            
            val rootFolderId = findFolder(ROOT_FOLDER_NAME, null) ?: return@withContext emptyList()
            val chatFolderId = findFolder(CHAT_FOLDER_NAME, rootFolderId) ?: return@withContext emptyList()
            
            val query = "'$chatFolderId' in parents and trashed=false"
            
            val result: FileList = drive.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()
            
            result.files.mapNotNull { file ->
                val content = downloadFileContent(file.id)
                if (content != null) {
                    Pair(file.name, content)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cloud chats", e)
            emptyList()
        }
    }
    
    suspend fun getAllCloudFiles(context: Context): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val drive = driveService ?: return@withContext emptyList()
            
            val rootFolderId = findFolder(ROOT_FOLDER_NAME, null) ?: return@withContext emptyList()
            val fileFolderId = findFolder(FILE_FOLDER_NAME, rootFolderId) ?: return@withContext emptyList()
            
            val query = "'$fileFolderId' in parents and trashed=false"
            
            val result: FileList = drive.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()
            
            result.files.mapNotNull { file ->
                val content = downloadFileContent(file.id)
                if (content != null) {
                    Pair(file.name, content)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cloud files", e)
            emptyList()
        }
    }
    
    private suspend fun downloadFileContent(fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val drive = driveService ?: return@withContext null
            
            val outputStream = ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            
            outputStream.toString("UTF-8")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download file content: $fileId", e)
            null
        }
    }
}
