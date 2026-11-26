set -uo pipefail
mkdir -p parity-evidence
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size 1080x2310
adb shell wm density 480
adb shell settings put global hide_error_dialogs 1
adb logcat -c
adb shell am instrument -w -r -e class com.bingemote.stream.PhonePickerGrantTest com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee parity-evidence/grant-test.txt
# State the picker was left in by the test (harness-side capture, after the instrumentation session ended)
adb exec-out screencap -p > parity-evidence/after-test.png
adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" > parity-evidence/focus.txt
adb shell uiautomator dump /sdcard/after.xml >/dev/null 2>&1; adb pull /sdcard/after.xml parity-evidence/after.xml >/dev/null 2>&1 || true
for f in $(adb shell ls /sdcard/ | grep "^zephyr-picker-" | tr -d "\r"); do adb pull /sdcard/$f parity-evidence/$f >/dev/null; done
adb logcat -d | grep -iE "documentsui|SAFTest|DocumentsProvider|bingemote.test.documents" | tail -150 > parity-evidence/saf-logcat.txt
# Open the system picker directly (no app): what roots does it list?
adb shell am force-stop com.google.android.documentsui
adb shell am start -a android.intent.action.OPEN_DOCUMENT_TREE >/dev/null; sleep 6
adb exec-out screencap -p > parity-evidence/bare-picker.png
adb shell uiautomator dump /sdcard/bare.xml >/dev/null 2>&1; adb pull /sdcard/bare.xml parity-evidence/bare.xml >/dev/null 2>&1 || true
adb shell cmd package query-activities --brief -a android.intent.action.OPEN_DOCUMENT_TREE > parity-evidence/picker-activities.txt 2>&1 || true
adb shell content query --uri content://com.bingemote.test.documents/root > parity-evidence/provider-root.txt 2>&1 || true
exit 0
