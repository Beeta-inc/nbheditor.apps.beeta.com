package com.beeta.nbheditor

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

class NbhEditorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseApp.initializeApp(this)
            // Enable offline disk persistence for sub-millisecond local caching & hyper-instant sync
            val database = try {
                FirebaseDatabase.getInstance()
            } catch (e: Exception) {
                FirebaseDatabase.getInstance("https://nbheditior-default-rtdb.firebaseio.com")
            }
            database.setPersistenceEnabled(true)
            
            // Keep critical synchronization nodes actively synced in memory
            database.getReference("device_pairs").keepSynced(true)
            database.getReference("collaborative_sessions").keepSynced(true)
            database.getReference("public_shared_notes").keepSynced(true)
            Log.d("NbhEditorApp", "Hyper-Instant Firebase RTDB persistence and keepSynced initialized successfully")
        } catch (e: Exception) {
            Log.e("NbhEditorApp", "Failed to initialize Firebase persistence (may already be set)", e)
        }
    }
}
