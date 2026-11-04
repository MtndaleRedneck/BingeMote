#!/bin/bash
# Android validation for the 1.7.2 candidate: the wake-gate and settings read-back
# instrumentation tests against in-test loopback mocks (controlled helper/VLC).
set -e
mkdir -p validation
adb shell settings put global hide_error_dialogs 1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -e class com.bingemote.WakeGateTest,com.bingemote.SettingsReadbackTest \
  com.bingemote.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee validation/wakegate-readback-instrument.txt
adb logcat -d > validation/wakegate-readback-logcat.txt
grep -q '^OK (3 tests)' validation/wakegate-readback-instrument.txt \
  || { echo 'ASSERT FAIL: wake-gate/read-back tests failed (see wakegate-readback-instrument.txt)'; exit 1; }
echo 'VALIDATION-ANDROID-PASS wake gate + settings read-back'
