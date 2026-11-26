#!/usr/bin/env bash
# Candidate capture (compile/render only): header/first-card at two generic
# geometries and two font scales, plus a listing of the image's icon-shape overlays.
set -uo pipefail
PKG=com.bingemote
OUT=candidate
mkdir -p $OUT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put global hide_error_dialogs 1
adb shell cmd overlay list > $OUT/overlays.txt 2>&1 || true
adb shell cmd overlay list --user 0 2>/dev/null | grep -i -E "icon|shape" > $OUT/overlays-icon.txt || true
black() { [ "$(stat -c%s "$1")" -lt 30000 ]; }
shoot() { # lane name screen extra...
  local LANE="$1" NAME="$2" SCR="$3"; shift 3
  for try in 1 2 3; do
    adb shell am force-stop $PKG
    adb shell am start -n $PKG/com.bingemote.MainActivity --es screen "$SCR" "$@" >/dev/null
    sleep 8
    adb exec-out screencap -p > "$OUT/$LANE-$NAME.png"
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    adb exec-out cat /sdcard/ui.xml > "$OUT/$LANE-$NAME.xml" 2>/dev/null || true
    if ! black "$OUT/$LANE-$NAME.png"; then break; fi
    sleep 4
  done
}
lane() { # name size density fontscale
  adb shell wm size "$2"; adb shell wm density "$3"; adb shell settings put system font_scale "$4"; sleep 3
  adb shell am start -n $PKG/com.bingemote.MainActivity --es screen main >/dev/null; sleep 6
  shoot "$1" main main
  shoot "$1" settings settings
  shoot "$1" movies movies
  shoot "$1" yourpc yourpc
}
lane g2310-480-f1.0 1080x2310 480 1.0
lane g2340-440-f1.0 1080x2340 440 1.0
lane g2340-440-f1.3 1080x2340 440 1.3
adb shell wm size reset; adb shell wm density reset; adb shell settings put system font_scale 1.0
adb logcat -d -s AndroidRuntime:E > $OUT/crashes.txt || true
adb logcat -d -s TopBarRow:I > $OUT/topbar-rows.txt || true
adb logcat -d -s TopBarRow:I > $OUT/topbar-rows.txt || true

# Accent captures (440 f1.0): Home and the scrolled Settings picker for orange (0), burnt orange (2), dusty blue (3), terracotta (5).
adb shell wm size 1080x2340; adb shell wm density 440; adb shell settings put system font_scale 1.0; sleep 3
for A in 0 2 3 5; do
  shoot accent$A home main --ei accent $A
  shoot accent$A settings-top settings --ei accent $A
  adb shell input swipe 540 1900 540 500 400; sleep 1; adb shell input swipe 540 1900 540 500 400; sleep 1; adb shell input swipe 540 1900 540 500 400; sleep 2
  adb exec-out screencap -p > "$OUT/accent$A-settings-picker.png"
done
shoot accent0 reset main --ei accent 0
adb shell wm size reset; adb shell wm density reset
