#!/usr/bin/env bash
set -euo pipefail
mkdir -p screenshots
adb install -r app/build/outputs/apk/debug/app-debug.apk
for screen in setup main mouse settings movies; do
  adb shell am force-stop com.bingemote
  adb shell am start -n com.bingemote/.MainActivity --es screen "$screen"
  sleep 8
  adb shell pidof com.bingemote
  adb exec-out screencap -p > "screenshots/$screen.png"
done
adb logcat -d -s AndroidRuntime:E > screenshots/runtime-errors.txt
if grep -q 'FATAL EXCEPTION' screenshots/runtime-errors.txt; then cat screenshots/runtime-errors.txt; exit 1; fi
