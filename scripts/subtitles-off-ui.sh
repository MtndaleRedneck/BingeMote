set -euo pipefail
mkdir -p subtitles-off-evidence
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
adb shell am instrument -w -r -e class com.bingemote.NotificationTapMatrixTest,com.bingemote.HapticTickTest,com.bingemote.ImmediateFlipTest,com.bingemote.DisconnectedPlaybackTest,com.bingemote.RefinementsUiTest,com.bingemote.StoreMigrationTest,com.bingemote.SubtitleSheetTest,com.bingemote.SubtitleClientTest,com.bingemote.SubtitleJournalTest com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee subtitles-off-evidence/tests.txt
for capture in refinements-yourpc refinements-settings refinements-after-pair-tap subtitle-sheet subtitle-sheet-large-font subtitle-sheet-disconnected subtitle-sheet-lost; do
  # Skip absent captures: an unconditional cat writes the error text into a
  # .png, a fake visual artifact in the evidence bundle.
  if adb shell test -f "/sdcard/Android/data/com.bingemote/files/bm-$capture.png"; then
    adb exec-out cat "/sdcard/Android/data/com.bingemote/files/bm-$capture.png" > "subtitles-off-evidence/$capture.png"
  fi
done
adb shell am instrument -w -r -e class 'com.bingemote.CancelGestureTest#releaseInsideCancelsFillsIn' com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee subtitles-off-evidence/cancel-tests.txt
if adb shell test -f /sdcard/bm-cancel-inside-fills.png; then
  adb exec-out cat /sdcard/bm-cancel-inside-fills.png > subtitles-off-evidence/cancel-fills.png
fi
adb shell am force-stop com.bingemote
adb logcat -d -s AndroidRuntime:E > subtitles-off-evidence/crashes.txt
# Diagnostic failure dumps (FailureDumpRule): pull whatever exists, tolerate absence.
for f in $(adb shell ls /sdcard/Android/data/com.bingemote/files/ | grep -E '^bm-(dump|andialog|dismissed|precond)' || true); do
  adb exec-out cat "/sdcard/Android/data/com.bingemote/files/$f" > "subtitles-off-evidence/$f" || true
done
grep -q '^OK (' subtitles-off-evidence/tests.txt
grep -q '^OK (' subtitles-off-evidence/cancel-tests.txt
