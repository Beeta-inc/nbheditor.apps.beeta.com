# Quick Fix: Google Sign-In Error Code 10

## 🚨 Error Message
"Sign in failed error code 10"

## ✅ Solution
The app now shows a helpful dialog with setup instructions when this error occurs.

## 📋 Quick Setup (5 minutes)

### Step 1: Go to Google Cloud Console
Visit: https://console.cloud.google.com/

### Step 2: Create OAuth Credentials
1. APIs & Services → Credentials
2. Create Credentials → OAuth client ID
3. Select "Android"
4. Fill in:
   - Package name: `com.beeta.nbheditor`
   - SHA-1: Copy from the error dialog

### Step 3: Create TWO Credentials
Create one for Debug and one for Release:

**Debug Build:**
- SHA-1: `71:BC:F6:67:E9:8A:B7:3C:7A:81:D1:93:73:82:2F:F3:9D:7D:12:86`

**Release Build:**
- SHA-1: `E5:99:68:01:45:96:26:EE:65:91:15:9B:C3:3E:87:26:A7:85:7E:B3`

### Step 4: Enable APIs
In the same project:
1. APIs & Services → Library
2. Enable "Google Drive API"

### Step 5: Wait & Test
- Wait 5-10 minutes for changes to propagate
- Uninstall and reinstall the app
- Try signing in again

## 🎯 In-App Help
When you get error code 10:
1. Tap "Setup Guide" button in the error dialog
2. Copy SHA-1 fingerprints with one tap
3. Follow the instructions shown

## 📝 Full Documentation
See `GOOGLE_SIGNIN_SETUP.md` for detailed step-by-step instructions with screenshots.

## ✨ After Setup
Once configured, sign-in will work and you'll get:
- ☁️ Automatic chat sync across devices
- 📄 File backup to your Google Drive
- 🔒 100% private (stored in your Drive only)
