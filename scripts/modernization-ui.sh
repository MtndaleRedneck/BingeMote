set -euo pipefail
mkdir -p modernization-evidence
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size 1080x2310
adb shell wm density 480
adb shell settings put system font_scale 1.0
adb shell settings put global hide_error_dialogs 1
adb shell am start -n com.bingemote/.MainActivity --es screen main --es statuscase cur-g
sleep 5
adb shell input keyevent 4
adb shell am force-stop com.bingemote
adb shell am instrument -w -r -e class com.bingemote.ModernizationUiTest,com.bingemote.NotificationTapMatrixTest,com.bingemote.HapticTickTest,com.bingemote.ImmediateFlipTest,com.bingemote.DisconnectedPlaybackTest,com.bingemote.RefinementsUiTest,com.bingemote.StoreMigrationTest,com.bingemote.SubtitleSheetTest,com.bingemote.SubtitleClientTest,com.bingemote.SubtitleJournalTest com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee modernization-evidence/tests.txt
for capture in modern-movies-offline modern-settings-reentry refinements-yourpc refinements-settings refinements-after-pair-tap subtitle-sheet subtitle-sheet-large-font subtitle-sheet-disconnected subtitle-sheet-lost; do
  # Skip absent captures: an unconditional cat writes the error text into a
  # .png, a fake visual artifact in the evidence bundle.
  if adb shell test -f "/sdcard/Android/data/com.bingemote/files/bm-$capture.png"; then
    adb exec-out cat "/sdcard/Android/data/com.bingemote/files/bm-$capture.png" > "modernization-evidence/$capture.png"
  fi
done
adb shell am instrument -w -r -e class 'com.bingemote.CancelGestureTest#releaseInsideCancelsFillsIn' com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee modernization-evidence/cancel-tests.txt
if adb shell test -f /sdcard/bm-cancel-inside-fills.png; then
  adb exec-out cat /sdcard/bm-cancel-inside-fills.png > modernization-evidence/cancel-fills.png
fi
adb shell am force-stop com.bingemote
adb logcat -d -s AndroidRuntime:E > modernization-evidence/crashes.txt
# Diagnostic failure dumps (FailureDumpRule): pull whatever exists, tolerate absence.
for f in $(adb shell ls /sdcard/Android/data/com.bingemote/files/ | grep -E '^bm-(dump|andialog|dismissed|precond)' || true); do
  adb exec-out cat "/sdcard/Android/data/com.bingemote/files/$f" > "modernization-evidence/$f" || true
done
grep -q '^OK (' modernization-evidence/tests.txt
grep -q '^OK (' modernization-evidence/cancel-tests.txt

# Actual minified production APK, not the unshrunk debug variant. Fresh-install
# smoke covers encrypted preferences and authenticated HTTP before release gating.
# Every instrument call is time-boxed: run 45340217426 hung silently for 23 minutes
# on the first release instrument with zero output, so a stall now dumps logcat and
# the process table as evidence instead of burning the job timeout.
run_release_instr() {
  local name=$1; shift
  local out="modernization-evidence/$name.txt"
  if ! timeout 300 adb shell am instrument -w -r "$@" com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee "$out"; then
    echo "INSTRUMENT STALLED OR FAILED: $name"
    adb logcat -d > "modernization-evidence/$name-logcat.txt" || true
    adb shell dumpsys activity processes > "modernization-evidence/$name-processes.txt" || true
    adb shell am force-stop com.bingemote || true
    exit 1
  fi
  if ! grep -q '^OK (' "$out"; then
    echo "INSTRUMENT ASSERTIONS FAILED: $name"
    adb logcat -d > "modernization-evidence/$name-logcat.txt" || true
    exit 1
  fi
}
adb shell am force-stop com.bingemote
adb uninstall com.bingemote.test
adb uninstall com.bingemote
adb install app/build/outputs/apk/release/app-release.apk
adb install app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
run_release_instr release-keystore-seed -e class com.bingemote.RecoveryContinuityTest -e phase seed
adb shell am force-stop com.bingemote
run_release_instr release-keystore-verify -e class com.bingemote.RecoveryContinuityTest -e phase verify
# These two methods reuse the existing fixture on ONE current minified APK.
# This is shrink/runtime coverage, not a cross-version upgrade claim.
run_release_instr release-auth-seed -e class 'com.bingemote.UpgradeConnectionTest#seedOldRelease'
run_release_instr release-auth-verify -e class 'com.bingemote.UpgradeConnectionTest#verifyNewReleaseAfterUpdate'
adb shell am start -n com.bingemote/.MainActivity
sleep 4
adb exec-out screencap -p > modernization-evidence/minified-production.png
