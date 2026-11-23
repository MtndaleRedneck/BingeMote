set -euo pipefail
mkdir -p refinements-evidence
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
adb shell am instrument -w -r -e class com.bingemote.NotificationTapMatrixTest,com.bingemote.HapticTickTest,com.bingemote.ImmediateFlipTest,com.bingemote.DisconnectedPlaybackTest,com.bingemote.RefinementsUiTest,com.bingemote.StoreMigrationTest,com.bingemote.SubtitleSheetTest,com.bingemote.SubtitleClientTest,com.bingemote.SubtitleJournalTest com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee refinements-evidence/tests.txt
for capture in refinements-yourpc refinements-settings refinements-after-pair-tap subtitle-sheet subtitle-sheet-large-font; do
  adb exec-out cat "/sdcard/Android/data/com.bingemote/files/bm-$capture.png" > "refinements-evidence/$capture.png"
done
adb shell am instrument -w -r -e class 'com.bingemote.CancelGestureTest#releaseInsideCancelsFillsIn' com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee refinements-evidence/cancel-tests.txt
adb exec-out cat /sdcard/bm-cancel-inside-fills.png > refinements-evidence/cancel-fills.png
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --es lockcontrols true
sleep 3
adb exec-out screencap -p > refinements-evidence/status-icon-native.png
adb shell cmd statusbar expand-notifications
sleep 2
adb exec-out screencap -p > refinements-evidence/notification-shade.png
adb shell cmd statusbar collapse
adb logcat -d -s AndroidRuntime:E > refinements-evidence/crashes.txt
grep -q '^OK (' refinements-evidence/tests.txt
grep -q '^OK (' refinements-evidence/cancel-tests.txt
