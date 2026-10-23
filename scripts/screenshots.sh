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
# Seek drag reproduction. Demo status seeds 25:00 elapsed / -1:25:24 remaining
# (length 1:50:24). Raw pixels from the 1440x2807 emulator screenshot
# (seek track x=44..1396, center y=913). Evidence first, assertions last: every
# dump's text fields and the BMSeek log are echoed to the CI log and uploaded
# even when a check fails, so a failure stays diagnosable.
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --ez seektop true --ez demostatus true
sleep 6
dismiss_anr
dump_texts() { # $1 basename: save the UI dump and echo its text fields to the CI log
  adb shell uiautomator dump "/sdcard/$1.xml" >/dev/null
  adb shell cat "/sdcard/$1.xml" > "screenshots/$1.xml"
  echo "--- $1 texts ---"
  grep -o 'text="[^"]*"' "screenshots/$1.xml" | sort -u | tee "screenshots/$1-text.txt" || true
}
SEEK_FAIL=0
# 1) Stationary hold past the 850ms unlock timer (a same-point swipe is a long press).
adb shell input swipe 400 913 400 913 1100
sleep 0.3
dump_texts seek-unlocked
grep -q 'Unlocked' screenshots/seek-unlocked.xml || { echo 'ASSERT FAIL: hold did not unlock the seek bar'; SEEK_FAIL=1; }
# 2) Separate 8s drag while the ~5s unlock persists, wandering >70px off the bar.
#    Two input commands cannot be one continuous hold-then-drag, so this verifies
#    retained unlock + absolute-x tracking, not the literal single-gesture path.
#    Mid captures land ~2s in, safely before release: preview counters must be live.
adb shell input swipe 400 913 1150 760 8000 &
SWIPE_PID=$!
sleep 2
adb exec-out screencap -p > screenshots/main-seekdrag.png
dump_texts seek-mid
wait "$SWIPE_PID"
sleep 0.5
adb exec-out screencap -p > screenshots/main-seekdrag-up.png
dump_texts seek-up
adb logcat -d -s BMSeek:I > screenshots/seek-command.txt
echo '--- BMSeek ---'
cat screenshots/seek-command.txt
# Assertions, after all evidence exists.
grep -q 'Unlocked' screenshots/seek-mid.xml || echo 'NOTE: unlocked hint absent in mid-drag dump'
if grep -q 'text="25:00"' screenshots/seek-mid.xml; then echo 'ASSERT FAIL: drag elapsed preview stayed at seed time'; SEEK_FAIL=1; fi
if grep -q 'text="-1:25:24"' screenshots/seek-mid.xml; then echo 'ASSERT FAIL: drag remaining preview stayed at seed time'; SEEK_FAIL=1; fi
grep -q 'text="25:00"' screenshots/seek-up.xml || { echo 'ASSERT FAIL: post-release elapsed did not return to the seed poll'; SEEK_FAIL=1; }
grep -q 'text="-1:25:24"' screenshots/seek-up.xml || { echo 'ASSERT FAIL: post-release remaining did not return to the seed poll'; SEEK_FAIL=1; }
grep -Eq 'seekTo seconds=[4-6][0-9]{3}' screenshots/seek-command.txt || { echo 'ASSERT FAIL: release issued no in-range seekTo'; SEEK_FAIL=1; }
# 3) Continuous hold-then-slide as ONE touch (the original complaint path):
#    down while locked, hold past the 850ms in-press unlock, slide, release -
#    no lift between hold and slide. `input swipe` cannot pause mid-gesture,
#    so the events are streamed on-device with sendevent (60 steps over 6s).
TOUCH_DEV=$(adb shell getevent -pl 2>/dev/null | tr -d '\r' | awk '/^add device/{d=$NF} /ABS_MT_POSITION_X/{print d; exit}')
if [ -z "$TOUCH_DEV" ]; then
  echo 'NOTE: no multitouch input device found; continuous hold-then-slide not exercised'
else
  echo "continuous hold-then-slide on $TOUCH_DEV"
  adb shell am force-stop com.bingemote
  adb shell am start -n com.bingemote/.MainActivity --es screen main --ez seektop true --ez demostatus true
  sleep 6
  dismiss_anr
  adb logcat -c
  SE="sendevent $TOUCH_DEV 3 57 0
sendevent $TOUCH_DEV 3 53 400
sendevent $TOUCH_DEV 3 54 913
sendevent $TOUCH_DEV 1 330 1
sendevent $TOUCH_DEV 0 0 0
sleep 1.2"
  for i in $(seq 1 60); do
    X=$((400 + (i * 750) / 60))
    Y=$((913 - (i * 150) / 60))
    SE="$SE
sendevent $TOUCH_DEV 3 53 $X
sendevent $TOUCH_DEV 3 54 $Y
sendevent $TOUCH_DEV 0 0 0
sleep 0.1"
  done
  SE="$SE
sendevent $TOUCH_DEV 3 57 4294967295
sendevent $TOUCH_DEV 1 330 0
sendevent $TOUCH_DEV 0 0 0"
  echo "$SE" | adb shell sh &
  SE_PID=$!
  sleep 3
  # ~1.2s hold + ~1.8s into the slide: mid-gesture, past the in-press unlock.
  adb exec-out screencap -p > screenshots/main-seekslide.png
  dump_texts seek-slide-mid
  wait "$SE_PID"
  sleep 0.5
  adb exec-out screencap -p > screenshots/main-seekslide-up.png
  dump_texts seek-slide-up
  adb logcat -d -s BMSeek:I > screenshots/seek-slide-command.txt
  echo '--- BMSeek (continuous) ---'
  cat screenshots/seek-slide-command.txt
  grep -q 'Unlocked' screenshots/seek-slide-mid.xml || { echo 'ASSERT FAIL: in-press hold did not unlock during the slide'; SEEK_FAIL=1; }
  if grep -q 'text="25:00"' screenshots/seek-slide-mid.xml; then echo 'ASSERT FAIL: continuous drag elapsed preview stayed at seed time'; SEEK_FAIL=1; fi
  if grep -q 'text="-1:25:24"' screenshots/seek-slide-mid.xml; then echo 'ASSERT FAIL: continuous drag remaining preview stayed at seed time'; SEEK_FAIL=1; fi
  grep -Eq 'seekTo seconds=[4-6][0-9]{3}' screenshots/seek-slide-command.txt || { echo 'ASSERT FAIL: continuous release issued no in-range seekTo'; SEEK_FAIL=1; }
fi
[ "$SEEK_FAIL" -eq 0 ] || exit 1
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
