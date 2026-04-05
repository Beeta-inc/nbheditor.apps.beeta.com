# SHA-1 Fingerprint Troubleshooting

## ❌ "Invalid Fingerprint" Error

If Firebase says "invalid fingerprint", try these solutions:

### Solution 1: Check Format
Firebase accepts SHA-1 **with colons**:
```
✅ CORRECT: 71:BC:F6:67:E9:8A:B7:3C:7A:81:D1:93:73:82:2F:F3:9D:7D:12:86
❌ WRONG:   71BCF667E98AB73C7A81D19373822FF39D7D1286
```

### Solution 2: Remove Extra Spaces
Make sure there are no spaces before or after:
```
✅ CORRECT: 71:BC:F6:67:E9:8A:B7:3C:7A:81:D1:93:73:82:2F:F3:9D:7D:12:86
❌ WRONG:    71:BC:F6:67:E9:8A:B7:3C:7A:81:D1:93:73:82:2F:F3:9D:7D:12:86 
            ^ extra space at start or end
```

### Solution 3: Use Correct Field
In Firebase Console, make sure you're adding to **SHA-1** field, not SHA-256:
- ✅ SHA-1 certificate fingerprint: `71:BC:F6:67:E9:8A:B7:3C:7A:81:D1:93:73:82:2F:F3:9D:7D:12:86`
- ❌ SHA-256 (different field)

### Solution 4: Copy from App
The app has a "Copy SHA-1" button that copies the exact correct format:
1. Try to sign in (will fail with error 10)
2. Tap "Setup Guide"
3. Tap "Copy Debug SHA-1"
4. Paste directly into Firebase Console

### Solution 5: Manual Entry
If copy-paste doesn't work, type it manually:

**Debug SHA-1:**
```
71:BC:F6:67:E9:8A:B7:3C:7A:81:D1:93:73:82:2F:F3:9D:7D:12:86
```

**Release SHA-1:**
```
E5:99:68:01:45:96:26:EE:65:91:15:9B:C3:3E:87:26:A7:85:7E:B3
```

### Solution 6: Generate Fresh SHA-1
If nothing works, generate a new debug keystore:

```bash
# Delete old debug keystore
rm ~/.android/debug.keystore

# Rebuild app (will generate new keystore)
./gradlew clean assembleDebug

# Get new SHA-1
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android -keypass android | grep SHA1
```

Then use the new SHA-1 in Firebase.

### Solution 7: Check Package Name
Make sure package name matches exactly:
```
✅ CORRECT: com.beeta.nbheditor
❌ WRONG:   com.beeta.nbheditor.debug
❌ WRONG:   com.beeta.nbh
```

## ✅ Verification

After adding SHA-1 in Firebase:
1. Wait 5 minutes for changes to propagate
2. Uninstall the app completely
3. Reinstall and try signing in
4. Should work now!

## 🆘 Still Not Working?

### Check Firebase Console:
1. Go to Project Settings
2. Scroll to "Your apps"
3. Click on your Android app
4. Verify:
   - Package name: `com.beeta.nbheditor`
   - SHA-1 is listed (with colons)
   - No typos

### Check App:
1. Make sure `google-services.json` is in correct location
2. Package name in `build.gradle.kts` matches
3. Rebuild: `./gradlew clean assembleDebug`

## 📝 Common Mistakes

| Mistake | Fix |
|---------|-----|
| No colons | Add colons between every 2 characters |
| Extra spaces | Remove all spaces |
| Wrong field | Use SHA-1 field, not SHA-256 |
| Wrong package | Use `com.beeta.nbheditor` |
| Old keystore | Generate fresh debug keystore |
| Not waiting | Wait 5 minutes after adding |
| Not reinstalling | Uninstall and reinstall app |

## 💡 Pro Tip

The SHA-1 format with colons is correct. Firebase accepts it. If it says "invalid", it's usually:
- Extra space at start/end
- Wrong field (SHA-256 instead of SHA-1)
- Typo when manually typing

Use the in-app "Copy SHA-1" button for guaranteed correct format!
