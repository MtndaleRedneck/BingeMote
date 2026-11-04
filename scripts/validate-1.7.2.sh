#!/bin/bash
# Android validation for the 1.7.2 candidate: the wake-gate and settings read-back
# instrumentation tests against in-test loopback mocks (controlled helper/VLC).
# The read-back test needs a FRESH app process per leg (disk-seeded prefs; a reused
# process serves its in-memory SharedPreferences), so each leg is its own instrument
# run with a script-level force-stop between - never inside a test, the runner lives
# in the app's process.
set -e
mkdir -p validation
adb shell settings put global hide_error_dialogs 1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -e class com.bingemote.WakeGateTest \
  com.bingemote.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee validation/wakegate-instrument.txt
grep -q '^OK (2 tests)' validation/wakegate-instrument.txt \
  || { echo 'ASSERT FAIL: wake-gate tests failed (see wakegate-instrument.txt)'; exit 1; }

run_readback() {  # $1 = method, $2 = output file
  adb shell am force-stop com.bingemote
  adb shell am instrument -w -e class "com.bingemote.SettingsReadbackTest#$1" \
    com.bingemote.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "validation/$2"
  grep -q '^OK (1 test)' "validation/$2" \
    || { echo "ASSERT FAIL: read-back leg $1 failed (see validation/$2)"; exit 1; }
}
run_readback helperStateWinsOverPhoneLocal readback-helperwins-instrument.txt
run_readback absentKeysKeepPhoneLocal readback-absentkeys-instrument.txt

adb logcat -d > validation/wakegate-readback-logcat.txt
echo 'VALIDATION-ANDROID-PASS wake gate + settings read-back (3 runs, 4 tests)'
