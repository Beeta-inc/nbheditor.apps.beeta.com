package com.beeta.nbheditor

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

data class DevicePairSession(
    val code: String = "",
    val hostDevice: Map<String, Any?> = emptyMap(),
    val guestDevice: Map<String, Any?>? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "waiting" // waiting, paired
)

data class DeviceTransferPayload(
    val id: String = "",
    val transferId: String = "",
    val title: String = "",
    val content: String = "",
    val type: String = "rtf",
    val timestamp: Long = System.currentTimeMillis()
)

object DevicePairingManager {
    private const val TAG = "DevicePairingManager"
    private const val PAIRS_PATH = "device_pairs"
    private const val TRANSFERS_KEY = "transfers"
    private const val PREFS_NAME = "nbh_device_prefs"
    private const val KEY_PAIR_CODE = "nbh_device_pair_code"

    private val database: DatabaseReference by lazy {
        try {
            FirebaseDatabase.getInstance().reference
        } catch (e: Exception) {
            FirebaseDatabase.getInstance("https://nbheditior-default-rtdb.firebaseio.com").reference
        }
    }

    /**
     * Get or create a persistent 6-character device pairing code (e.g. NBH-842)
     */
    fun getOrCreateDevicePairCode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var code = prefs.getString(KEY_PAIR_CODE, null)
        if (code.isNullOrEmpty()) {
            val randomNum = Random.nextInt(100, 999)
            code = "NBH-$randomNum"
            prefs.edit().putString(KEY_PAIR_CODE, code).apply()
        }
        return code
    }

    /**
     * Register this device as a host in Firebase RTDB
     */
    suspend fun registerHostSession(context: Context, pairCode: String): Result<String> {
        return try {
            val pairRef = database.child(PAIRS_PATH).child(pairCode)
            val hostData = mapOf(
                "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "os" to "Android ${Build.VERSION.RELEASE}",
                "timestamp" to ServerValue.TIMESTAMP,
                "appVersion" to "2.0.0-PROD"
            )
            val sessionData = mapOf(
                "code" to pairCode,
                "hostDevice" to hostData,
                "createdAt" to ServerValue.TIMESTAMP,
                "status" to "waiting"
            )
            pairRef.setValue(sessionData).await()
            pairRef.onDisconnect().removeValue()
            pairRef.keepSynced(true)
            Log.d(TAG, "Registered host session for $pairCode")
            Result.success(pairCode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register host session for $pairCode", e)
            Result.failure(e)
        }
    }

    /**
     * Pair with a remote host code (e.g. from Web app)
     */
    suspend fun pairWithDevice(pairCode: String, clientLabel: String = "Android Client"): Result<Unit> {
        return try {
            val pairRef = database.child(PAIRS_PATH).child(pairCode)
            val snapshot = pairRef.get().await()
            if (!snapshot.exists()) {
                return Result.failure(Exception("Pairing code not found or expired."))
            }

            val guestData = mapOf(
                "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "os" to "Android ${Build.VERSION.RELEASE}",
                "pairedBy" to clientLabel,
                "timestamp" to ServerValue.TIMESTAMP
            )
            val updates = mapOf(
                "guestDevice" to guestData,
                "status" to "paired",
                "pairedAt" to ServerValue.TIMESTAMP
            )
            pairRef.updateChildren(updates).await()
            pairRef.keepSynced(true)
            Log.d(TAG, "Successfully paired with $pairCode")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pair with $pairCode", e)
            Result.failure(e)
        }
    }

    /**
     * Instantly send a note / content payload to the paired target channel
     */
    suspend fun sendTransferPayload(pairCode: String, title: String, content: String, type: String = "rtf"): Result<String> {
        return try {
            val transfersRef = database.child(PAIRS_PATH).child(pairCode).child(TRANSFERS_KEY).push()
            val transferId = transfersRef.key ?: "transfer_${System.currentTimeMillis()}"
            val payload = mapOf(
                "id" to transferId,
                "transferId" to transferId,
                "title" to title,
                "content" to content,
                "type" to type,
                "timestamp" to ServerValue.TIMESTAMP
            )
            transfersRef.setValue(payload).await()
            Log.d(TAG, "Transfer payload dispatched to $pairCode: $title")
            Result.success(transferId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send transfer payload to $pairCode", e)
            Result.failure(e)
        }
    }

    /**
     * Sub-millisecond reactive Flow observing incoming transfers for this device's pairCode
     */
    fun observeIncomingTransfers(pairCode: String): Flow<DeviceTransferPayload> = callbackFlow {
        val transfersRef = database.child(PAIRS_PATH).child(pairCode).child(TRANSFERS_KEY)
        transfersRef.keepSynced(true)

        val childListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                try {
                    val id = snapshot.key ?: ""
                    val transferId = snapshot.child("transferId").getValue(String::class.java) ?: id
                    val title = snapshot.child("title").getValue(String::class.java) ?: "Untitled"
                    val content = snapshot.child("content").getValue(String::class.java) ?: ""
                    val type = snapshot.child("type").getValue(String::class.java) ?: "rtf"
                    val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()

                    val payload = DeviceTransferPayload(
                        id = id,
                        transferId = transferId,
                        title = title,
                        content = content,
                        type = type,
                        timestamp = timestamp
                    )
                    trySend(payload)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing incoming transfer", e)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Incoming transfers listener cancelled", error.toException())
                close(error.toException())
            }
        }

        transfersRef.addChildEventListener(childListener)
        awaitClose {
            transfersRef.removeEventListener(childListener)
        }
    }
}
