set -euo pipefail
mkdir -p focused-ui
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Clear only our own FailureDumpRule/test-failure outputs (any extension) so anything
# pulled later belongs to THIS run; dump file names carry the test method
# (FailureDumpRule tags with description.methodName), giving per-test attribution.
if ! adb shell "rm -f /sdcard/Android/data/com.bingemote/files/bm-dump* /sdcard/Android/data/com.bingemote/files/bm-andialog* /sdcard/Android/data/com.bingemote/files/bm-dismissed-dialogs.txt /sdcard/Android/data/com.bingemote/files/bm-setup-selected-pc-fail.* /sdcard/Android/data/com.bingemote/files/bm-setup-confirmed-address-fail.*" 2>/dev/null; then
  echo "CLEAR DIAG: stale-evidence cleanup failed - this run's pulls may include files from an earlier run on this emulator"
fi

adb shell wm size 1080x2310
adb shell wm density 480
adb shell settings put system font_scale 1.0
adb shell settings put global hide_error_dialogs 1
adb shell am start -n com.bingemote/.MainActivity --es screen main --es statuscase cur-g
sleep 8
adb shell input keyevent 4
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --es statuscase cur-g
sleep 5
adb shell uiautomator dump /sdcard/focused-ui.xml
sleep 3
adb exec-out screencap -p > focused-ui/reconnect.png
adb exec-out cat /sdcard/focused-ui.xml > focused-ui/reconnect.xml
adb shell am instrument -w -r -e class com.bingemote.AutomaticOnlySetupTest,com.bingemote.AutoSetupCheckTest,com.bingemote.ImmediateFlipTest,com.bingemote.DisconnectedPlaybackTest,com.bingemote.HapticTickTest,com.bingemote.DisconnectedHomeUiTest,com.bingemote.SettingsScrollNavTest com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee focused-ui/tests.txt || echo "INSTRUMENT DIAG: adb/instrument pipeline failed (rc=$?) - partial output above; the grep assert below records the failure"
# Verified pull from the app's files dir (where FailureDumpRule and the setup tests
# write, via getExternalFilesDir): temp name, per-type signature check, keep-or-log.
# A failed or error-text pull is deleted and logged, never kept as a fake capture.
pull_device_file() { # $1=device file name under the app files dir, $2=local dir
  local name="$1" dir="$2" sz sig
  if ! adb exec-out cat "/sdcard/Android/data/com.bingemote/files/$name" > "$dir/$name.tmp" 2>/dev/null; then
    rm -f "$dir/$name.tmp"; echo "PULL DIAG: $name transport failed - not kept"; return 0
  fi
  sz=$(stat -c%s "$dir/$name.tmp" 2>/dev/null || echo 0)
  sig=$(file -b "$dir/$name.tmp" 2>/dev/null | cut -c1-60)
  # adb exec-out of a missing/unreadable device file yields a SMALL text stub whose
  # body is the error line; a real .uix/.txt dump (window hierarchy, logcat) is KBs
  # and can legitimately MENTION these phrases, so reject only the small-stub shape.
  is_adb_error_stub() { [ "$1" -lt 200 ] && grep -qm1 'No such file\|Permission denied' "$2"; }
  case "$name" in
    *.png) echo "$sig" | grep -q 'PNG image' || { rm -f "$dir/$name.tmp"; echo "PULL DIAG: $name not a PNG ($sz bytes, $sig) - not kept"; return 0; };;
    *.uix|*.xml) echo "$sig" | grep -Eq 'XML|text' && ! is_adb_error_stub "$sz" "$dir/$name.tmp" || { rm -f "$dir/$name.tmp"; echo "PULL DIAG: $name not XML ($sz bytes, $sig) - not kept"; return 0; };;
    *) [ "$sz" -gt 0 ] && ! is_adb_error_stub "$sz" "$dir/$name.tmp" || { rm -f "$dir/$name.tmp"; echo "PULL DIAG: $name empty/error-stub ($sz bytes) - not kept"; return 0; };;
  esac
  mv "$dir/$name.tmp" "$dir/$name"
  echo "PULLED: $name ($sz bytes, $sig)"
}

# Named test captures via the same verified pull: a missing/failed/error pull is
# logged and NOT kept (never an empty stub), and never aborts the run before the
# failure-evidence pull below; the instrument grep assert keeps the exit semantics.
for capture in setup-before-selection setup-selected-pc setup-selected-address setup-cancel-settings updates-success updates-expired disconnected-zero disconnected-reconnected vlcclosed-zero vlcrefused-zero settings-nav-kept settings-nav-top; do
  pull_device_file "bm-$capture.png" focused-ui
done
# FailureDumpRule evidence (screenshot + window hierarchy + logcat, plus the paired
# 4s-later recurrence capture) is written on ANY instrumented failure, and the setup
# tests write their own *-fail captures on reveal failures - none of it was ever
# pulled: runs 45547260244 and 45549797139 failed with zero failure pixels (the old
# lines also read /sdcard root while the writers use the files dir). Placement: after
# all instrument asserts, before the script's existing exit checks, so a failure is
# collected first and still propagates through the unchanged grep asserts.
if dump_listing=$(adb shell "ls /sdcard/Android/data/com.bingemote/files/ 2>/dev/null"); then :; else
  ls_rc=$?; echo "PULL DIAG: evidence dir listing failed (rc=$ls_rc) - cannot confirm which failure evidence exists this run"; dump_listing=""
fi
dumps=$(printf '%s\n' "$dump_listing" | tr -d '\r' | grep -E '^bm-(dump|andialog|dismissed-dialogs|setup-selected-pc-fail|setup-confirmed-address-fail)' || true)
if [ -z "$dumps" ]; then echo "PULL DIAG: no FailureDumpRule/test-failure evidence files listed (none written, or listing failed above)"; fi
for dump in $dumps; do pull_device_file "$dump" focused-ui; done

adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --es lockcontrols true
sleep 8
adb shell uiautomator dump /sdcard/status-icon.xml
sleep 2
adb exec-out screencap -p > focused-ui/status-icon-native.png
adb shell cmd statusbar expand-notifications
sleep 3
adb exec-out screencap -p > focused-ui/status-icon-shade.png
adb shell cmd statusbar collapse
# Strict summary: exactly the expected count, and no failure markers anywhere.
EXPECTED_TESTS=38
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_RESULT: shortMsg|Process crashed' focused-ui/tests.txt; then
  echo "FOCUSED-UI GATE FAIL: failure marker in instrumentation output"; exit 1
fi
if ! tr -d "\r" < focused-ui/tests.txt | grep -qx "OK ($EXPECTED_TESTS tests)"; then
  echo "FOCUSED-UI GATE FAIL: missing summary or test count != $EXPECTED_TESTS"; grep -E '^OK \(|Tests run' focused-ui/tests.txt || true; exit 1
fi
echo "FOCUSED-UI GATE PASS: OK ($EXPECTED_TESTS tests)"
