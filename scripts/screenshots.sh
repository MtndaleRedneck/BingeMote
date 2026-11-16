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
# Emulate the primary tester's measured phone profile (1080x2310 @ 480dpi =
# 360dp wide, font scale 1.00) so the shots match what he sees. Restored at the end.
adb shell settings put global hide_error_dialogs 1
adb shell wm size 1080x2310
adb shell wm density 480
# Cold start after a density/font change can capture a black frame: warm up first,
# and retry any capture that comes back near-black.
black() { [ "$(stat -c%s "$1")" -lt 30000 ]; }
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
adb shell input swipe 540 1553 540 518 400
sleep 2
adb exec-out screencap -p > "screenshots/settings-feel.png"
# One more screenful down: the cards below Feel (lock-screen toggle lives in Feel now).
adb shell input swipe 540 1553 540 518 400
sleep 2
adb exec-out screencap -p > "screenshots/settings-after-feel.png"
# Resume/Start-over dialog (PC7) and the deferred-VLC-setup line (PC5).
shoot "movies-resume" ""
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen setup --es vlcpending true >/dev/null
sleep 8
dismiss_anr
# The deferred-VLC-setup line sits below the Done button: scroll it into view.
adb shell input swipe 540 1900 540 700 400
sleep 2
adb exec-out screencap -p > "screenshots/setup-vlcpending.png"

# Compact-row proof: permission denied first, then allowed, no media session.
adb shell am force-stop com.bingemote
adb shell pm revoke com.bingemote android.permission.POST_NOTIFICATIONS
adb shell am start -n com.bingemote/.MainActivity --es screen main --es lockcontrols true
sleep 4
adb shell dumpsys notification --noredact > screenshots/permission-denied-dump.txt
if grep -q '|com.bingemote|101|' screenshots/permission-denied-dump.txt; then
  echo 'ASSERT FAIL: row enqueued with notification permission denied'; exit 1
fi
echo 'ok: denied permission does not post the row'
adb shell am force-stop com.bingemote
adb shell pm grant com.bingemote android.permission.POST_NOTIFICATIONS
adb shell am start -n com.bingemote/.MainActivity --es screen main --es lockcontrols true
sleep 5
adb shell cmd statusbar expand-notifications
sleep 3
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml > screenshots/compact-row-ui.xml
adb exec-out screencap -p > screenshots/compact-row.png
for label in 'Back 10 seconds' 'Volume down' 'Pause' 'Volume up' 'Forward 10 seconds'; do
  grep -q "$label" screenshots/compact-row-ui.xml || { echo "ASSERT FAIL: missing visible $label"; exit 1; }
done
adb shell dumpsys notification --noredact > screenshots/notification-dump.txt
adb shell dumpsys media_session > screenshots/media-session.txt
adb shell dumpsys package com.bingemote > screenshots/package-perms.txt
grep -q '|com.bingemote|101|' screenshots/notification-dump.txt || { echo 'ASSERT FAIL: no custom notification'; exit 1; }
if grep -q 'package=com.bingemote' screenshots/media-session.txt; then
  echo 'ASSERT FAIL: unexpected BingeMote media session'; exit 1
fi
echo 'ok: five visible controls, custom notification posted, no BingeMote media session'
adb shell cmd statusbar collapse
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --es lockcontrols true --ei skipmain 5
sleep 4
adb shell cmd statusbar expand-notifications
sleep 2
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml > screenshots/compact-row-skip5-ui.xml
grep -q 'Back 5 seconds' screenshots/compact-row-skip5-ui.xml && grep -q 'Forward 5 seconds' screenshots/compact-row-skip5-ui.xml || { echo 'ASSERT FAIL: row ignores skip setting'; exit 1; }
adb exec-out screencap -p > screenshots/compact-row-skip5.png
adb shell cmd statusbar collapse
# Screen off/on persistence capture. This is not a Samsung/One UI test.
adb shell input keyevent 26
sleep 4
adb shell input keyevent 224
sleep 3
adb shell cmd statusbar expand-notifications
sleep 2
adb exec-out screencap -p > screenshots/compact-row-after-screen-off.png
adb shell cmd statusbar collapse
# End UNLOCKED: every phase after this launches the app and must see it.
adb shell input keyevent 4
sleep 1
adb shell input swipe 540 1900 540 600 400
sleep 2
adb shell input keyevent 3
sleep 2
# Seek drag reproduction. Demo status seeds 25:00 elapsed / -1:25:24 remaining
# (length 1:50:24). Raw pixels from the 1080x2310 emulator screenshot
# (seek track x=33..1033, center y=675). Evidence first, assertions last: every
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
adb shell input swipe 300 675 300 675 1100
sleep 0.3
dump_texts seek-unlocked
grep -q 'Unlocked' screenshots/seek-unlocked.xml || { echo 'ASSERT FAIL: hold did not unlock the seek bar'; SEEK_FAIL=1; }
# 2) Separate 8s drag while the ~5s unlock persists, wandering >70px off the bar.
#    Two input commands cannot be one continuous hold-then-drag, so this verifies
#    retained unlock + absolute-x tracking, not the literal single-gesture path.
#    Mid captures land ~2s in, safely before release: preview counters must be live.
adb shell input swipe 300 675 851 562 8000 &
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
# 4b) Mouse cadence diagnosis (jerky-mouse report): steady synthetic drags against a
#     fake helper on localhost; emitted frames' step sizes and inter-arrival gaps
#     land in logcat under BMMouse. Assertions are minimal by design - the numbers
#     are the deliverable, not a gate on the cadence itself.
adb logcat -c
adb shell am instrument -w -e class com.bingemote.MouseCadenceTest \
  com.bingemote.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee screenshots/mouse-cadence-instrument.txt
adb logcat -d -s BMMouse:I > screenshots/mouse-cadence.txt
cat screenshots/mouse-cadence.txt
grep -q '^OK (1 test)' screenshots/mouse-cadence-instrument.txt || { echo 'ASSERT FAIL: mouse cadence test failed (see mouse-cadence-instrument.txt)'; SEEK_FAIL=1; }
# End-to-end notification buttons (Ezzy: "buttons do nothing" on 1.12.2): a stub VLC
# on localhost must receive every tap's command, the card must follow stub state, and
# poll failures must be loud in BMBar.
adb logcat -c
adb shell am instrument -w -e class 'com.bingemote.BarActionsTest#buttonsReachStubPcAndCardFollowsState' \
  com.bingemote.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee screenshots/bar-actions-instrument.txt
adb logcat -d -s BMBar:V > screenshots/bar-actions-log.txt
cat screenshots/bar-actions-log.txt
for f in bar-actions-failure-ui.xml bar-actions-failure.png; do
  adb pull "/sdcard/$f" "screenshots/$f" 2>&1 || echo "No failure capture: $f"
done
grep -q '^OK (1 test)' screenshots/bar-actions-instrument.txt || { echo 'ASSERT FAIL: notification action end-to-end test failed (see bar-actions-instrument.txt + bar-actions-log.txt)'; SEEK_FAIL=1; }
adb logcat -c
adb shell am instrument -w -e class 'com.bingemote.BarActionsTest#pollFailuresAreLoud' \
  com.bingemote.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee screenshots/bar-failures-instrument.txt
adb logcat -d -s BMBar:V > screenshots/bar-failures-log.txt
cat screenshots/bar-failures-log.txt
grep -q '^OK (1 test)' screenshots/bar-failures-instrument.txt || { echo 'ASSERT FAIL: poll-failure loudness test failed (see bar-failures-instrument.txt + bar-failures-log.txt)'; SEEK_FAIL=1; }
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
# 6) Status-notice proposal renders (notification-simplification design review):
#    current vs proposed captures at Ezzy's exact phone profile (1080x2310 @ 480).
#    "cur-*" states are forced through the REAL UI; "prop-*" use DEBUG-only mock
#    state (the proposed hero note line) - illustrative, not shipped UI.
adb shell wm size 1080x2310
adb shell wm density 480
for case in cur-g prop-g cur-e prop-e prop-unpaired prod-e prod-unpaired; do
  adb shell am force-stop com.bingemote
  adb shell am start -n com.bingemote/.MainActivity --es screen main --es statuscase "$case" >/dev/null
  sleep 6
  dismiss_anr
  adb exec-out screencap -p > "screenshots/status-$case.png" 2>/dev/null || echo "PULL DIAG: status-$case"
  sz=$(stat -c%s "screenshots/status-$case.png" 2>/dev/null || echo 0)
  if [ "$sz" -gt 20 ] && file -b "screenshots/status-$case.png" | grep -q 'PNG image'; then
    echo "PULLED: status-$case.png ($sz bytes)"
  else
    echo "PULL DIAG: failed/invalid: status-$case.png ($sz bytes)"
  fi
done
adb shell wm size reset
adb shell wm density reset
[ "$SEEK_FAIL" -eq 0 ] || exit 1
# Small phone, same aspect ratio as the reference (~2.14): everything should
# shrink to one uniform scale (296/360 ~ 0.82), gaps included.
adb shell wm size 740x1583
adb shell wm density 400
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main >/dev/null
sleep 8
dismiss_anr
adb exec-out screencap -p > screenshots/main-small.png
# Taller phone, same width: nothing scales, the equal gaps just grow.
adb shell wm size 1080x2800
adb shell wm density 480
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main >/dev/null
sleep 8
dismiss_anr
adb exec-out screencap -p > screenshots/main-tall.png

# --- Prototype review captures (verify branches only): 420dpi settings redesign,
# pressed states, and a short press recording. 420dpi per the design owner's ask. ---
adb shell wm size 1080x2310
adb shell wm density 420
adb shell settings put system font_scale 1.0
shoot "settings" "-420"
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen settings --es pcdemo true >/dev/null
sleep 8
dismiss_anr
adb exec-out screencap -p > "screenshots/settings-top-420.png"
adb shell input swipe 540 1553 540 518 400
sleep 2
adb exec-out screencap -p > "screenshots/settings-power-420.png"
adb shell input swipe 540 1553 540 518 400
sleep 2
adb exec-out screencap -p > "screenshots/settings-feel-420.png"
adb shell input swipe 540 1553 540 700 400
sleep 2
adb exec-out screencap -p > "screenshots/settings-feel2-420.png"

center_of() { # $1 text or content-desc, prints "X Y" center of the first match
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  local B
  B=$(adb shell cat /sdcard/ui.xml | tr '<' '\n' | grep "$1" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 | grep -o '[0-9]*' | tr '\n' ' ')
  [ -z "$B" ] && return 1
  echo "$B" | awk '{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'
}

# Pressed-state stills on the main screen (demo status so Play is live).
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --ez demostatus true >/dev/null
sleep 8
dismiss_anr
PLAY=$(center_of '"Play"') || PLAY=$(center_of '"Pause"')
GEAR=$(center_of 'content-desc="Settings"')
echo "play=$PLAY gear=$GEAR" > screenshots/proto-coords.txt
if [ -n "${PLAY:-}" ]; then
  adb shell input swipe $PLAY $PLAY 9000 &
  HOLD=$!
  for i in 1 2 3 4; do
    sleep 1.6
    adb exec-out screencap -p > screenshots/play-pressed-420-$i.png
  done
  wait $HOLD || true
fi
if [ -n "${GEAR:-}" ]; then
  adb shell input swipe $GEAR $GEAR 5000 &
  HOLD=$!
  for i in 1 2; do
    sleep 1.6
    adb exec-out screencap -p > screenshots/gear-pressed-420-$i.png
  done
  wait $HOLD || true
fi

# Short recording: play sink, gear sink into settings, both toggles flipped.
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --ez demostatus true --es pcdemo true >/dev/null
sleep 8
dismiss_anr
PLAY=$(center_of '"Play"') || PLAY=$(center_of '"Pause"')
GEAR=$(center_of 'content-desc="Settings"')
adb shell screenrecord --time-limit 60 /sdcard/proto.mp4 &
REC=$!
sleep 2
[ -n "${PLAY:-}" ] && adb shell input swipe $PLAY $PLAY 1200
sleep 2
[ -n "${GEAR:-}" ] && adb shell input tap $GEAR
sleep 3
flip() { # $1 label: tap its row twice (off, back on) if it is on screen
  local C
  C=$(center_of ""$1"") || return 0
  [ -z "$C" ] && return 0
  adb shell input tap $C
  sleep 2
  adb shell input tap $C
  sleep 1
}
flip "Vibrate on press"
flip "Controls on lock screen"
flip "Show volume on PC screen"
sleep 1
adb shell input swipe 540 1553 540 900 400
sleep 2
wait $REC || true
adb pull /sdcard/proto.mp4 screenshots/proto-press.mp4 >/dev/null 2>&1 || true
adb shell rm /sdcard/proto.mp4 || true

adb shell wm size reset
adb shell wm density reset
adb logcat -d | grep BMLayout > screenshots/layout-metrics.txt || true
adb logcat -d -s AndroidRuntime:E > screenshots/runtime-errors.txt
if grep -q 'FATAL EXCEPTION' screenshots/runtime-errors.txt; then cat screenshots/runtime-errors.txt; exit 1; fi
