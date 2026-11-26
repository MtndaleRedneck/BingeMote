set -euo pipefail
mkdir -p parity-evidence
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size 1080x2310
adb shell wm density 480
adb shell settings put system font_scale 1.0
adb shell settings put global hide_error_dialogs 1
adb shell am instrument -w -r -e class com.bingemote.TopBarParityTest,com.bingemote.stream.StreamServerAndroidTest,com.bingemote.stream.StreamServiceLifecycleTest,com.bingemote.stream.StreamServiceNoWifiTest,com.bingemote.stream.PhonePickerGrantTest,com.bingemote.stream.PhoneMoviesAndroidTest com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee parity-evidence/tests.txt || true
for s in main movies settings movies-phone; do
  adb shell am force-stop com.bingemote
  adb shell am start -n com.bingemote/.MainActivity --es screen "$s" >/dev/null
  sleep 8
  adb exec-out screencap -p > "parity-evidence/$s.png"
done
adb logcat -d -s AndroidRuntime:E > parity-evidence/crashes.txt || true
grep -c 'INSTRUMENTATION_STATUS_CODE: -3' parity-evidence/tests.txt > parity-evidence/skipped-count.txt || true
echo "skipped (Assume) tests: $(cat parity-evidence/skipped-count.txt)"
grep -q '^OK (' parity-evidence/tests.txt
