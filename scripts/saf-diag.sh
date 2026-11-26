set -uo pipefail
mkdir -p parity-evidence
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size 1080x2310
adb shell wm density 480
adb shell settings put global hide_error_dialogs 1
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
dumpui() { # $1 name: harness-side hierarchy dump with retries
  for i in 1 2 3 4; do adb shell uiautomator dump /sdcard/$1.xml >/dev/null 2>&1 && adb pull /sdcard/$1.xml parity-evidence/$1.xml >/dev/null 2>&1 && return 0; sleep 2; done; echo "dump $1 failed" >> parity-evidence/notes.txt
}
snap() { adb exec-out screencap -p > parity-evidence/$1.png; dumpui $1; }
adb shell cmd overlay list --user 0 2>/dev/null | grep -i navbar > parity-evidence/navbar-overlays.txt || true
adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton >> parity-evidence/notes.txt 2>&1 || true
sleep 3
adb shell settings get secure navigation_mode >> parity-evidence/notes.txt
adb shell am force-stop com.google.android.documentsui
adb shell am start -a android.intent.action.OPEN_DOCUMENT_TREE >/dev/null; sleep 6
snap D-bare-3button
adb shell input swipe 3 1200 700 1200 300; sleep 2
snap E-swipe-from-edge
adb shell input swipe 20 1200 700 1200 300; sleep 2
snap F-swipe-from-20
# overflow menu
adb shell am force-stop com.google.android.documentsui
adb shell am start -a android.intent.action.OPEN_DOCUMENT_TREE >/dev/null; sleep 5
adb shell input tap 1008 195; sleep 2
snap G-overflow
adb logcat -d | grep -iE "documentsui|RootsLoader|test.documents" | tail -60 > parity-evidence/saf-logcat.txt
exit 0
