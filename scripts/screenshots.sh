#!/usr/bin/env bash
set -euo pipefail
mkdir -p screenshots
adb install -r app/build/outputs/apk/debug/app-debug.apk
shoot() { # $1 screen, $2 suffix
  for try in 1 2 3; do
    adb shell am force-stop com.bingemote
    adb shell am start -n com.bingemote/.MainActivity --es screen "$1"
    sleep 8
    adb shell pidof com.bingemote
    adb exec-out screencap -p > "screenshots/$1$2.png"
    if ! black "screenshots/$1$2.png"; then break; fi
    echo "black frame on $1$2 (try $try), retrying"
    sleep 4
  done
}
# Emulate the primary tester's phone (Galaxy S22 Ultra class: 1440x3088 @ ~649dpi)
# so the shots match what he sees. Restored at the end.
adb shell wm size 1440x2807
adb shell wm density 649
# Cold start after a density/font change can capture a black frame: warm up first,
# and retry any capture that comes back near-black.
black() { [ "$(stat -c%s "$1")" -lt 80000 ]; }
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.bingemote/.MainActivity --es screen main >/dev/null
sleep 6
for scale in 1.0 1.3; do
  adb shell settings put system font_scale "$scale"
  suffix=""
  [ "$scale" = "1.3" ] && suffix="-13x"
  for screen in setup main mouse settings movies keyboard subtitles update; do
    shoot "$screen" "$suffix"
  done
done
adb shell settings put system font_scale 1.0
adb shell wm size reset
adb shell wm density reset
adb logcat -d -s AndroidRuntime:E > screenshots/runtime-errors.txt
if grep -q 'FATAL EXCEPTION' screenshots/runtime-errors.txt; then cat screenshots/runtime-errors.txt; exit 1; fi
