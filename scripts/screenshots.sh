#!/usr/bin/env bash
set -euo pipefail
mkdir -p screenshots
adb install -r app/build/outputs/apk/debug/app-debug.apk
shoot() { # $1 screen, $2 suffix
  adb shell am force-stop com.bingemote
  adb shell am start -n com.bingemote/.MainActivity --es screen "$1"
  sleep 8
  adb shell pidof com.bingemote
  adb exec-out screencap -p > "screenshots/$1$2.png"
}
for scale in 1.0 1.3; do
  adb shell settings put system font_scale "$scale"
  suffix=""
  [ "$scale" = "1.3" ] && suffix="-13x"
  for screen in setup main mouse settings movies keyboard subtitles update; do
    shoot "$screen" "$suffix"
  done
done
adb shell settings put system font_scale 1.0
adb logcat -d -s AndroidRuntime:E > screenshots/runtime-errors.txt
if grep -q 'FATAL EXCEPTION' screenshots/runtime-errors.txt; then cat screenshots/runtime-errors.txt; exit 1; fi
