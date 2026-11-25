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
adb shell uiautomator dump /sdcard/focused-ui.xml
sleep 3
adb exec-out screencap -p > focused-ui/reconnect.png
adb exec-out cat /sdcard/focused-ui.xml > focused-ui/reconnect.xml
adb shell am instrument -w -r -e class com.bingemote.AutomaticOnlySetupTest,com.bingemote.AutoSetupCheckTest,com.bingemote.ImmediateFlipTest,com.bingemote.DisconnectedPlaybackTest,com.bingemote.HapticTickTest,com.bingemote.DisconnectedHomeUiTest,com.bingemote.SettingsScrollNavTest com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee focused-ui/tests.txt
for capture in setup-before-selection setup-selected-pc setup-selected-address setup-cancel-settings updates-success updates-expired disconnected-zero disconnected-reconnected vlcclosed-zero vlcrefused-zero settings-nav-kept settings-nav-top; do
  adb exec-out cat "/sdcard/Android/data/com.bingemote/files/bm-$capture.png" > "focused-ui/$capture.png"
done

adb shell am force-stop com.bingemote
adb shell am start -n com.bingemote/.MainActivity --es screen main --es lockcontrols true
sleep 8
adb shell uiautomator dump /sdcard/status-icon.xml
sleep 2
adb exec-out screencap -p > focused-ui/status-icon-native.png
adb shell cmd statusbar expand-notifications
sleep 3
adb exec-out screencap -p > focused-ui/status-icon-shade.png
adb shell cmd statusbar collapse
grep -q '^OK (' focused-ui/tests.txt
