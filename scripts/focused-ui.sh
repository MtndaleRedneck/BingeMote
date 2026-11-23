set -euo pipefail
mkdir -p focused-ui
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size 1080x2310
adb shell wm density 480
adb shell settings put system font_scale 1.0
adb shell settings put global hide_error_dialogs 1
adb shell am start -n com.bingemote/.MainActivity --es screen main --es statuscase cur-g
sleep 8
adb shell input keyevent 4
adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --es statuscase cur-g
sleep 5
adb exec-out screencap -p > focused-ui/reconnect.png
adb shell uiautomator dump /sdcard/focused-ui.xml
adb exec-out cat /sdcard/focused-ui.xml > focused-ui/reconnect.xml
adb shell am instrument -w -e class com.bingemote.AutomaticOnlySetupTest,com.bingemote.AutoSetupCheckTest com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee focused-ui/tests.txt
for capture in setup-before-selection setup-selected-pc setup-selected-address setup-cancel-settings updates-success updates-expired; do
  adb exec-out cat "/sdcard/bm-$capture.png" > "focused-ui/$capture.png"
done
grep -q '^OK (' focused-ui/tests.txt
