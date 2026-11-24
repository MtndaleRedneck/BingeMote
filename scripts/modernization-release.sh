set -euo pipefail
# Minified release-runtime evidence, independent of the debug UI suite.
# Runs in the verify-release-runtime job on a fresh emulator.
mkdir -p release-evidence
adb shell wm size 1080x2310
adb shell wm density 480
adb shell settings put system font_scale 1.0
adb shell settings put global hide_error_dialogs 1

# Actual minified production APK, not the unshrunk debug variant. Fresh-install
# smoke covers encrypted preferences and authenticated HTTP before release gating.
# Every instrument call is time-boxed: run 45340217426 hung silently for 23 minutes
# on the first release instrument with zero output, so a stall now dumps logcat and
# the process table as evidence instead of burning the job timeout.
run_release_instr() {
  local name=$1; shift
  local out="release-evidence/$name.txt"
  if ! timeout 300 adb shell am instrument -w -r "$@" com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee "$out"; then
    echo "INSTRUMENT STALLED OR FAILED: $name"
    adb logcat -d > "release-evidence/$name-logcat.txt" || true
    adb shell dumpsys activity processes > "release-evidence/$name-processes.txt" || true
    adb shell am force-stop com.bingemote || true
    exit 1
  fi
  if ! grep -q '^OK (' "$out"; then
    echo "INSTRUMENT ASSERTIONS FAILED: $name"
    adb logcat -d > "release-evidence/$name-logcat.txt" || true
    exit 1
  fi
}
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
adb exec-out screencap -p > release-evidence/minified-production.png
adb logcat -d -s AndroidRuntime:E > release-evidence/crashes.txt
