#!/usr/bin/env bash
set -euo pipefail
mkdir -p screenshots
adb install -r app/build/outputs/apk/debug/app-debug.apk
dismiss_anr() { # tap "Wait" if an "isn't responding" system dialog is up
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 0
  adb shell cat /sdcard/ui.xml | grep -q "isn't responding" || return 0
  B=$(adb shell cat /sdcard/ui.xml | grep -o 'text="Wait"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o "\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" | head -1)
  [ -z "$B" ] && return 0
  X1=$(echo $B | cut -d'[' -f2 | cut -d',' -f1); Y1=$(echo $B | cut -d'[' -f2 | cut -d',' -f2 | cut -d']' -f1)
  X2=$(echo $B | cut -d'[' -f3 | cut -d',' -f1); Y2=$(echo $B | cut -d'[' -f3 | cut -d',' -f2 | cut -d']' -f1)
  adb shell input tap $(( (X1+X2)/2 )) $(( (Y1+Y2)/2 ))
  sleep 2
  return 0
}
shoot() { # $1 screen, $2 suffix
  for try in 1 2 3; do
    adb shell am force-stop com.bingemote
    adb shell am start -n com.bingemote/.MainActivity --es screen "$1"
    sleep 8
    adb shell pidof com.bingemote
    dismiss_anr
    adb exec-out screencap -p > "screenshots/$1$2.png"
    if ! black "screenshots/$1$2.png"; then break; fi
    echo "black frame on $1$2 (try $try), retrying"
    sleep 4
  done
}
# Emulate the primary tester's phone (Galaxy S22 Ultra class: 1440x3088 @ ~649dpi)
# so the shots match what he sees. Restored at the end.
adb shell settings put global hide_error_dialogs 1
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
# Seek-on-top variant (Settings toggle), for review
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --ez seektop true
sleep 8
dismiss_anr
adb exec-out screencap -p > screenshots/main-seektop.png
# Seek drag reproduction: make the settings state explicit and use measured raw
# pixels from the 1440x2807 emulator screenshot (seek track x=44..1396, center y=913).
# Hold stationary long enough to unlock before starting the drag. Then wander >70px
# vertically so the finger is outside the 28dp hit target while it keeps tracking.
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --ez seektop true --ez demostatus true
sleep 6
dismiss_anr
set -euo pipefail
mkdir -p screenshots
adb install -r app/build/outputs/apk/debug/app-debug.apk
dismiss_anr() { # tap "Wait" if an "isn't responding" system dialog is up
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 0
  adb shell cat /sdcard/ui.xml | grep -q "isn't responding" || return 0
  B=$(adb shell cat /sdcard/ui.xml | grep -o 'text="Wait"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o "\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" | head -1)
  [ -z "$B" ] && return 0
  X1=$(echo $B | cut -d'[' -f2 | cut -d',' -f1); Y1=$(echo $B | cut -d'[' -f2 | cut -d',' -f2 | cut -d']' -f1)
  X2=$(echo $B | cut -d'[' -f3 | cut -d',' -f1); Y2=$(echo $B | cut -d'[' -f3 | cut -d',' -f2 | cut -d']' -f1)
  adb shell input tap $(( (X1+X2)/2 )) $(( (Y1+Y2)/2 ))
  sleep 2
  return 0
}
shoot() { # $1 screen, $2 suffix
  for try in 1 2 3; do
    adb shell am force-stop com.bingemote
    adb shell am start -n com.bingemote/.MainActivity --es screen "$1"
    sleep 8
    adb shell pidof com.bingemote
    dismiss_anr
    adb exec-out screencap -p > "screenshots/$1$2.png"
    if ! black "screenshots/$1$2.png"; then break; fi
    echo "black frame on $1$2 (try $try), retrying"
    sleep 4
  done
}
# Emulate the primary tester's phone (Galaxy S22 Ultra class: 1440x3088 @ ~649dpi)
# so the shots match what he sees. Restored at the end.
adb shell settings put global hide_error_dialogs 1
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
# Seek-on-top variant (Settings toggle), for review
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --ez seektop true
sleep 8
dismiss_anr
adb exec-out screencap -p > screenshots/main-seektop.png
# Seek drag reproduction: make the settings state explicit and use measured raw
# pixels from the 1440x2807 emulator screenshot (seek track x=44..1396, center y=913).
# Hold stationary long enough to unlock before starting the drag. Then wander >70px
# vertically so the finger is outside the 28dp hit target while it keeps tracking.
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --ez seektop true --ez demostatus true
sleep 6
dismiss_anr
adb shell input swipe 400 913 400 913 1100
# Separate gesture after the 850ms hold; horizontal movement continues off the bar.
adb shell input swipe 400 913 1150 760 3000 &
SWIPE_PID=$!
sleep 2.4
adb exec-out screencap -p > screenshots/main-seekdrag.png
adb shell uiautomator dump /sdcard/seek-mid.xml >/dev/null
adb shell cat /sdcard/seek-mid.xml > screenshots/seek-mid.xml
MID_TEXT=$(grep -o 'text="[^"]*"' screenshots/seek-mid.xml)
echo "$MID_TEXT" > screenshots/seek-mid-text.txt
# Drag preview must change both existing time counters away from the seed state.
grep -Eq 'text="[0-9]+:[0-9]{2}(:[0-9]{2})?"' screenshots/seek-mid.xml
grep -q 'text="-' screenshots/seek-mid.xml
if echo "$MID_TEXT" | grep -q 'text="25:00"'; then echo 'drag elapsed preview stayed at seed time'; exit 1; fi
if echo "$MID_TEXT" | grep -q 'text="-1:25:24"'; then echo 'drag remaining preview stayed at seed time'; exit 1; fi
wait "$SWIPE_PID"
sleep 0.3
adb exec-out screencap -p > screenshots/main-seekdrag-up.png
adb shell uiautomator dump /sdcard/seek-up.xml >/dev/null
adb shell cat /sdcard/seek-up.xml > screenshots/seek-up.xml
# After release the displayed counters return to the seed poll, while the thumb
# remains at pendingSeek; BMSeek confirms the app issued the absolute seek request.
grep -q 'text="25:00"' screenshots/seek-up.xml
grep -q 'text="-1:25:24"' screenshots/seek-up.xml
adb logcat -d -s BMSeek:I > screenshots/seek-command.txt
grep -Eq 'release fraction=0\.[6-9][0-9]* elapsed=[4-6][0-9]{3} remaining=' screenshots/seek-command.txt
grep -Eq 'seekTo seconds=[4-6][0-9]{3}' screenshots/seek-command.txt
# Small phone, same aspect ratio as the reference (~1.94): everything should
# shrink to one uniform scale (296/356 ~ 0.83), gaps included.
adb shell wm size 740x1438
adb shell wm density 400
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main >/dev/null
sleep 8
dismiss_anr
adb exec-out screencap -p > screenshots/main-small.png
# Taller phone, same width: nothing scales, the equal gaps just grow.
adb shell wm size 1440x3400
adb shell wm density 649
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main >/dev/null
sleep 8
dismiss_anr
adb exec-out screencap -p > screenshots/main-tall.png
adb shell wm size reset
adb shell wm density reset
adb logcat -d | grep BMLayout > screenshots/layout-metrics.txt || true
adb logcat -d -s AndroidRuntime:E > screenshots/runtime-errors.txt
if grep -q 'FATAL EXCEPTION' screenshots/runtime-errors.txt; then cat screenshots/runtime-errors.txt; exit 1; fi
