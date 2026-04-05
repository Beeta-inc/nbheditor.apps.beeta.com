# Google Sign-In Setup Guide

## Error Code 10 - Configuration Required

Error code 10 means the app needs to be registered with Google Cloud Console. Follow these steps:

## 📋 Setup Steps

### 1. Create Google Cloud Project
1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing one
3. Name it "NBH Editor" or similar

### 2. Enable APIs
1. Go to **APIs & Services** → **Library**
2. Search and enable:
   - **Google Drive API**
   - **Google Sign-In API**

### 3. Configure OAuth Consent Screen
1. Go to **APIs & Services** → **OAuth consent screen**
2. Select **External** user type
3. Fill in:
   - App name: `NBH Editor`
   - User support email: Your email
   - Developer contact: Your email
4. Add scopes:
   - `../auth/drive.appdata`
   - `../auth/userinfo.email`
   - `../auth/userinfo.profile`
5. Save and continue

### 4. Create OAuth 2.0 Credentials
1. Go to **APIs & Services** → **Credentials**
2. Click **Create Credentials** → **OAuth client ID**
3. Select **Android**
4. Fill in:
   - **Name**: NBH Editor Android
   - **Package name**: `com.beeta.nbheditor`
   - **SHA-1 certificate fingerprint**: See below

### 5. Get SHA-1 Fingerprints

#### For Debug Build (Testing):
```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```
**Debug SHA-1**: `71:BC:F6:67:E9:8A:B7:3C:7A:81:D1:93:73:82:2F:F3:9D:7D:12:86`

#### For Release Build (Production):
```bash
keytool -list -v -keystore appdata/Android/nbheditor/beeta-release.jks -alias nbheditor -storepass beeta2024 -keypass beeta2024
```
**Release SHA-1**: `E5:99:68:01:45:96:26:EE:65:91:15:9B:C3:3E:87:26:A7:85:7E:B3`

### 6. Create TWO OAuth Clients
You need to create **two separate OAuth clients**:

#### Client 1 - Debug Build:
- Package name: `com.beeta.nbheditor`
- SHA-1: `71:BC:F6:67:E9:8A:B7:3C:7A:81:D1:93:73:82:2F:F3:9D:7D:12:86`

#### Client 2 - Release Build:
- Package name: `com.beeta.nbheditor`
- SHA-1: `E5:99:68:01:45:96:26:EE:65:91:15:9B:C3:3E:87:26:A7:85:7E:B3`

### 7. Download google-services.json (Optional)
If you want to use Firebase features later:
1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Add your project
3. Add Android app with package name `com.beeta.nbheditor`
4. Download `google-services.json`
5. Place in `app/` directory

## ⏱️ Wait Time
After creating OAuth credentials, wait **5-10 minutes** for Google's servers to propagate the configuration.

## ✅ Testing
1. Uninstall the app completely
2. Rebuild and reinstall
3. Try signing in again

## 🔧 Troubleshooting

### Still getting Error 10?
- Double-check package name matches exactly: `com.beeta.nbheditor`
- Verify SHA-1 fingerprint is correct
- Make sure you created OAuth client for **Android** (not Web or iOS)
- Wait 10 minutes after creating credentials
- Clear app data and try again

### Error 12 (Cancelled)?
- User cancelled the sign-in flow
- This is normal if user backs out

### Error 7 (Network Error)?
- Check internet connection
- Try again later

## 📝 Quick Setup Checklist
- [ ] Created Google Cloud project
- [ ] Enabled Google Drive API
- [ ] Enabled Google Sign-In API
- [ ] Configured OAuth consent screen
- [ ] Created OAuth client for Debug build
- [ ] Created OAuth client for Release build
- [ ] Waited 10 minutes
- [ ] Tested sign-in

## 🎯 Current Configuration
- **Package Name**: `com.beeta.nbheditor`
- **Debug SHA-1**: `71:BC:F6:67:E9:8A:B7:3C:7A:81:D1:93:73:82:2F:F3:9D:7D:12:86`
- **Release SHA-1**: `E5:99:68:01:45:96:26:EE:65:91:15:9B:C3:3E:87:26:A7:85:7E:B3`
