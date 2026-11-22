#!/usr/bin/env bash
# Staged android test orchestration, one bash process.
#
# reactivecircus/android-emulator-runner@v2 executes EACH LINE of its script as
# a separate /usr/bin/sh -c invocation (run 45018522227 job log shows
# "[command]/usr/bin/sh -c echo ..." per line): no variable, no set +e and no
# multi-line construct survives across lines, and a pkill -f pattern in a
# wrapper line matches the wrapper's own command line and kills it
# ("exit code null"). The staging logic therefore lives in this file and the
# workflow invokes it as a single line.
set +e
API="$1"

adb logcat -c || true
(adb logcat -v threadtime > /tmp/device-logcat.txt 2>&1 &)

./gradlew --no-daemon installDebug installDebugAndroidTest
install_status=$?
echo "STAGE install: exit=$install_status"

# PopupPermissionTest is isolated: BarActionsTest grants POST_NOTIFICATIONS for
# its own needs, so the popup's denied state must be set up AFTER the main
# suite, from outside the app process - a revoke while the app runs SIGKILLs
# it, and instrumented tests run inside that process (one run aborted that way:
# logcat Zygote signal 9, 1.5s after the test started).
# InjectionPathProbeTest is isolated too: RED by design on api31 (documented
# unresolved injection-path anomaly), and must not be conflated with product
# suite status.
./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.bingemote.PopupPermissionTest,com.bingemote.InjectionPathProbeTest
status=$?
echo "STAGE product-suite: exit=$status"

adb shell am force-stop com.bingemote; s1=$?
adb shell pm revoke com.bingemote android.permission.POST_NOTIFICATIONS; s2=$?
adb shell pm clear-permission-flags com.bingemote android.permission.POST_NOTIFICATIONS user-set user-fixed; s3=$?
echo "STAGE popup-setup: force-stop=$s1 revoke=$s2 clear-flags=$s3"
# Denied-state evidence in the job log itself, independent of the test's own
# logcat line.
adb shell dumpsys package com.bingemote | grep -i 'POST_NOTIFICATIONS' | head -3

./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bingemote.PopupPermissionTest
status2=$?
echo "STAGE popup: exit=$status2"
# Which branch the popup test took (denied vs suppression) is load-bearing for
# the coverage claim; the printed logcat tail may not reach back this far.
grep -a 'popup-test' /tmp/device-logcat.txt || true

./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bingemote.InjectionPathProbeTest
status3=$?
echo "STAGE probe: exit=$status3 (api$API; RED is the documented expected state on api31 only)"

pkill -f 'adb logcat -v threadtime' || true

# Decisive UI evidence for selector failures: the popup test dumps
# screenshots/hierarchies into the app's external files dir. Pull them while
# the emulator is still alive; the upload step runs on the host.
timeout 60 adb pull /sdcard/Android/data/com.bingemote/files/ /tmp/bm-diag/ || true

fail=0
if [ $install_status -ne 0 ]; then echo "FAIL: install stage"; fail=1; fi
if [ $status -ne 0 ]; then echo "FAIL: product suite"; fail=1; fi
if [ $status2 -ne 0 ]; then echo "FAIL: popup stage"; fail=1; fi
if [ $s1 -ne 0 ] || [ $s2 -ne 0 ] || [ $s3 -ne 0 ]; then
  echo "FAIL: popup permission setup (force-stop=$s1 revoke=$s2 clear-flags=$s3)"; fail=1
fi
if [ $status3 -ne 0 ]; then
  if [ "$API" = "31" ]; then
    echo "PROBE: red on api31 as documented (not a job failure)"
  else
    echo "FAIL: probe red on api$API"; fail=1
  fi
fi
exit $fail
