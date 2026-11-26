set -euo pipefail
mkdir -p parity-evidence
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size 1080x2310
adb shell wm density 480
adb shell settings put system font_scale 1.0
adb shell settings put global hide_error_dialogs 1
adb shell am instrument -w -r -e class com.bingemote.TopBarParityTest,com.bingemote.HeaderGeometryTest,com.bingemote.stream.StreamServerAndroidTest,com.bingemote.stream.StreamServiceRefusalAndroidTest,com.bingemote.stream.StreamServiceBackgroundBindAndroidTest,com.bingemote.stream.StreamServiceLifecycleTest,com.bingemote.stream.StreamServiceNoWifiTest,com.bingemote.stream.PhonePickerStorageTest,com.bingemote.stream.PhonePickerGrantTest,com.bingemote.stream.PhoneMoviesAndroidTest com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee parity-evidence/tests.txt || true
# F3 call-site test: its own instrument session (UiAutomator), same fail-closed rule as the main session.
adb shell am instrument -w -r -e class com.bingemote.MoviesStaleLoadTest com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee parity-evidence/tests-stale-load.txt || true
# FailureDumpRule evidence (failure screenshot, hierarchy, logcat, paired capture; launcher-ANR before/after captures),
# pulled right after the instrumentation sessions so a red run always carries the pixels. Evidence only, never changes the verdict.
EVDIR=/sdcard/Android/data/com.bingemote/files
mkdir -p parity-evidence/failure-dumps
if dump_listing=$(adb shell "ls $EVDIR/ 2>/dev/null"); then :; else echo "PULL DIAG: evidence dir listing failed (rc=$?)"; dump_listing=""; fi
dumps=$(printf '%s\n' "$dump_listing" | tr -d '\r' | grep -E '^bm-(dump|dump2|andialog|dismissed-dialogs)' || true)
if [ -z "$dumps" ]; then echo "PULL DIAG: no FailureDumpRule evidence files listed"; fi
for d in $dumps; do adb exec-out cat "$EVDIR/$d" > "parity-evidence/failure-dumps/$d" 2>/dev/null || echo "PULL DIAG: could not pull $d"; done
# Standalone-provider probe (evidence only, never changes the verdict): with no instrumentation running, stop the test
# package, then query its SAF provider through the content shell so the system starts a normal com.bingemote.test process
# that hosts TestDocumentsProvider. Records the query output, whether that process exists afterwards, and its AndroidRuntime
# lines. If the shell is refused (permission) the file says so and the provider launch is NOT proven by this run.
adb shell am force-stop com.bingemote.test || true
echo "pid before: $(adb shell pidof com.bingemote.test | tr -d '\r\n')" > parity-evidence/provider-probe.txt
adb shell content query --uri content://com.bingemote.test.documents/root >> parity-evidence/provider-probe.txt 2>&1 || echo "probe query rc=$?" >> parity-evidence/provider-probe.txt
sleep 3
echo "pid after: $(adb shell pidof com.bingemote.test | tr -d '\r\n')" >> parity-evidence/provider-probe.txt
adb logcat -d -s AndroidRuntime:E | grep -E "bingemote.test|TestDocumentsProvider|kotlin" | tail -5 >> parity-evidence/provider-probe.txt || true
for s in main movies settings movies-phone; do
  adb shell am force-stop com.bingemote
  adb shell am start -n com.bingemote/.MainActivity --es screen "$s" >/dev/null
  sleep 8
  adb exec-out screencap -p > "parity-evidence/$s.png"
done
# Reference for the unpaired-first-launch question: a fresh install (data cleared), no rig extras.
adb shell pm clear com.bingemote >/dev/null
adb shell am start -n com.bingemote/.MainActivity >/dev/null
sleep 8
adb exec-out screencap -p > "parity-evidence/fresh-install-first-launch.png"
for f in $(adb shell ls /sdcard/ | grep -E "^parity-(storage-picker|saf-grant)-" | tr -d "\r"); do adb pull /sdcard/$f parity-evidence/$f >/dev/null; done
adb logcat -d -s AndroidRuntime:E > parity-evidence/crashes.txt || true
adb logcat -d -s FgBindBackground:I ActivityManager:W AndroidRuntime:E > parity-evidence/fg-bind-background.txt || true
adb logcat -d -s HeaderGeom:I > parity-evidence/header-geometry.txt || true
for f in $(adb shell ls /sdcard/ | grep -E "^hg-" | tr -d "\r"); do adb pull /sdcard/$f parity-evidence/$f >/dev/null; done
# JUnit Assume skips are reported by the runner as status code -4 (-3 is never emitted for them).
# Record each skipped test by name; the OK line alone does not mean they ran.
awk '/INSTRUMENTATION_STATUS: class=/{c=$0; sub(/.*class=/,"",c)} /INSTRUMENTATION_STATUS: test=/{t=$0; sub(/.*test=/,"",t)} /INSTRUMENTATION_STATUS_CODE: -4/{print c "#" t}' parity-evidence/tests.txt | sort -u > parity-evidence/skipped-tests.txt
wc -l < parity-evidence/skipped-tests.txt | tr -d ' ' > parity-evidence/skipped-count.txt
echo "skipped (Assume) tests: $(cat parity-evidence/skipped-count.txt)"
cat parity-evidence/skipped-tests.txt
# No skip is accepted: the SAF grant and listing tests run the real picker on shared storage and cannot skip.
: > parity-evidence/expected-skips.txt
if comm -23 parity-evidence/skipped-tests.txt parity-evidence/expected-skips.txt | grep -q .; then
  echo "UNEXPECTED skipped tests:"; comm -23 parity-evidence/skipped-tests.txt parity-evidence/expected-skips.txt; exit 1
fi
# Named results: every required test must have a final status code 0 (passed) in the stream; a dropped class or a skip fails.
awk '/INSTRUMENTATION_STATUS: class=/{c=$0; sub(/.*class=/,"",c)} /INSTRUMENTATION_STATUS: test=/{t=$0; sub(/.*test=/,"",t)} /INSTRUMENTATION_STATUS_CODE: /{k=$0; sub(/.*CODE: /,"",k); print c "#" t " " k}' parity-evidence/tests.txt > parity-evidence/test-results.txt
for req in com.bingemote.stream.PhonePickerGrantTest#pickerGrantListsMoviesAsApp com.bingemote.stream.PhoneMoviesAndroidTest#listsFoldersThenMoviesWithSidecars com.bingemote.stream.PhoneMoviesAndroidTest#subfolderIsNotRoot com.bingemote.stream.PhonePickerStorageTest#realPickerGrantsSharedStorageFolderAndListsIt; do
  if grep -qx "$req 0" parity-evidence/test-results.txt; then echo "REQUIRED PASSED: $req"; else echo "REQUIRED NOT PASSED: $req"; exit 1; fi
done
grep -q '^OK (' parity-evidence/tests.txt
grep -q '^OK (' parity-evidence/tests-stale-load.txt
