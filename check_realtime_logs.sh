#!/bin/bash
echo "🔍 Monitoring Real-Time Interpolator Logs..."
echo "Open the app and switch to Real-Time mode now..."
echo "Press Ctrl+C to stop"
echo ""
adb logcat -c
adb logcat | grep -E "RealTime|SNPE|DLC|Injection"
