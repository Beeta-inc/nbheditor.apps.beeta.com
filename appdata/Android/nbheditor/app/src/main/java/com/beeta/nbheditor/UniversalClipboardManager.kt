package com.beeta.nbheditor

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Universal Clipboard Cross-Platform Sync Engine
 * Real-time, device-agnostic, multi-app synchronization between:
 * - NBH Web
 * - NBH Android APK
 * - NeTuArk Web (glowing-carnival)
 * - NTA Android APK (nta-app-try1)
 *
 * Cloud Database: NeTuArk Firestore (ntaf-754e1) -> users/{email}/universal_clipboard/current
 */
object UniversalClipboardManager {
    private const val TAG = "UniversalClipboard"
    private const val NETUARK_APP_NAME = "NeTuArkClipboardApp"
    private const val PREFS_NAME = "nbh_universal_clipboard_prefs"
    private const val KEY_LAST_CLIP = "last_cached_clip_text"

    // NeTuArk Firebase Credentials (projectId: ntaf-754e1)
    private const val NTA_API_KEY = "AIzaSyAD0NdeeIAZhOU2qrGYzZWXLUkIsw2j5vA"
    private const val NTA_PROJECT_ID = "ntaf-754e1"
    private const val NTA_APP_ID = "1:101870349371:web:477a5966c4a6f7d8bc801e"
    private const val NTA_STORAGE_BUCKET = "ntaf-754e1.firebasestorage.app"

    private val _latestClip = MutableStateFlow<String>("")
    val latestClip: StateFlow<String> = _latestClip

    private var firestoreInstance: FirebaseFirestore? = null
    private var activeEmail: String = ""
    private var isInitialized = false

    fun init(context: Context, userEmail: String? = null) {
        val email = userEmail?.trim()?.lowercase() ?: getSavedUserEmail(context)
        if (email.isBlank()) {
            Log.d(TAG, "No user email available yet for Universal Clipboard")
            return
        }

        activeEmail = email
        try {
            val netuarkApp = getOrInitFirebaseApp(context)
            firestoreInstance = FirebaseFirestore.getInstance(netuarkApp)

            // Load last local cached clip
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cached = prefs.getString(KEY_LAST_CLIP, "") ?: ""
            if (cached.isNotEmpty()) {
                _latestClip.value = cached
            }

            // Start real-time Firestore snapshot listener
            listenToRemoteClipboard(context, email)
            isInitialized = true
            Log.i(TAG, "Universal Clipboard initialized for user: $email")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Universal Clipboard Firestore", e)
        }
    }

    private fun getOrInitFirebaseApp(context: Context): FirebaseApp {
        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == NETUARK_APP_NAME }
        if (existing != null) return existing

        val options = FirebaseOptions.Builder()
            .setApiKey(NTA_API_KEY)
            .setApplicationId(NTA_APP_ID)
            .setProjectId(NTA_PROJECT_ID)
            .setStorageBucket(NTA_STORAGE_BUCKET)
            .build()

        return FirebaseApp.initializeApp(context.applicationContext, options, NETUARK_APP_NAME)
    }

    private fun listenToRemoteClipboard(context: Context, email: String) {
        val db = firestoreInstance ?: return
        db.collection("users").document(email)
            .collection("universal_clipboard").document("current")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Firestore clipboard listen error", error)
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val text = snapshot.getString("text") ?: ""
                    if (text.isNotBlank() && text != _latestClip.value) {
                        _latestClip.value = text
                        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putString(KEY_LAST_CLIP, text).apply()
                        Log.d(TAG, "Received universal clip from cloud (${text.length} chars)")
                    }
                }
            }
    }

    fun copyToUniversalClipboard(context: Context, text: String, sourceApp: String = "NBH_APK") {
        if (text.isBlank()) return
        _latestClip.value = text

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_CLIP, text).apply()

        val email = activeEmail.ifBlank { getSavedUserEmail(context) }
        if (email.isBlank()) {
            Log.d(TAG, "Copied locally; waiting for user login to sync to cloud")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (firestoreInstance == null) {
                    val app = getOrInitFirebaseApp(context)
                    firestoreInstance = FirebaseFirestore.getInstance(app)
                }

                val docData = hashMapOf(
                    "text" to text,
                    "sourceApp" to sourceApp,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                )

                firestoreInstance?.collection("users")?.document(email)
                    ?.collection("universal_clipboard")?.document("current")
                    ?.set(docData, SetOptions.merge())?.await()

                Log.i(TAG, "Universal Clipboard synced to cloud for $email")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write clipboard to Firestore", e)
            }
        }
    }

    fun hasClip(context: Context): Boolean {
        if (_latestClip.value.isNotBlank()) return true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_LAST_CLIP, "").isNullOrBlank()
    }

    fun getClipContent(context: Context): String {
        if (_latestClip.value.isNotBlank()) return _latestClip.value
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_CLIP, "") ?: ""
    }

    fun getSavedUserEmail(context: Context): String {
        // Check Google Sign-in / NbhEditor prefs
        val nbhPrefs = context.getSharedPreferences("nbheditor_prefs", Context.MODE_PRIVATE)
        val nbhEmail = nbhPrefs.getString("user_email", null)
        if (!nbhEmail.isNullOrBlank()) return nbhEmail.trim().lowercase()

        // Check NeTuArk shared prefs
        val netuarkEmail = nbhPrefs.getString("netuark_user_email", null)
        if (!netuarkEmail.isNullOrBlank()) return netuarkEmail.trim().lowercase()

        // Check NTA session prefs if available
        val ntaPrefs = context.getSharedPreferences("nta_session", Context.MODE_PRIVATE)
        val ntaEmail = ntaPrefs.getString("email", null)
        if (!ntaEmail.isNullOrBlank()) return ntaEmail.trim().lowercase()

        return ""
    }
}
