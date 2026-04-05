# ⚠️ IMPORTANT: Replace This File!

## Current Status
This is a **PLACEHOLDER** `google-services.json` file that allows the app to build but **will not work** for sign-in.

## What You Need To Do

### Step 1: Set Up Firebase (5 minutes)
Follow the guide in `FIREBASE_SETUP.md` at the project root.

### Step 2: Download Your Real File
1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select your project
3. Go to Project Settings (gear icon)
4. Scroll to "Your apps"
5. Click "Download google-services.json"

### Step 3: Replace This File
1. Delete this placeholder file
2. Copy your downloaded `google-services.json` here
3. Rebuild the app

## File Location
```
appdata/Android/nbheditor/app/google-services.json  ← You are here
```

## How to Verify
Your real `google-services.json` should have:
- ✅ Your actual project ID (not "nbheditor-placeholder")
- ✅ Real API keys (not "AIzaSyXXXXX...")
- ✅ Real client IDs (not "123456789000-xxx...")
- ✅ Your package name: `com.beeta.nbheditor`

## What Happens If You Don't Replace It?
- ❌ Sign-in will fail with error code 10
- ❌ Firebase features won't work
- ❌ Cloud sync will be disabled

## Quick Setup
```bash
# 1. Set up Firebase (see FIREBASE_SETUP.md)
# 2. Download google-services.json
# 3. Replace this file
# 4. Rebuild
cd /path/to/project
./gradlew clean assembleDebug
```

## Need Help?
See `FIREBASE_SETUP.md` for complete step-by-step instructions.
