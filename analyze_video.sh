#!/bin/bash
# Script to analyze interpolated videos
# Usage: ./analyze_video.sh <video_file.mp4>

if [ $# -eq 0 ]; then
    echo "Usage: $0 <video_file.mp4>"
    echo ""
    echo "Available videos:"
    ls -lh output_videos/*.mp4 2>/dev/null || echo "  No videos in output_videos/"
    exit 1
fi

VIDEO="$1"

if [ ! -f "$VIDEO" ]; then
    echo "Error: Video file not found: $VIDEO"
    exit 1
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Video Analysis: $(basename "$VIDEO")"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# Basic file info
echo "📁 File Info:"
ls -lh "$VIDEO"
echo ""

# Video metadata
echo "🎬 Video Metadata:"
ffprobe -v error -show_entries format=duration,size,bit_rate -of default=noprint_wrappers=1 "$VIDEO" 2>/dev/null
echo ""

# Stream info
echo "📺 Stream Info:"
ffprobe -v error -select_streams v:0 -show_entries stream=width,height,r_frame_rate,codec_name,pix_fmt -of default=noprint_wrappers=1 "$VIDEO" 2>/dev/null
echo ""

# Frame count
echo "🎞️  Frame Count:"
FRAMES=$(ffprobe -v error -select_streams v:0 -count_packets -show_entries stream=nb_read_packets -of csv=p=0 "$VIDEO" 2>/dev/null)
echo "nb_frames=$FRAMES"
echo ""

# Extract sample frames
read -p "Extract sample frames? [y/N] " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    BASENAME=$(basename "$VIDEO" .mp4)
    FRAME_DIR="analysis_frames/${BASENAME}"
    mkdir -p "$FRAME_DIR"
    
    echo "Extracting frames to: $FRAME_DIR/"
    ffmpeg -i "$VIDEO" -vf "select='not(mod(n\,10))'" -vsync vfr "$FRAME_DIR/frame_%04d.png" -y 2>&1 | grep -E "frame=|video:"
    
    echo ""
    echo "✓ Frames extracted: $(ls "$FRAME_DIR" | wc -l | tr -d ' ') frames"
    echo "  Location: $FRAME_DIR/"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Next Steps:"
echo "  • Play video: open '$VIDEO'"
echo "  • Compare frames: open analysis_frames/"
echo "  • Re-encode: ffmpeg -i '$VIDEO' -c:v libx264 -crf 18 output.mp4"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

