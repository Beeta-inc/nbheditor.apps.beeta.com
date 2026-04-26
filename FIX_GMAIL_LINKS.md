# 🔴 URGENT FIX NEEDED

## The Problem

Your assetlinks.json has the WRONG SHA-256 fingerprint!

**Current (WRONG):**
```
7E:79:EC:52:22:BE:A7:5E:78:C2:84:3D:5A:00:4E:B9:56:10:4A:E2:08:68:E3:0C:71:8B:64:3F:3A:13:AE:0B
```

**Correct (from your actual app):**
```
28:83:7A:DC:A0:68:02:16:00:17:DA:38:EF:BF:CA:88:63:5D:7C:8B:E8:07:DC:8A:D7:65:43:CD:E3:20:4D:D3
```

This is why Gmail links open in browser instead of app!

## The Fix

### Step 1: Update assetlinks.json on your website

Replace the content at `https://nbheditor.pages.dev/.well-known/assetlinks.json` with:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.beeta.nbheditor",
    "sha256_cert_fingerprints": [
      "28:83:7A:DC:A0:68:02:16:00:17:DA:38:EF:BF:CA:88:63:5D:7C:8B:E8:07:DC:8A:D7:65:43:CD:E3:20:4D:D3"
    ]
  }
}]
```

**File location:** `assetlinks-CORRECT.json` (in this directory)

### Step 2: Verify the file is updated

```bash
curl https://nbheditor.pages.dev/.well-known/assetlinks.json
```

Should show the NEW fingerprint: `28:83:7A:DC:...`

### Step 3: Force re-verification on device

```bash
# Clear verification state
adb shell pm set-app-links --package com.beeta.nbheditor 0 nbheditor.pages.dev

# Trigger re-verification
adb shell pm verify-app-links --re-verify com.beeta.nbheditor

# Wait 10 seconds
sleep 10

# Check status
adb shell pm get-app-links com.beeta.nbheditor
```

Should now show:
```
Domain verification state:
  nbheditor.pages.dev: verified
```

### Step 4: Test

```bash
adb shell am start -a android.intent.action.VIEW -d "https://nbheditor.pages.dev/collaborative/NE5SDT2"
```

Should open app directly (not browser)!

## Why This Happened

The fingerprint `7E:79:EC:52...` is from your debug.keystore, but your app is signed with a different key (possibly release key or a different debug key).

## For Future Reference

To get the correct fingerprint from any installed app:

```bash
adb shell pm get-app-links com.beeta.nbheditor
```

Look at the "Signatures" line - that's the fingerprint you need in assetlinks.json!

## Quick Summary

1. ✅ Copy `assetlinks-CORRECT.json` content
2. ✅ Upload to `https://nbheditor.pages.dev/.well-known/assetlinks.json`
3. ✅ Run: `adb shell pm verify-app-links --re-verify com.beeta.nbheditor`
4. ✅ Wait 10 seconds
5. ✅ Test from Gmail - should open app! 🎉
