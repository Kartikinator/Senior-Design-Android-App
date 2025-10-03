# How to Use the App

## 🚀 Quick Start

### Step 1: Install the App

```bash
# Build and install on your Pineapple board
./gradlew :rife:installQidkDebug
```

### Step 2: Launch the App

You'll see a screen with two modes at the top:

```
┌─────────────────────────────────────┐
│  RIFE Frame Interpolation           │
│  ┌─────────┐  ┌──────────────┐      │
│  │ Batch   │  │ Real-Time 🚀 │      │
│  └─────────┘  └──────────────┘      │
│  [ Pick Video ]                     │
└─────────────────────────────────────┘
```

---

## 📺 Two Modes Explained

### Mode 1: **Batch Mode** (Original)

**What it does:**
- Processes the ENTIRE video first
- Saves interpolated video to a file
- Then you can play both versions

**How to use:**
1. Click **"Batch Mode"** chip
2. Click **"Pick Video"**  
3. Select your video
4. Click **"Interpolate (x2 fps)"** button
5. Wait for processing (shows progress bar)
6. Play original (top) and interpolated (bottom) videos

**Best for:**
- Creating high-quality output files
- Videos you want to save
- Comparing before/after

---

### Mode 2: **Real-Time Mode** 🚀 (NEW!)

**What it does:**
- Plays video immediately with on-the-fly interpolation
- Uses Qualcomm Hexagon DSP for AI acceleration
- **NO waiting** - starts playing right away!

**How to use:**
1. Click **"Real-Time 🚀"** chip
2. Click **"Pick Video"**
3. Select your video
4. Wait for "AI chip ready ✓" (~1 second)
5. Click **"Play (60fps)"**
6. **That's it!** Video plays smoothly at 60fps

**Best for:**
- Instant playback
- Testing different videos quickly
- Seeing interpolation quality immediately
- Live preview

---

## 📖 Step-by-Step Example

### Using Real-Time Mode (Recommended)

```
1. Open app
   └─> You see: "RIFE Frame Interpolation"

2. Tap "Real-Time 🚀" chip at top
   └─> Description changes to: 
       "Play with on-the-fly interpolation (Hexagon DSP)"

3. Tap "Pick Video" button
   └─> File picker opens
   └─> Select a video (e.g., from DCIM or Downloads)

4. Wait ~1 second
   └─> Status shows: "Initializing..."
   └─> Then: "AI chip ready ✓"

5. Tap "Play (60fps)" button
   └─> Video starts playing immediately!
   └─> Smooth 60fps from your 30fps video
   └─> Status shows: "Playing with real-time interpolation..."

6. Controls:
   - "Pause" - Pause playback
   - "Resume" - Continue playback
   - "Stop" - Stop and reset
```

### Using Batch Mode (Original)

```
1. Open app
   └─> Tap "Batch Mode" chip

2. Tap "Pick top video" button
   └─> Select your video

3. Tap "Interpolate (x2 fps)" button
   └─> Progress bar appears
   └─> Wait for processing (takes ~30 seconds for 5s video)

4. When done:
   └─> Top player shows original video
   └─> Bottom player shows interpolated video
   └─> Shows: "Average interpolate time: XX.X ms/frame"

5. Tap "Play both" to compare side-by-side
```

---

## ⚡ Performance Tips

### For Best Real-Time Performance:

1. **Use 720p videos** (1280x720)
   - Faster than 1080p
   - Still great quality
   
2. **Shorter videos first**
   - Test with 5-10 second clips
   - Then try longer videos

3. **Close background apps**
   - Free up RAM and CPU
   - Better DSP performance

4. **Let device cool if hot**
   - Check if device is warm
   - Better performance when cool

---

## 🎯 What to Expect

### Real-Time Mode Performance:

| Input Video | Expected Result |
|-------------|----------------|
| 720p @ 30fps | ✅ Smooth 60fps |
| 1080p @ 30fps | ⚠️ 40-50fps (may drop frames) |
| 4K @ 30fps | ❌ Too slow for real-time |

**Recommendation**: Use 720p for best experience

### Status Messages:

| Message | Meaning |
|---------|---------|
| "Initializing..." | Loading RIFE model (~500ms) |
| "AI chip ready ✓" | Ready to play! |
| "Playing with real-time interpolation..." | Currently playing |
| "Paused" | Playback paused |
| "Stopped" | Playback stopped |

---

## 🔍 Monitoring Performance

### Check Logcat:

```bash
adb logcat | grep "RealTimeInterpolator"
```

**Good performance looks like:**
```
RealTimeInterpolator: SNPE initialized successfully on runtime: DSP
RealTimeInterpolator: Interpolation took 12ms for timestamp 123456
RealTimeInterpolator: Interpolation took 11ms for timestamp 234567
```

**Problems look like:**
```
RealTimeInterpolator: Interpolation took 45ms (too slow!)
# Or
RealTimeInterpolator: Using runtime: CPU (should be DSP!)
```

---

## ❓ FAQ

**Q: Which mode should I use?**
- **Real-Time**: For instant playback and preview
- **Batch**: For saving files to share or archive

**Q: Why does real-time only work in qidk?**
- Real-time needs the RIFE model and SNPE
- Mock flavor only has simple averaging (no AI)

**Q: Can I save real-time output?**
- Not yet - it only plays to screen
- Use Batch mode to create output files

**Q: How long does initialization take?**
- ~500ms to 1 second
- Only happens once when you switch to Real-Time mode

**Q: What if "AI chip ready" never shows?**
- Check logcat for errors
- Make sure you're using qidkDebug build
- Verify SNPE libraries are included

**Q: Can I use both modes?**
- Yes! Switch between them anytime
- Each mode remembers your video selection

---

## 🎮 Controls Reference

### Real-Time Mode:

| Button | Action |
|--------|--------|
| **Pick Video** | Choose video file |
| **Play (60fps)** | Start real-time playback |
| **Pause** | Pause playback |
| **Resume** | Continue playback |
| **Stop** | Stop and reset |

### Batch Mode:

| Button | Action |
|--------|--------|
| **Pick top video** | Choose input video |
| **Interpolate (x2 fps)** | Process entire video |
| **Play both** | Play original and interpolated side-by-side |

---

## 🚨 Troubleshooting

**Problem**: Real-Time tab doesn't appear

**Solution**: Make sure you built `qidkDebug`, not `mockDebug`

---

**Problem**: "Real-time mode requires qidk build variant"

**Solution**: 
```bash
./gradlew :rife:assembleQidkDebug
adb install -r rife/build/outputs/apk/qidk/debug/rife-qidk-debug.apk
```

---

**Problem**: Video plays at normal speed, not 60fps

**Solution**: 
1. Check logcat for interpolation times
2. If >16ms, try lower resolution video
3. Close background apps

---

**Problem**: App crashes when pressing Play

**Solution**:
1. Check logcat: `adb logcat | grep AndroidRuntime`
2. Try a different video (H.264 codec)
3. Ensure video is <1GB

---

## ✅ Success Checklist

You'll know it's working when:

- ✅ App shows two mode chips (Batch and Real-Time 🚀)
- ✅ After picking video in Real-Time mode, see "AI chip ready ✓"
- ✅ Pressing Play starts video immediately
- ✅ Video looks smooth (60fps not 30fps)
- ✅ Logcat shows interpolation times <16ms

---

Enjoy your AI-powered frame interpolation! 🎉

