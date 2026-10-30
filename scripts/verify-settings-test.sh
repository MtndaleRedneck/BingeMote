#!/usr/bin/env bash
set -u
mkdir -p verify
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.bingemote.BingeSettingsVisualTest com.bingemote.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee verify/settings-test-instrument.txt
if grep -q '^OK (1 test)' verify/settings-test-instrument.txt; then echo "RESULT: PASS" > verify/settings-test-result.txt; else echo "RESULT: FAIL" > verify/settings-test-result.txt; fi
adb shell dumpsys package com.bingemote | grep versionName >> verify/settings-test-result.txt
adb exec-out run-as com.bingemote true >/dev/null 2>&1
adb pull /sdcard/Android/data/com.bingemote/files/binge-settings-labels.png verify/ >/dev/null 2>&1
cat verify/settings-test-result.txt
[ "$(head -1 verify/settings-test-result.txt)" = "RESULT: PASS" ]
