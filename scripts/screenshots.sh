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
# Scrolled Settings: the Feel section (incl. "Cancel button style") sits below
# the fold, so capture it explicitly for review.
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen settings >/dev/null
sleep 8
dismiss_anr
adb shell input swipe 720 2100 720 700 400
sleep 2
adb exec-out screencap -p > "screenshots/settings-feel.png"
# Seek drag reproduction. Demo status seeds 25:00 elapsed / -1:25:24 remaining
# (length 1:50:24). Raw pixels from the 1440x2807 emulator screenshot
# (seek track x=44..1396, center y=913). Evidence first, assertions last: every
# dump's text fields and the BMSeek log are echoed to the CI log and uploaded
# even when a check fails, so a failure stays diagnosable.
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --ez demostatus true
sleep 6
dismiss_anr
dump_texts() { # $1 basename: save the UI dump and echo its text fields to the CI log
  adb shell uiautomator dump "/sdcard/$1.xml" >/dev/null
  adb shell cat "/sdcard/$1.xml" > "screenshots/$1.xml"
  echo "--- $1 texts ---"
  grep -o 'text="[^"]*"' "screenshots/$1.xml" | sort -u | tee "screenshots/$1-text.txt" || true
}
SEEK_FAIL=0
# 1) Stationary hold past the 500ms unlock timer (a same-point swipe is a long press).
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

# Counter bounds ride the logcat (UiAutomation executeShellCommand does not
# interpret shell redirection on this emulator - run 39960953492). Extract the
# last logged fwd/back pair for a tag into a bounds file.
extract_bounds() { # $1 logcat file, $2 tag, $3 output file
  : > "$3"
  grep -oE "bounds $2 fwd [0-9]+ [0-9]+ [0-9]+ [0-9]+" "$1" | tail -1 | sed -E "s/^bounds $2 //" >> "$3" || true
  grep -oE "bounds $2 back [0-9]+ [0-9]+ [0-9]+ [0-9]+" "$1" | tail -1 | sed -E "s/^bounds $2 //" >> "$3" || true
}
bounds_ok() { grep -q '^fwd ' "$1" && grep -q '^back ' "$1"; }

# 3) Continuous hold-then-slide as ONE touch (the original complaint path):
#    down while locked, the 850ms unlock firing mid-press, then sliding without
#    lifting. `input swipe` cannot pause mid-gesture and the shell user may not
#    write /dev/input directly, so a UiAutomator instrumentation test performs
#    the gesture and asserts the live preview DURING it; the seekTo log check
#    happens here after the run.
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -e class com.bingemote.SeekGestureTest \
  com.bingemote.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee screenshots/seek-slide-instrument.txt
# Keep the full logcat whatever happened: a crashed test process leaves its stack here.
adb logcat -d > screenshots/seek-slide-logcat.txt
grep -q '^OK (1 test)' screenshots/seek-slide-instrument.txt || { echo 'ASSERT FAIL: continuous hold-then-slide test failed (see seek-slide-instrument.txt)'; SEEK_FAIL=1; }
# Evidence the test captured inside the instrument (mid-slide, release, post-release),
# written to the target app's internal files dir and retrieved via run-as (debug build
# is debuggable). Each extraction is verified by size and file signature BEFORE the
# pixels are interpreted; a bad pull is a loud rig defect, never a silent NOTE.
extract_bounds screenshots/seek-slide-logcat.txt seek-slide screenshots/seek-slide-bounds.txt
for f in seek-slide-mid.png seek-slide-release.png seek-slide-post.png \
         seek-slide-pixels-before.png seek-slide-pixels-after.png; do
  adb exec-out cat "/sdcard/bm-$f" > "screenshots/$f" 2>/dev/null || echo "PULL DIAG: exec-out failed: $f"
  sz=$(stat -c%s "screenshots/$f" 2>/dev/null || echo 0)
  sig=$(file -b "screenshots/$f" 2>/dev/null | cut -c1-60)
  if [ "$sz" -gt 20 ] && echo "$sig" | grep -Eq 'PNG image|text'; then
    echo "PULLED: $f ($sz bytes, $sig)"
  else
    echo "PULL DIAG: failed/invalid: $f ($sz bytes, $sig)"
  fi
done
# Post-release seed-return: the counter regions must pixel-match the baseline.
if file -b screenshots/seek-slide-pixels-before.png 2>/dev/null | grep -q 'PNG image' \
   && file -b screenshots/seek-slide-pixels-after.png 2>/dev/null | grep -q 'PNG image' \
   && bounds_ok screenshots/seek-slide-bounds.txt; then
  python3 scripts/pixel_region_compare.py \
    screenshots/seek-slide-pixels-before.png \
    screenshots/seek-slide-pixels-after.png \
    screenshots/seek-slide-bounds.txt 2>&1 | tee screenshots/pixel-compare-seek-slide.txt \
    || { echo 'ASSERT FAIL: post-release counters did not pixel-match the seed'; SEEK_FAIL=1; }
else
  echo 'ASSERT FAIL: missing/invalid seek-slide pixel-compare inputs'; SEEK_FAIL=1
fi
adb exec-out screencap -p > screenshots/main-seekslide-up.png
adb logcat -d -s BMSeek:I > screenshots/seek-slide-command.txt
echo '--- BMSeek (continuous) ---'
cat screenshots/seek-slide-command.txt
grep -Eq 'seekTo seconds=[4-6][0-9]{3}' screenshots/seek-slide-command.txt || { echo 'ASSERT FAIL: continuous release issued no in-range seekTo'; SEEK_FAIL=1; }
# 4) The 1.9 cancel circle: release inside keeps the place (no seekTo/toggle),
#    counters live inside the circle, an interrupted drag does nothing; plus the
#    settings migration checks. Each test clears logcat first, so this runs AFTER
#    the seek-slide log checks above.
adb logcat -c
adb shell am instrument -w -e class com.bingemote.CancelGestureTest,com.bingemote.StoreMigrationTest \
  com.bingemote.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee screenshots/cancel-instrument.txt
adb logcat -d > screenshots/cancel-logcat.txt
grep -q '^OK (6 tests)' screenshots/cancel-instrument.txt || { echo 'ASSERT FAIL: cancel-circle/migration tests failed (see cancel-instrument.txt)'; SEEK_FAIL=1; }
for tag in ring fills interrupted; do
  extract_bounds screenshots/cancel-logcat.txt "$tag" "screenshots/cancel-bounds-$tag.txt"
done
for f in cancel-inside-ring.png cancel-after-ring.png \
         cancel-inside-fills.png cancel-after-fills.png \
         cancel-interrupted.png \
         cancel-pixels-ring-before.png cancel-pixels-ring-after.png \
         cancel-pixels-fills-before.png cancel-pixels-fills-after.png \
         cancel-pixels-interrupted-before.png cancel-pixels-interrupted-after.png; do
  adb exec-out cat "/sdcard/bm-$f" > "screenshots/$f" 2>/dev/null || echo "PULL DIAG: exec-out failed: $f"
  sz=$(stat -c%s "screenshots/$f" 2>/dev/null || echo 0)
  sig=$(file -b "screenshots/$f" 2>/dev/null | cut -c1-60)
  if [ "$sz" -gt 20 ] && echo "$sig" | grep -Eq 'PNG image|text'; then
    echo "PULLED: $f ($sz bytes, $sig)"
  else
    echo "PULL DIAG: failed/invalid: $f ($sz bytes, $sig)"
  fi
done
# Counter-region pixel proof: the seed counters after cancel/interruption must
# match the pre-gesture baseline exactly. This is the tree-independent check -
# a stale accessibility tree cannot fake pixels.
for tag in ring fills interrupted; do
  if file -b "screenshots/cancel-pixels-$tag-before.png" 2>/dev/null | grep -q 'PNG image' \
     && file -b "screenshots/cancel-pixels-$tag-after.png" 2>/dev/null | grep -q 'PNG image' \
     && bounds_ok "screenshots/cancel-bounds-$tag.txt"; then
    python3 scripts/pixel_region_compare.py \
      "screenshots/cancel-pixels-$tag-before.png" \
      "screenshots/cancel-pixels-$tag-after.png" \
      "screenshots/cancel-bounds-$tag.txt" 2>&1 | tee "screenshots/pixel-compare-$tag.txt" \
      || { echo "ASSERT FAIL: counters did not pixel-match the seed ($tag)"; SEEK_FAIL=1; }
  else
    echo "PULL DIAG: missing pixel-compare inputs for $tag"; SEEK_FAIL=1
  fi
done
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
