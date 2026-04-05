# Firebase Setup Guide - 100% FREE ✅

## Why Firebase?
- ✅ **Completely FREE** - No credit card required
- ✅ **Generous free tier** - 1GB storage, 50K reads/day, 20K writes/day
- ✅ **No trial period** - Free forever for this use case
- ✅ **Easy setup** - 5 minutes to configure

## 📋 Setup Steps (5 Minutes)

### Step 1: Create Firebase Project
1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Click **Add Project**
3. Name it "NBH Editor" or similar
4. Disable Google Analytics (optional, not needed)
5. Click **Create Project**

### Step 2: Add Android App
1. In your Firebase project, click **Add app** → **Android**
2. Fill in:
   - **Package name**: `com.beeta.nbheditor`
   - **App nickname**: NBH Editor (optional)
   - **Debug SHA-1**: `71:BC:F6:67:E9:8A:B7:3C:7A:81:D1:93:73:82:2F:F3:9D:7D:12:86`
3. Click **Register app**

### Step 3: Download google-services.json
1. Download the `google-services.json` file
2. **IMPORTANT**: Replace the placeholder file at:
   ```
   appdata/Android/nbheditor/app/google-services.json
   ```
3. The placeholder file is there to allow builds, but won't work for sign-in
4. Click **Next** → **Next** → **Continue to console**

### Step 4: Enable Authentication
1. In Firebase Console, go to **Build** → **Authentication**
2. Click **Get Started**
3. Click **Sign-in method** tab
4. Enable **Google** sign-in provider
5. Set support email (your email)
6. Click **Save**

### Step 5: Enable Firestore Database
1. Go to **Build** → **Firestore Database**
2. Click **Create database**
3. Select **Start in test mode** (we'll secure it later)
4. Choose location closest to you
5. Click **Enable**

### Step 6: Set Firestore Security Rules
1. In Firestore, go to **Rules** tab
2. Replace with these secure rules:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Users can only read/write their own data
    match /users/{userId}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }
  }
}
```

3. Click **Publish**

### Step 7: Add Release SHA-1 (For Production)
1. Go to **Project Settings** (gear icon)
2. Scroll to **Your apps** → Select your Android app
3. Click **Add fingerprint**
4. Add **Release SHA-1**: `E5:99:68:01:45:96:26:EE:65:91:15:9B:C3:3E:87:26:A7:85:7E:B3`
5. Download new `google-services.json` and replace the old one

## 📁 File Structure
```
appdata/Android/nbheditor/app/
├── google-services.json  ← Place downloaded file here
├── build.gradle.kts
└── src/
```

## ✅ Verification
After setup, your `google-services.json` should contain:
- `project_id`
- `client` array with your package name
- `oauth_client` with `client_id`

## 🎯 What You Get (FREE)
- **Authentication**: Google Sign-In
- **Firestore**: NoSQL database for chats and files
- **Storage**: 1GB free storage
- **Bandwidth**: 10GB/month free
- **Operations**: 50K reads + 20K writes per day

## 🔒 Security
- Users can only access their own data
- No developer access to user data
- Data encrypted in transit and at rest
- Firestore rules enforce user isolation

## 🚀 Testing
1. Place `google-services.json` in `app/` folder
2. Rebuild the app
3. Try signing in
4. Check Firebase Console → Authentication to see your user
5. Check Firestore → Data to see synced chats/files

## 🆘 Troubleshooting

### Error: "default_web_client_id not found"
- Make sure `google-services.json` is in the correct location
- Rebuild the project (Clean → Rebuild)
- Check that package name matches exactly

### Error Code 10
- Add SHA-1 fingerprint in Firebase Console
- Wait 5 minutes for changes to propagate
- Uninstall and reinstall the app

### Sign-in works but no data syncing
- Check Firestore rules are published
- Check internet connection
- Look at Logcat for error messages

## 💰 Cost Comparison

| Service | Google Cloud | Firebase |
|---------|-------------|----------|
| Setup | Complex | Simple |
| Cost | Paid (trial) | FREE |
| Storage | Paid | 1GB free |
| Auth | Paid | FREE |
| Maintenance | High | Low |

## 📝 Quick Checklist
- [ ] Created Firebase project
- [ ] Added Android app with package name
- [ ] Downloaded google-services.json
- [ ] Placed file in app/ folder
- [ ] Enabled Google Authentication
- [ ] Created Firestore database
- [ ] Set security rules
- [ ] Added both SHA-1 fingerprints
- [ ] Rebuilt the app
- [ ] Tested sign-in

## 🎉 Done!
Firebase is now configured and completely FREE. No credit card, no trial, no hidden costs!
