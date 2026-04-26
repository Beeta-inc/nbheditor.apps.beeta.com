# Testing Deep Links - Step by Step Guide

## ✅ Changes Made

1. **AndroidManifest.xml** - Added custom URI scheme `nbheditor://`
2. **MainActivity.kt** - Updated to handle both HTTPS and custom scheme links
3. **Build successful** - App compiled without errors

## 📱 Installation & Testing

### Step 1: Install the Updated App

```bash
cd /home/noywrit/AndroidStudioProjects/nbheditor.apps.beeta.com
./gradlew installDebug
```

Or manually install the APK:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 2: Test Custom Scheme (Should Work Immediately)

```bash
# Test with a sample session ID
adb shell am start -a android.intent.action.VIEW -d "nbheditor://collaborative/NE5SDT2"
```

Expected behavior:
- App opens immediately
- If not signed in: Shows "Sign In Required" dialog
- If signed in: Joins the collaborative session

### Step 3: Test from WhatsApp/Browser

1. **Send yourself a message in WhatsApp:**
   ```
   Join my session: nbheditor://collaborative/NE5SDT2
   ```

2. **Tap the link** - It should open NbhEditor app directly

3. **If it doesn't work**, try this HTML test file:
   - Open `web-redirect-example.html` in a browser
   - It will automatically try to open the app

### Step 4: Update Your Web App (nbheditor.pages.dev)

Your web app at `https://nbheditor.pages.dev/collaborative/NE5SDT2` needs to redirect to the custom scheme.

Add this JavaScript to your collaborative session page:

```javascript
// Get session ID from URL
const sessionId = window.location.pathname.split('/').pop();

// Immediately try to open the app
if (sessionId && sessionId.match(/^NE[A-Z0-9]{5}$/)) {
    // Try custom scheme (works on Android)
    window.location.href = `nbheditor://collaborative/${sessionId}`;
    
    // Show a message after 2 seconds if still on page
    setTimeout(() => {
        const openAppBtn = document.createElement('button');
        openAppBtn.textContent = 'Open in NbhEditor App';
        openAppBtn.onclick = () => {
            window.location.href = `nbheditor://collaborative/${sessionId}`;
        };
        document.body.prepend(openAppBtn);
    }, 2000);
}
```

## 🔧 Troubleshooting

### Issue: Link opens browser instead of app

**Solution 1: Use Custom Scheme**
- Change links from `https://nbheditor.pages.dev/collaborative/NE5SDT2`
- To: `nbheditor://collaborative/NE5SDT2`

**Solution 2: Add Redirect to Web App**
- Update your web app to automatically redirect to `nbheditor://` scheme
- See the JavaScript code above

### Issue: "No app found to open this link"

**Causes:**
1. App not installed
2. App not updated with new manifest
3. Typo in the link

**Fix:**
```bash
# Reinstall the app
./gradlew installDebug

# Verify intent filters
adb shell dumpsys package com.beeta.nbheditor | grep -A 20 "android.intent.action.VIEW"
```

### Issue: App opens but doesn't join session

**Check:**
1. Is user signed in with Google?
2. Is session ID valid format? (NE + 5 alphanumeric chars)
3. Check logcat for errors:
```bash
adb logcat | grep -i "nbheditor\|collaborative"
```

## 📋 Verify Intent Filters

Check if the app registered the intent filters correctly:

```bash
adb shell dumpsys package com.beeta.nbheditor | grep -A 30 "android.intent.action.VIEW"
```

You should see:
- `scheme: "nbheditor"`
- `host: "collaborative"`
- `scheme: "https"`
- `host: "nbheditor.pages.dev"`

## 🎯 Best Practice: Share Both Links

When sharing a collaborative session, provide both options:

```
Join my NbhEditor session!

📱 Open in app: nbheditor://collaborative/NE5SDT2

🌐 Open in browser: https://nbheditor.pages.dev/collaborative/NE5SDT2

Session Code: NE5SDT2
```

## 🚀 Quick Test Commands

```bash
# Install app
./gradlew installDebug

# Test custom scheme
adb shell am start -a android.intent.action.VIEW -d "nbheditor://collaborative/NETEST1"

# Test HTTPS (will open browser until domain verified)
adb shell am start -a android.intent.action.VIEW -d "https://nbheditor.pages.dev/collaborative/NETEST1"

# Check logs
adb logcat -c && adb logcat | grep -i collaborative
```

## ✨ Next Steps

1. **Install the updated app** on your device
2. **Test the custom scheme** link
3. **Update your web app** to redirect to `nbheditor://` scheme
4. **Share links** using the custom scheme format

The custom scheme (`nbheditor://`) will work immediately without any domain verification!
