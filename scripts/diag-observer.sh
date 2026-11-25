set -uo pipefail
# ONE iteration of ONE test method. Observer-only test change (pre-teardown captures); host logcat pulled.
mkdir -p diag
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size 1080x2310
adb shell wm density 480
adb shell settings put system font_scale 1.0
adb shell settings put global hide_error_dialogs 1
D=/sdcard/Android/data/com.bingemote/files
adb shell "rm -f $D/bm-obs* $D/bm-stublog* $D/bm-logcat* $D/bm-dump* $D/bm-dump2*" || true
adb logcat -c || true
date +%s%3N > diag/host-start-epoch-ms.txt
adb shell am instrument -w -r -e class com.bingemote.DisconnectedHomeUiTest#connectedThenDropReadsZeroThenReconnects com.bingemote.test/androidx.test.runner.AndroidJUnitRunner > diag/tests.txt 2>&1
date +%s%3N > diag/host-end-epoch-ms.txt
adb logcat -d -v threadtime > diag/host-logcat.txt 2>&1 || true
tr -d '\r' < diag/tests.txt | grep -E '^OK \(|FAILURES|Tests run' | tr '\n' ' '; echo
for f in $(adb shell "ls $D" | tr -d '\r' | grep -E '^bm-(obs|stublog|logcat|dump)'); do
  adb exec-out cat "$D/$f" > "diag/$f" || true
done
adb shell getprop ro.build.version.sdk > diag/props.txt; adb shell getprop ro.product.cpu.abi >> diag/props.txt; adb shell wm size >> diag/props.txt; adb shell wm density >> diag/props.txt
echo "OBSERVER DONE (1 iteration)"
