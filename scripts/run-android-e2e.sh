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

# The workflow's 'Name the APK' step expects a release APK; the original workflow
# built it (assembleRelease) and the staging work dropped that leg, so the first
# run whose tests all passed failed on the missing APK (run 45021496277).
./gradlew --no-daemon assembleRelease installDebug installDebugAndroidTest
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

# Returning-user leg: asked=yes routes the denied-state popup's destination to
# the app notification settings instead of the permission controller. Same
denied-state setup from outside the process first.
adb shell am force-stop com.bingemote; s4=$?
adb shell pm revoke com.bingemote android.permission.POST_NOTIFICATIONS; s5=$?
adb shell pm clear-permission-flags com.bingemote android.permission.POST_NOTIFICATIONS user-set user-fixed; s6=$?
echo "STAGE popup-again-setup: force-stop=$s4 revoke=$s5 clear-flags=$s6"
./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bingemote.PopupPermissionTest -Pandroid.testInstrumentationRunnerArguments.again=yes
status4=$?
echo "STAGE popup-again: exit=$status4"
# Which branch the popup test took (denied vs suppression) is load-bearing for
# the coverage claim; the printed logcat tail may not reach back this far.
grep -a 'popup-test' /tmp/device-logcat.txt || true

# The probe output is kept: the api31 tolerance below must match the ONE
# documented failure (the zero-drag assertion alone), not any probe-leg red.
./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bingemote.InjectionPathProbeTest 2>&1 | tee /tmp/probe-leg.txt
status3=${PIPESTATUS[0]}
echo "STAGE probe: exit=$status3 (api$API; RED is the documented expected state on api31 only)"

pkill -f 'adb logcat -v threadtime' || true

# Decisive UI evidence for selector failures: the popup test dumps
# screenshots/hierarchies into the app's external files dir, then stashes them
# to public Download (@After) because AGP uninstalls the app at each leg's end
# and wipes that dir. Pull while the emulator is alive; the upload runs on the host.
timeout 60 adb pull /sdcard/Download/bm-diag/ /tmp/bm-diag/ || true

fail=0
if [ $install_status -ne 0 ]; then echo "FAIL: install stage"; fail=1; fi
if [ $status -ne 0 ]; then echo "FAIL: product suite"; fail=1; fi
if [ $status2 -ne 0 ]; then echo "FAIL: popup stage"; fail=1; fi
if [ $status4 -ne 0 ]; then echo "FAIL: popup-again stage"; fail=1; fi
if [ $s4 -ne 0 ]; then echo "FAIL: popup-again setup (force-stop=$s4)"; fail=1; fi
if [ "$API" -ge 33 ] && { [ $s5 -ne 0 ] || [ $s6 -ne 0 ]; }; then
  echo "FAIL: popup-again permission setup (revoke=$s5 clear-flags=$s6)"; fail=1
fi
# POST_NOTIFICATIONS exists only on sdk>=33: below that, revoke is a no-op and
# clear-permission-flags exits 255 (run 45019910407, api31). Only sdk>=33 setup
# failures are real setup failures; on older APIs the popup test's suppression
# branch is the exercisable path by design.
if [ $s1 -ne 0 ]; then echo "FAIL: popup permission setup (force-stop=$s1)"; fail=1; fi
if [ "$API" -ge 33 ] && { [ $s2 -ne 0 ] || [ $s3 -ne 0 ]; }; then
  echo "FAIL: popup permission setup (revoke=$s2 clear-flags=$s3)"; fail=1
elif [ "$API" -lt 33 ]; then
  echo "SETUP note: api$API has no POST_NOTIFICATIONS runtime permission (revoke=$s2 clear-flags=$s3) - denied path unexercisable here"
fi
if [ $status3 -ne 0 ]; then
  known=0
  if [ "$API" = "31" ] \
     && grep -q 'injectInputEventStartsADrag' /tmp/probe-leg.txt \
     && grep -q 'injectInputEvent drag produced no app-side drag lines' /tmp/probe-leg.txt \
     && grep -q 'There was 1 failure' /tmp/probe-leg.txt; then
    known=1
  fi
  if [ $known -eq 1 ]; then
    echo "PROBE: red on api31 as documented (exactly the known zero-drag assertion; not a job failure)"
  else
    echo "FAIL: probe red on api$API - not the single documented api31 zero-drag assertion"; fail=1
  fi
fi
# Diagnostic retention is loud: a failing popup leg without surviving dumps is
# called out explicitly (the job is already red from the leg itself).
diag_count=$(ls /tmp/bm-diag/ 2>/dev/null | wc -l)
echo "DIAG: $diag_count file(s) pulled"
if { [ $status2 -ne 0 ] || [ $status4 -ne 0 ]; } && [ "$diag_count" -eq 0 ]; then
  echo "DIAG MISSING: a popup leg failed but no screenshots/hierarchies survived"
fi
exit $fail
