#!/bin/bash

# SNPE Runtime Checker
# This script monitors logcat for SNPE runtime information

echo "==================================="
echo "SNPE Runtime Checker"
echo "==================================="
echo ""
echo "Clearing old logs..."
adb logcat -c

echo "Starting log monitor..."
echo "Now open your app and tap 'Interpolate (x2 fps)'"
echo ""
echo "==================================="
echo ""

# Monitor logs with color highlighting
adb logcat -v color | grep --color=always -E "SNPE|Device Information|runtime|DSP|NPU|GPU|CPU|SUCCESS|WARNING|FAILED"
