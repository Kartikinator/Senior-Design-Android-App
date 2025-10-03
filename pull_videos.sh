#!/bin/bash
# Script to pull interpolated videos from Android device
# Usage: ./pull_videos.sh [qidk|mock]

set -e

FLAVOR="${1:-qidk}"  # Default to qidk if not specified
OUTPUT_DIR="output_videos"
PACKAGE_NAME="com.example.rife.${FLAVOR}"
DEVICE_PATH="/sdcard/Android/data/${PACKAGE_NAME}/files/Movies"

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  RIFE Video Downloader"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Flavor: ${FLAVOR}"
echo "Package: ${PACKAGE_NAME}"
echo ""

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ Error: No Android device connected"
    echo "   Please connect your device and enable USB debugging"
    exit 1
fi

echo "✓ Device connected"

# Create output directory
mkdir -p "${OUTPUT_DIR}"

# List videos on device
echo ""
echo "Videos on device:"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if ! adb shell "ls -lh ${DEVICE_PATH}/" 2>/dev/null | tail -n +2; then
    echo "❌ No videos found at ${DEVICE_PATH}"
    echo "   Make sure you've run the app and processed a video"
    exit 1
fi

echo ""
read -p "Pull all videos? [Y/n] " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]] || [[ -z $REPLY ]]; then
    echo ""
    echo "Downloading all videos..."
    
    # Pull all .mp4 files
    adb pull "${DEVICE_PATH}/." "${OUTPUT_DIR}/"
    
    echo ""
    echo "✓ Download complete!"
    echo ""
    echo "Videos saved to: ${OUTPUT_DIR}/"
    ls -lh "${OUTPUT_DIR}"/*.mp4 2>/dev/null || echo "No videos downloaded"
else
    echo "Download cancelled"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Analysis Tips:"
echo "  • Use VLC or QuickTime to play the video"
echo "  • Use FFmpeg to analyze: ffmpeg -i video.mp4"
echo "  • Check frame rate: ffprobe -v error -select_streams v:0 -show_entries stream=r_frame_rate -of default=noprint_wrappers=1:nokey=1 video.mp4"
echo "  • Extract frames: ffmpeg -i video.mp4 frames/frame_%04d.png"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

