set -uo pipefail
N=10
mkdir -p diag
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size 1080x2310
adb shell wm density 480
adb shell settings put system font_scale 1.0
adb shell settings put global hide_error_dialogs 1
D=/sdcard/Android/data/com.bingemote/files
for i in $(seq 1 $N); do
  adb shell "rm -f $D/bm-diag* $D/bm-stublog* $D/bm-logcat* $D/bm-dump* $D/bm-dump2*" || true
  adb logcat -c || true
  adb shell am instrument -w -r -e class com.bingemote.DisconnectedHomeUiTest#connectedThenDropReadsZeroThenReconnects com.bingemote.test/androidx.test.runner.AndroidJUnitRunner > diag/iter$i-tests.txt 2>&1
  res=$(tr -d '\r' < diag/iter$i-tests.txt | grep -E '^OK \(|FAILURES|Tests run' | tr '\n' ' ')
  echo "iter $i: $res"
  for f in $(adb shell "ls $D" | tr -d '\r' | grep -E '^bm-(diag|stublog|logcat|dump)'); do
    adb exec-out cat "$D/$f" > "diag/iter$i-$f" || true
  done
done
echo "DIAG DONE ($N iterations; per-iteration results above)"
