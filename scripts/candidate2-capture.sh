#!/usr/bin/env bash
# Candidate-2 capture (render only): Home, the play/pause demo ring and the scrolled Settings accent picker, for orange (0), dusty blue (2), terracotta (4), taupe (7).
set -uo pipefail
PKG=com.bingemote
OUT=candidate2
mkdir -p $OUT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put global hide_error_dialogs 1
black() { [ "$(stat -c%s "$1")" -lt 30000 ]; }
shoot() { # name screen extra...
  local NAME="$1" SCR="$2"; shift 2
  for try in 1 2 3; do
    adb shell am force-stop $PKG
    adb shell am start -n $PKG/com.bingemote.MainActivity --es screen "$SCR" "$@" >/dev/null
    sleep 8
    adb exec-out screencap -p > "$OUT/$NAME.png"
    if ! black "$OUT/$NAME.png"; then break; fi
    sleep 4
  done
}
adb shell wm size 1080x2340; adb shell wm density 440; adb shell settings put system font_scale 1.0; sleep 3
adb shell am start -n $PKG/com.bingemote.MainActivity --es screen main >/dev/null; sleep 6
for A in 0 2 4 7; do
  shoot accent$A-home main --ei accent $A
  shoot accent$A-icondemo icondemo --ei accent $A
  shoot accent$A-settings-top settings --ei accent $A
  adb shell input swipe 540 1900 540 500 400; sleep 1; adb shell input swipe 540 1900 540 500 400; sleep 1; adb shell input swipe 540 1900 540 500 400; sleep 2
  adb exec-out screencap -p > "$OUT/accent$A-settings-picker.png"
done
# High-density frames for edge inspection: 1440x3120 at 560 dpi, and the 1080x2340 at 480 reference.
adb shell wm size 1440x3120; adb shell wm density 560; sleep 3
shoot hi560-home main --ei accent 0
shoot hi560-icondemo icondemo --ei accent 0
adb shell wm size 1080x2310; adb shell wm density 480; sleep 3
shoot ref480-home main --ei accent 0
adb logcat -d -s AndroidRuntime:E > $OUT/crashes.txt || true

# --- Pairing screens and the help card (debug demo states; no network). ---
adb shell wm size 1080x2340; adb shell wm density 440; sleep 3
shoot help-settings-bottom settings --ei accent 0
adb shell input swipe 540 1900 540 300 300; sleep 1; adb shell input swipe 540 1900 540 300 300; sleep 1; adb shell input swipe 540 1900 540 300 300; sleep 1; adb shell input swipe 540 1900 540 300 300; sleep 2
adb exec-out screencap -p > "$OUT/help-settings-bottom.png"
shoot pair1-install setup
shoot pair2-find setup --ez pairdemo true
shoot pair3-chosen setup --ez pairdemo true --ez pairdemoselect true
for ST in ok unreachable refused; do
  shoot pair4-result-$ST-top setup --ez vlcpending true --es pairdemoauto $ST
  adb shell input swipe 540 1900 540 300 300; sleep 1; adb shell input swipe 540 1900 540 300 300; sleep 2
  adb exec-out screencap -p > "$OUT/pair4-result-$ST-bottom.png"
done
adb shell wm size reset; adb shell wm density reset
adb logcat -d -s AndroidRuntime:E > $OUT/crashes2.txt || true
