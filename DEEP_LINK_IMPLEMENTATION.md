# Deep Link Implementation for Collaborative Sessions

## Overview
This implementation adds deep link support for collaborative sessions in NbhEditor Android app. Users can now click on links like `https://nbheditor.pages.dev/collaborative/NEJ00OV` to automatically join sessions in the app.

## Changes Made

### 1. AndroidManifest.xml
Added deep link intent filter to handle collaborative session URLs:

```xml
<!-- Deep link for collaborative sessions -->
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data
        android:scheme="https"
        android:host="nbheditor.pages.dev"
        android:pathPrefix="/collaborative" />
</intent-filter>
```

### 2. MainActivity.kt

#### Deep Link Handling in onCreate()
- Detects collaborative session links when app is launched
- Checks if user is signed in (required for collaborative sessions)
- If not signed in, shows sign-in dialog and stores session ID for later
- If signed in, automatically joins the session

#### Deep Link Handling in onNewIntent()
- Handles deep links when app is already running
- Parses URL to extract session ID
- Validates session ID format (NE + 5 alphanumeric characters)
- Automatically joins session or prompts for sign-in

#### Pending Session Join After Sign-In
- Stores pending session ID when user needs to sign in first
- Automatically joins session after successful sign-in
- Clears pending session after joining

#### Updated Session Invite Dialog
- Now displays both session code AND shareable link
- Shows link in format: `https://nbheditor.pages.dev/collaborative/[SESSION_ID]`
- Updated copy button to include link in invitation text
- Added separate card for displaying the clickable link

#### Updated Copy Button in Session Info Bar
- Copy button now includes the shareable link in the invitation text
- Format: "Click the link to join: [LINK] or use this code in the app: [CODE]"

## How It Works

### Creating a Session
1. User creates a collaborative session
2. Session invite dialog shows:
   - Session code (e.g., NEJ00OV)
   - Shareable link (https://nbheditor.pages.dev/collaborative/NEJ00OV)
3. User can copy or share the invitation which includes both code and link

### Joining via Link
1. User clicks link (from message, email, etc.)
2. Android opens NbhEditor app
3. App checks if user is signed in:
   - If yes: Automatically joins session
   - If no: Shows sign-in dialog, then joins after sign-in
4. User is connected to the collaborative session

### Link Format
- Base URL: `https://nbheditor.pages.dev/collaborative/`
- Session ID: 7 characters (NE + 5 alphanumeric)
- Example: `https://nbheditor.pages.dev/collaborative/NEJ00OV`

## User Experience

### Before (Old Flow)
1. Creator shares session code manually
2. Recipient opens app
3. Recipient navigates to Menu → Collaborative Session → Join
4. Recipient manually types session code
5. Recipient joins session

### After (New Flow)
1. Creator shares link (via copy/share button)
2. Recipient clicks link
3. App opens automatically
4. Session joins automatically (or after sign-in)

## Benefits
- ✅ One-click join from any app (Messages, WhatsApp, Email, etc.)
- ✅ No manual code entry required
- ✅ Seamless user experience
- ✅ Reduces friction in joining sessions
- ✅ Works across all devices with the app installed
- ✅ Backward compatible (session codes still work)

## Testing
To test the implementation:

1. **Create a session:**
   - Sign in to the app
   - Create a collaborative session
   - Note the session link shown in the dialog

2. **Test deep link:**
   - Send the link to another device or use ADB:
     ```bash
     adb shell am start -a android.intent.action.VIEW -d "https://nbheditor.pages.dev/collaborative/NEJ00OV"
     ```
   - App should open and join the session automatically

3. **Test without sign-in:**
   - Sign out from the app
   - Click a session link
   - Should show sign-in dialog
   - After sign-in, should join session automatically

## Notes
- Deep links only work when the app is installed
- If app is not installed, link opens in browser (web app)
- Session ID validation ensures only valid links are processed
- Sign-in is required for collaborative sessions (enforced)
