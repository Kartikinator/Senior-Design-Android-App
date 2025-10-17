# Real-Time Video Frame Interpolation on Android

AI-powered mobile application that performs real-time video frame interpolation using RIFE (Real-Time Intermediate Flow Estimation) on Qualcomm Snapdragon devices with hardware acceleration.

## Overview

This Senior Design project converts 30fps videos to smooth 60fps playback using neural network-based frame interpolation, optimized for Qualcomm's Hexagon DSP/NPU.

### Key Features

- **Real-Time Processing**: On-the-fly interpolation during video playback (<16ms per frame)
- **Hardware Acceleration**: Utilizes Qualcomm Hexagon DSP for efficient AI inference
- **Two Modes**: 
  - Batch processing for saving interpolated videos
  - Real-time playback for instant preview
- **RIFE Lite Model**: Quantized INT8 model optimized for mobile inference

## Technical Stack

- **Framework**: Android (Kotlin + Jetpack Compose)
- **AI Model**: RIFE Lite (quantized)
- **Inference Engine**: Qualcomm SNPE (Snapdragon Neural Processing Engine)
- **Target Hardware**: Qualcomm Snapdragon SoCs with Hexagon DSP
- **Video Processing**: MediaCodec, FFmpeg

## Architecture

```
Video Input (30fps) → MediaCodec Decoder → RIFE Model (Hexagon DSP) → Interpolated Frames → 60fps Output
```

The system uses a quantized RIFE Lite model (`rife_3_cached.dlc`) that runs on the Qualcomm Hexagon DSP for hardware-accelerated inference, achieving 10-15ms interpolation time per frame.

## Build & Install

### Prerequisites

- Android Studio
- Android SDK (API 29+)
- Qualcomm Snapdragon device with Hexagon DSP
- ADB tools

### Build Instructions

```bash
# Clone the repository
git clone <repository-url>
cd Senior-Design-Android-App

# Build the qidk variant (includes SNPE and RIFE model)
./gradlew :rife:assembleQidkDebug

# Install on device
./gradlew :rife:installQidkDebug

# Or manually install
adb install -r rife/build/outputs/apk/qidk/debug/rife-qidk-debug.apk
```

## Usage

### Batch Mode
1. Open the app and select "Batch Mode"
2. Pick a video from device storage
3. Tap "Interpolate (x2 fps)"
4. Wait for processing to complete
5. Play and compare original vs interpolated video

### Real-Time Mode
1. Select "Real-Time Mode"
2. Pick a video
3. Wait for "AI chip ready ✓" status
4. Press "Play (60fps)" for instant interpolated playback

## Performance

| Input | Output | Interpolation Time | Real-Time Capable |
|-------|--------|-------------------|-------------------|
| 720p @ 30fps | 720p @ 60fps | ~10-12ms (DSP) | ✅ Yes |
| 1080p @ 30fps | 1080p @ 60fps | ~15-18ms (DSP) | ⚠️ Marginal |

**Recommended**: Use 720p (1280x720) videos for best real-time performance.

## Project Structure

```
Senior-Design-Android-App/
├── rife/                          # Main interpolation module
│   ├── src/qidk/                  # SNPE implementation (hardware-accelerated)
│   │   ├── assets/                # RIFE model (rife_3_cached.dlc)
│   │   └── java/com/example/app/
│   │       ├── video/             # Interpolation engines
│   │       └── ui/                # User interface
│   ├── src/main/                  # Shared code
│   └── libs/                      # SNPE SDK, FFmpeg
├── app/                           # Legacy TFLite implementation
└── HTPInterpolatorProject/        # Reference project
```

## Model Information

- **Model**: RIFE Lite v3
- **Format**: Qualcomm DLC (Deep Learning Container)
- **Quantization**: INT8 (from FP32)
- **Size**: ~29 MB
- **Input**: Two 720p frames (1280×720×3 RGB)
- **Output**: One interpolated 720p frame
- **Runtime**: Hexagon DSP with CPU fallback

## Development Team

Senior Design Project  
[Add team member names and roles]

## License

[Add license information]

## Acknowledgments

- RIFE: Real-Time Intermediate Flow Estimation
- Qualcomm SNPE SDK
- FFmpeg for video processing
