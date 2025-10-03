# Debugging "AI chip failed to initialize"

## 🔍 Diagnose the Problem

### Step 1: Install the Updated Build

```bash
# Rebuild with better logging
./gradlew :rife:assembleQidkDebug

# Install on device
adb install -r rife/build/outputs/apk/qidk/debug/rife-qidk-debug.apk
```

### Step 2: Watch Logs in Real-Time

```bash
# Clear old logs and monitor
adb logcat -c
adb logcat | grep -E "RealTime|SNPE|DLC"
```

### Step 3: Reproduce the Issue

1. Open the app
2. Tap "Real-Time 🚀" mode
3. Watch the logcat output

---

## ✅ What SUCCESS Looks Like

You should see logs like this:

```
RealTimePlayerScreen: Copying DLC from assets to /data/.../cache/rife_3_cached.dlc
RealTimePlayerScreen: Copied 29408095 bytes
RealTimePlayerScreen: DLC URI: file:///data/.../cache/rife_3_cached.dlc
RealTimeInterpolator: initializeNetwork called with URI: file://...
RealTimeInterpolator: Model file found: /data/.../rife_3_cached.dlc (29408095 bytes)
RealTimeInterpolator: Initializing SNPE for real-time inference...
RealTimeInterpolator: ✅ SNPE initialized successfully on runtime: DSP
RealTimeInterpolator: ✅ Ready for real-time interpolation
```

---

## ❌ Common Errors and Solutions

### Error 1: "Model file does not exist"

**Logs:**
```
RealTimeInterpolator: Model file does not exist: /data/.../rife_3_cached.dlc
```

**Cause:** DLC file wasn't copied from assets

**Solution:**
```bash
# Check if DLC is in APK
unzip -l rife/build/outputs/apk/qidk/debug/rife-qidk-debug.apk | grep dlc

# Should show:
# 29408095  01-01-1981 01:01   assets/rife_3_cached.dlc

# If missing, check your assets folder:
ls -lh rife/src/qidk/assets/
```

---

### Error 2: "Model file path is null"

**Logs:**
```
RealTimeInterpolator: Model file path is null from URI: null
```

**Cause:** copyDlcFromAssets() returned null

**Look for:**
```
RealTimePlayerScreen: Failed to copy DLC: [error message]
```

**Solution:**
Check file permissions and storage space:
```bash
adb shell "df -h | grep data"
adb shell "ls -l /data/data/com.example.rife.qidk/cache/"
```

---

### Error 3: SNPE Initialization Failed

**Logs:**
```
RealTimeInterpolator: ❌ Failed to initialize SNPE: [error]
```

**Common causes:**

#### 3a. DSP Not Available
```
Error: "DSP runtime not found" or "No DSP support"
```

**Solution:** DSP might not be available. Try CPU fallback:
```kotlin
// In RealTimeFrameInterpolator.kt, change line 85:
.setRuntimeOrder(NeuralNetwork.Runtime.CPU)  // Instead of DSP
```

#### 3b. SNPE Version Mismatch
```
Error: "Unsupported model version"
```

**Solution:** Model might be for wrong SNPE version.
Check SNPE version:
```bash
adb shell "dumpsys package com.qualcomm.qti.snpe | grep version"
```

#### 3c. Missing Native Libraries
```
Error: "UnsatisfiedLinkError: libSNPE.so"
```

**Solution:** Check if SNPE libs are included:
```bash
unzip -l rife/build/outputs/apk/qidk/debug/rife-qidk-debug.apk | grep "libSNPE.so"

# Should show:
# lib/arm64-v8a/libSNPE.so
```

---

## 🛠️ Debug Commands

### Check App Installation
```bash
adb shell pm list packages | grep rife
# Should show: package:com.example.rife.qidk
```

### Check Cache Directory
```bash
adb shell "run-as com.example.rife.qidk ls -lh /data/data/com.example.rife.qidk/cache/"
# Should eventually show: rife_3_cached.dlc (29M)
```

### Check Available Space
```bash
adb shell "df -h /data"
# Make sure there's >100MB free
```

### Clear App Data (Fresh Start)
```bash
adb shell pm clear com.example.rife.qidk
# Then reinstall and try again
```

### Full Detailed Logs
```bash
# Get ALL logs (verbose)
adb logcat *:V | grep -C 5 "SNPE\|RealTime"
```

---

## 🔬 Detailed Debugging

### Enable SNPE Debug Logging

Add this to `RealTimeFrameInterpolator.kt` before building the network:

```kotlin
// Add before line 82
System.setProperty("debug.snpe.log", "1")
Log.d(TAG, "SNPE debug logging enabled")
```

Then rebuild:
```bash
./gradlew :rife:assembleQidkDebug
adb install -r rife/build/outputs/apk/qidk/debug/rife-qidk-debug.apk
```

### Check Device Capabilities

```bash
# Check if device has Hexagon DSP
adb shell "cat /proc/cpuinfo | grep -i qualcomm"

# Check Android version
adb shell getprop ro.build.version.release

# Check SoC model
adb shell getprop ro.product.board
```

---

## 📊 Expected File Sizes

| File | Expected Size |
|------|---------------|
| `rife_3_cached.dlc` | ~29 MB (29,408,095 bytes) |
| APK (qidkDebug) | ~357 MB |
| libSNPE.so | ~4-5 MB |

---

## 🚨 If Nothing Works

### Last Resort: Use Batch Mode Instead

The batch mode still works with RIFE/SNPE for processing videos:

1. Switch to "Batch Mode" tab
2. Pick video
3. Click "Interpolate (x2 fps)"
4. This uses the same SNPE model but in batch mode

If batch mode works but real-time doesn't, the issue is specific to the real-time player implementation, not SNPE itself.

---

## 📝 Collect Debug Info

If you need help, collect this info:

```bash
# 1. Device info
adb shell getprop | grep -E "ro.product|ro.build"

# 2. App version
adb shell dumpsys package com.example.rife.qidk | grep versionName

# 3. Full error logs
adb logcat -d > logcat_error.txt

# 4. File listing
unzip -l rife/build/outputs/apk/qidk/debug/rife-qidk-debug.apk > apk_contents.txt
```

Share these files for troubleshooting.

---

## ✅ Quick Checklist

- [ ] DLC file is in APK (`assets/rife_3_cached.dlc`)
- [ ] APK is ~357 MB (has all libraries)
- [ ] Device is Qualcomm Snapdragon with DSP
- [ ] Android 10+ (API 29+)
- [ ] Enough storage space (>100MB free)
- [ ] App has storage permissions
- [ ] Built `qidkDebug` not `mockDebug`
- [ ] Logcat shows DLC being copied
- [ ] No "file not found" errors in logs

---

## 💡 Tips

1. **Always watch logcat** - It shows exactly what's failing
2. **Try CPU runtime first** - Easier to debug than DSP
3. **Clear app data** between tests for clean state
4. **Check batch mode** - If it works, SNPE is functional
5. **Verify device** - Make sure it's actually a Snapdragon with DSP

---

Good luck! The new logs should tell us exactly what's wrong. 🔍

