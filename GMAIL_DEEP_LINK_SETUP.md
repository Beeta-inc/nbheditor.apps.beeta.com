# 🔗 Complete Deep Link Setup - Works from Gmail & All Apps

## The Problem
- Custom scheme (`nbheditor://`) doesn't work in Gmail, some messaging apps
- Gmail automatically converts links to HTTPS
- Need HTTPS deep links to work: `https://nbheditor.pages.dev/collaborative/NE5SDT2`

## The Solution: App Links (HTTPS Deep Links)

### ✅ Step 1: Upload assetlinks.json to Your Website

**CRITICAL:** This file MUST be accessible at:
```
https://nbheditor.pages.dev/.well-known/assetlinks.json
```

**File content (already created for you):**
```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.beeta.nbheditor",
    "sha256_cert_fingerprints": [
      "7E:79:EC:52:22:BE:A7:5E:78:C2:84:3D:5A:00:4E:B9:56:10:4A:E2:08:68:E3:0C:71:8B:64:3F:3A:13:AE:0B"
    ]
  }
}]
```

**How to upload (Cloudflare Pages):**

1. In your `nbheditor.pages.dev` project, create this folder structure:
   ```
   .well-known/
   └── assetlinks.json
   ```

2. Copy the `assetlinks.json` file from this directory

3. Deploy to Cloudflare Pages

4. Verify it's accessible:
   ```bash
   curl https://nbheditor.pages.dev/.well-known/assetlinks.json
   ```

### ✅ Step 2: Verify the File is Correct

**Requirements:**
- ✅ Must be at `/.well-known/assetlinks.json` (exact path)
- ✅ Must return `Content-Type: application/json`
- ✅ Must be accessible via HTTPS (not HTTP)
- ✅ No redirects allowed
- ✅ Must return 200 OK status

**Test it:**
```bash
curl -I https://nbheditor.pages.dev/.well-known/assetlinks.json
```

Should show:
```
HTTP/2 200
content-type: application/json
```

### ✅ Step 3: Reinstall the App

After uploading the assetlinks.json file, reinstall the app:

```bash
cd /home/noywrit/AndroidStudioProjects/nbheditor.apps.beeta.com
adb uninstall com.beeta.nbheditor
./gradlew installDebug
```

Android will automatically verify the domain when the app is installed.

### ✅ Step 4: Test It Works

**Test from command line:**
```bash
adb shell am start -a android.intent.action.VIEW -d "https://nbheditor.pages.dev/collaborative/NE5SDT2"
```

**Expected result:** App opens directly (not browser)

**Test from Gmail:**
1. Send yourself an email with: `https://nbheditor.pages.dev/collaborative/NE5SDT2`
2. Tap the link in Gmail
3. App should open directly ✅

### ✅ Step 5: Check Verification Status

```bash
adb shell pm get-app-links com.beeta.nbheditor
```

Should show:
```
com.beeta.nbheditor:
  ID: ...
  Signatures: [...]
  Domain verification state:
    nbheditor.pages.dev: verified
```

If it shows `1024` or `none`, the verification failed.

## Troubleshooting

### Issue: Domain shows "1024" or "none" instead of "verified"

**Causes:**
1. assetlinks.json not uploaded to correct location
2. File not accessible via HTTPS
3. Wrong SHA-256 fingerprint
4. Content-Type not set to application/json

**Fix:**
1. Verify file is at: `https://nbheditor.pages.dev/.well-known/assetlinks.json`
2. Check it returns 200 OK
3. Reinstall app: `adb uninstall com.beeta.nbheditor && ./gradlew installDebug`
4. Wait 20 seconds for Android to verify
5. Check status: `adb shell pm get-app-links com.beeta.nbheditor`

### Issue: Link still opens in browser

**Fix:**
1. Clear app defaults:
   ```bash
   adb shell pm clear-package-preferred-activities com.beeta.nbheditor
   ```
2. Reinstall app
3. Test again

### Issue: Gmail doesn't recognize the link

**This is normal!** Gmail will show the link as text. When tapped:
- ✅ If domain verified: Opens app directly
- ❌ If not verified: Opens browser

## For Release Builds

When you create a release build, you need to add the release keystore fingerprint too:

1. Get release fingerprint:
   ```bash
   keytool -list -v -keystore /path/to/beeta-release.jks -alias your-alias
   ```

2. Update assetlinks.json to include BOTH fingerprints:
   ```json
   [{
     "relation": ["delegate_permission/common.handle_all_urls"],
     "target": {
       "namespace": "android_app",
       "package_name": "com.beeta.nbheditor",
       "sha256_cert_fingerprints": [
         "7E:79:EC:52:22:BE:A7:5E:78:C2:84:3D:5A:00:4E:B9:56:10:4A:E2:08:68:E3:0C:71:8B:64:3F:3A:13:AE:0B",
         "YOUR_RELEASE_FINGERPRINT_HERE"
       ]
     }
   }]
   ```

3. Upload updated file to website

## Quick Checklist

- [ ] assetlinks.json uploaded to `https://nbheditor.pages.dev/.well-known/assetlinks.json`
- [ ] File is accessible (returns 200 OK)
- [ ] Content-Type is application/json
- [ ] App uninstalled and reinstalled
- [ ] Domain verification status shows "verified"
- [ ] Test link opens app (not browser)
- [ ] Test from Gmail works

## Expected Timeline

- **Immediate:** Custom scheme works (`nbheditor://`)
- **20 seconds after install:** Domain verification completes
- **After verification:** HTTPS links work from Gmail and all apps

## Summary

Once you upload `assetlinks.json` to your website at the correct location and reinstall the app:

✅ Links work from Gmail
✅ Links work from WhatsApp
✅ Links work from any messaging app
✅ Links work from any browser
✅ Links work from anywhere

The HTTPS link `https://nbheditor.pages.dev/collaborative/NE5SDT2` will open your app directly instead of the browser!
