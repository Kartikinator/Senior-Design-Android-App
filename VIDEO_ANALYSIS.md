# Video Output & Analysis Guide

This guide explains how to retrieve and analyze interpolated videos from your Android device.

## 📱 Output Location on Device

Interpolated videos are saved to:
```
/sdcard/Android/data/com.example.rife.<flavor>/files/Movies/
```

Where `<flavor>` is either:
- `qidk` - RIFE model with Qualcomm SNPE acceleration
- `mock` - Simple pixel averaging interpolation

Filenames follow the pattern: `interpolated_<timestamp>.mp4`

## 🔽 Downloading Videos

### Option 1: Quick Download Script (Recommended)

```bash
# Download videos from qidk flavor (RIFE model)
./pull_videos.sh qidk

# Download videos from mock flavor
./pull_videos.sh mock

# Default is qidk
./pull_videos.sh
```

Videos will be downloaded to: `output_videos/`

### Option 2: Manual ADB Commands

```bash
# List videos on device
adb shell "ls -lh /sdcard/Android/data/com.example.rife.qidk/files/Movies/"

# Pull specific video
adb pull /sdcard/Android/data/com.example.rife.qidk/files/Movies/interpolated_<timestamp>.mp4 .

# Pull all videos
adb pull /sdcard/Android/data/com.example.rife.qidk/files/Movies/. output_videos/
```

## 🔍 Analyzing Videos

### Quick Analysis

```bash
./analyze_video.sh output_videos/interpolated_<timestamp>.mp4
```

This will show:
- File size and bitrate
- Resolution and codec
- Frame rate (should be ~2x original)
- Frame count
- Option to extract sample frames

### Manual Analysis Commands

#### Get video info
```bash
ffprobe -v error -show_entries format output_videos/video.mp4
```

#### Check frame rate
```bash
ffprobe -v error -select_streams v:0 -show_entries stream=r_frame_rate -of default=noprint_wrappers=1:nokey=1 output_videos/video.mp4
```

#### Count total frames
```bash
ffprobe -v error -count_packets -show_entries stream=nb_read_packets -of csv=p=0 output_videos/video.mp4
```

#### Extract all frames as PNG
```bash
mkdir frames
ffmpeg -i output_videos/video.mp4 frames/frame_%06d.png
```

#### Extract every 10th frame
```bash
mkdir sample_frames
ffmpeg -i output_videos/video.mp4 -vf "select='not(mod(n\,10))'" -vsync vfr sample_frames/frame_%04d.png
```

## 📊 What to Look For

### Quality Metrics

1. **Frame Rate Doubling**
   - Original: ~30 fps → Interpolated: ~60 fps
   - Check with: `ffprobe -show_streams video.mp4 | grep r_frame_rate`

2. **Visual Quality**
   - Smooth motion without artifacts
   - No ghosting or blurring
   - Consistent object edges

3. **File Size**
   - Should be proportional to frame count increase
   - Check compression efficiency

### Comparing RIFE vs Mock

Create test videos with both flavors:

```bash
# Run with qidk flavor (RIFE)
./pull_videos.sh qidk

# Run with mock flavor (simple averaging)  
./pull_videos.sh mock

# Analyze both
./analyze_video.sh output_videos/interpolated_qidk_<timestamp>.mp4
./analyze_video.sh output_videos/interpolated_mock_<timestamp>.mp4
```

**Expected differences:**
- RIFE should have smoother, more natural motion
- Mock will show simple blending/averaging between frames
- RIFE may have slightly longer processing time per frame

## 🎬 Playing Videos

### macOS
```bash
open output_videos/video.mp4
```

### VLC (any platform)
```bash
vlc output_videos/video.mp4
```

### Frame-by-frame playback in VLC
- Play video
- Press `E` to advance one frame
- Press `Shift+S` to take snapshot

## 🔬 Advanced Analysis

### Side-by-side comparison
```bash
# Extract frames from both videos
ffmpeg -i original.mp4 original_frames/frame_%06d.png
ffmpeg -i interpolated.mp4 interpolated_frames/frame_%06d.png

# Create side-by-side comparison
ffmpeg -i original.mp4 -i interpolated.mp4 -filter_complex hstack comparison.mp4
```

### Check interpolation artifacts
```bash
# Extract high-quality frames for inspection
ffmpeg -i output_videos/video.mp4 -qscale:v 2 analysis_frames/frame_%06d.png

# Open in image viewer
open analysis_frames/
```

### Performance metrics
The app logs interpolation performance. Check logcat:
```bash
adb logcat | grep "SNPE\|Interpolation"
```

## 📝 Notes

- Videos use H.264 (libx264) codec by default
- Fallback codecs: mpeg4, h264_mediacodec
- Pixel format: yuv420p
- Intermediate frames stored as PNG during processing
- Temporary files cleaned up after encoding

## 🐛 Troubleshooting

### No videos found
- Make sure you've run the app and processed a video
- Check app permissions (Storage access)
- Verify correct app flavor is installed

### Permission denied
```bash
# Grant storage permissions manually
adb shell pm grant com.example.rife.qidk android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant com.example.rife.qidk android.permission.WRITE_EXTERNAL_STORAGE
```

### Video won't play
- Check codec support: `ffprobe output_videos/video.mp4`
- Re-encode with compatible codec:
  ```bash
  ffmpeg -i output_videos/video.mp4 -c:v libx264 -crf 23 -pix_fmt yuv420p compatible.mp4
  ```

