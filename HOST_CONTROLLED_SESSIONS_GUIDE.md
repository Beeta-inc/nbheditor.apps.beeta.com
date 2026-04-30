# Implementation Guide - Host-Controlled Sessions & Font Improvements

## ✅ Completed

### 1. Font System (50+ Fonts)
- ✅ Created `FontManager.kt` with 50+ fonts
- ✅ Organized fonts by category (System, Sans Serif, Serif, Monospace, Handwriting, Display)
- ✅ Updated `showTextTypeDialog()` with:
  - Live font preview
  - 50+ font options
  - 12 size options (10sp - 48sp)
  - Real-time preview updates
- ✅ Improved span application with SPAN_PRIORITY
- ✅ Enhanced CustomTypefaceSpan with better rendering

### 2. Join Request System (Backend)
- ✅ Added `JoinRequest` data class
- ✅ Added `status` field to `SessionUser`
- ✅ Created `requestToJoinSession()` function
- ✅ Created `approveJoinRequest()` function
- ✅ Created `rejectJoinRequest()` function
- ✅ Created `observeJoinRequests()` flow for host
- ✅ Created `observeJoinRequestStatus()` flow for requester

## 🔨 To Implement

### 3. Waiting Room UI (For Users)

**File:** Create `dialog_waiting_room.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="24dp"
    android:background="@drawable/bg_glass_card">

    <ProgressBar
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:layout_gravity="center"
        android:indeterminateTint="@color/accent_primary" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:layout_marginTop="16dp"
        android:text="Waiting for host approval..."
        android:textSize="18sp"
        android:textStyle="bold"
        android:textColor="@color/editor_text" />

    <TextView
        android:id="@+id/waitingMessage"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:layout_marginTop="8dp"
        android:text="The host will be notified of your request"
        android:textSize="14sp"
        android:textColor="@color/editor_hint"
        android:gravity="center" />

    <Button
        android:id="@+id/cancelWaitingButton"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:text="Cancel Request"
        android:backgroundTint="@color/editor_surface" />
</LinearLayout>
```

**Implementation in MainActivity.kt:**
```kotlin
private fun showWaitingRoomDialog(sessionId: String, userId: String) {
    val dialog = AlertDialog.Builder(this)
        .setView(R.layout.dialog_waiting_room)
        .setCancelable(false)
        .create()
    
    dialog.show()
    
    val cancelButton = dialog.findViewById<Button>(R.id.cancelWaitingButton)
    cancelButton?.setOnClickListener {
        lifecycleScope.launch {
            // Cancel join request
            database.child("collaborative_sessions/$sessionId/joinRequests/$userId").removeValue()
            dialog.dismiss()
        }
    }
    
    // Observe request status
    lifecycleScope.launch {
        CollaborativeSessionManager.observeJoinRequestStatus(sessionId, userId).collect { status ->
            when (status) {
                "approved" -> {
                    dialog.dismiss()
                    // Join session
                    joinApprovedSession(sessionId)
                }
                "rejected" -> {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Host rejected your request", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
```

### 4. Join Request Popup (For Host)

**File:** Create `dialog_join_request.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_margin="16dp"
    app:cardCornerRadius="16dp"
    app:cardElevation="8dp"
    app:cardBackgroundColor="@color/editor_surface"
    app:strokeWidth="2dp"
    app:strokeColor="@color/accent_primary">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="20dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <ImageView
                android:id="@+id/userPhoto"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:src="@drawable/ic_account_circle"
                android:scaleType="centerCrop" />

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="12dp"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/userName"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="User Name"
                    android:textSize="16sp"
                    android:textStyle="bold"
                    android:textColor="@color/editor_text" />

                <TextView
                    android:id="@+id/userEmail"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="user@email.com"
                    android:textSize="12sp"
                    android:textColor="@color/editor_hint" />
            </LinearLayout>

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="🔔"
                android:textSize="24sp" />
        </LinearLayout>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:text="wants to join your session"
            android:textSize="14sp"
            android:textColor="@color/editor_text" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:orientation="horizontal"
            android:gravity="end">

            <Button
                android:id="@+id/rejectButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Reject"
                android:backgroundTint="@android:color/holo_red_dark"
                android:textColor="@android:color/white" />

            <Button
                android:id="@+id/approveButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:text="Approve"
                android:backgroundTint="@color/accent_primary"
                android:textColor="@android:color/white" />
        </LinearLayout>
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

**Implementation in MainActivity.kt:**
```kotlin
private var joinRequestDialog: AlertDialog? = null

private fun observeJoinRequests(sessionId: String) {
    lifecycleScope.launch {
        CollaborativeSessionManager.observeJoinRequests(sessionId).collect { requests ->
            if (requests.isNotEmpty() && joinRequestDialog == null) {
                showJoinRequestDialog(sessionId, requests.first())
            }
        }
    }
}

private fun showJoinRequestDialog(sessionId: String, request: JoinRequest) {
    val dialogView = layoutInflater.inflate(R.layout.dialog_join_request, null)
    
    joinRequestDialog = AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(false)
        .create()
    
    dialogView.findViewById<TextView>(R.id.userName).text = request.userName
    dialogView.findViewById<TextView>(R.id.userEmail).text = request.email
    
    // Load user photo if available
    if (request.photoUrl.isNotEmpty()) {
        // Load image with Glide or similar
    }
    
    dialogView.findViewById<Button>(R.id.approveButton).setOnClickListener {
        lifecycleScope.launch {
            CollaborativeSessionManager.approveJoinRequest(sessionId, request.userId)
            joinRequestDialog?.dismiss()
            joinRequestDialog = null
            Toast.makeText(this@MainActivity, "${request.userName} joined the session", Toast.LENGTH_SHORT).show()
        }
    }
    
    dialogView.findViewById<Button>(R.id.rejectButton).setOnClickListener {
        lifecycleScope.launch {
            CollaborativeSessionManager.rejectJoinRequest(sessionId, request.userId)
            joinRequestDialog?.dismiss()
            joinRequestDialog = null
            Toast.makeText(this@MainActivity, "Request rejected", Toast.LENGTH_SHORT).show()
        }
    }
    
    joinRequestDialog?.show()
}
```

### 5. User List with Allow/Reject

**Update `dialog_session_users.xml`:**
Add buttons for pending users:
```xml
<Button
    android:id="@+id/approveUserButton"
    android:layout_width="wrap_content"
    android:layout_height="32dp"
    android:text="Approve"
    android:visibility="gone"
    android:backgroundTint="@color/accent_primary" />

<Button
    android:id="@+id/rejectUserButton"
    android:layout_width="wrap_content"
    android:layout_height="32dp"
    android:text="Reject"
    android:visibility="gone"
    android:backgroundTint="@android:color/holo_red_dark" />
```

### 6. Notification System

**Update notification to include session ID:**
```kotlin
private fun showSessionNotification(sessionId: String, sessionName: String) {
    val intent = Intent(this, MainActivity::class.java).apply {
        action = "OPEN_COLLABORATIVE_SESSION"
        putExtra("SESSION_ID", sessionId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    
    val pendingIntent = PendingIntent.getActivity(
        this, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Collaborative Session Active")
        .setContentText(sessionName)
        .setSmallIcon(R.drawable.ic_collab)
        .setContentIntent(pendingIntent)
        .setAutoCancel(false)
        .setOngoing(true)
        .build()
    
    notificationManager.notify(SESSION_NOTIFICATION_ID, notification)
}
```

**Handle notification click in MainActivity.onCreate():**
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Handle notification click
    if (intent.action == "OPEN_COLLABORATIVE_SESSION") {
        val sessionId = intent.getStringExtra("SESSION_ID")
        if (sessionId != null) {
            // Open collaborative session
            openCollaborativeSession(sessionId)
        }
    }
}
```

### 7. Offline Host Notification

**Add to CollaborativeSessionManager:**
```kotlin
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
```

**Show notification when host is offline:**
```kotlin
private fun checkHostStatus(sessionId: String) {
    lifecycleScope.launch {
        CollaborativeSessionManager.isHostOnline(sessionId).collect { isOnline ->
            if (!isOnline) {
                showHostOfflineNotification()
            }
        }
    }
}

private fun showHostOfflineNotification() {
    Snackbar.make(
        binding.root,
        "⚠️ Host is currently offline. Your request will be processed when they return.",
        Snackbar.LENGTH_LONG
    ).show()
}
```

## Testing Checklist

- [ ] Font selection works on real device
- [ ] Font preview updates in real-time
- [ ] 50+ fonts are available
- [ ] Join request creates pending status
- [ ] Waiting room shows for requester
- [ ] Host receives join request popup
- [ ] Approve button adds user to session
- [ ] Reject button denies access
- [ ] User list shows pending requests
- [ ] Notification opens correct session
- [ ] Offline host notification appears
- [ ] Request persists if host is offline

## Files to Create/Modify

### New Files:
1. `FontManager.kt` ✅
2. `dialog_waiting_room.xml`
3. `dialog_join_request.xml`

### Modified Files:
1. `CollaborativeSessionManager.kt` ✅
2. `MainActivity.kt` (add functions above)
3. `dialog_session_users.xml` (add approve/reject buttons)
4. Notification handling code

## Next Steps

1. Create the XML layout files
2. Implement the dialog functions in MainActivity
3. Update notification system
4. Test on real device
5. Build new APK

---

**Status:** Backend complete, UI implementation needed
**Priority:** High - Core collaboration feature
