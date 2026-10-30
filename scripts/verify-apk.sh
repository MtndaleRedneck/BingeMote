#!/usr/bin/env bash
set -u
cd verify
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
{
 for f in old new; do
  echo "== $f.apk"; sha256sum $f.apk
  "$BT/apksigner" verify --verbose --print-certs $f.apk 2>&1 | grep -E "Verifies|Verified using|certificate SHA-256|Number of signers"
  "$BT/aapt" dump badging $f.apk | grep -E "^package:"
 done
} > report.txt 2>&1
OLDC=$("$BT/apksigner" verify --print-certs old.apk | grep "certificate SHA-256" | head -1)
NEWC=$("$BT/apksigner" verify --print-certs new.apk | grep "certificate SHA-256" | head -1)
[ "$OLDC" = "$NEWC" ] && echo "SIGNER MATCH: $NEWC" >> report.txt || echo "SIGNER MISMATCH old[$OLDC] new[$NEWC]" >> report.txt
adb install old.apk >> report.txt 2>&1
adb shell am start -n com.bingemote/.MainActivity >/dev/null 2>&1; sleep 6
adb shell dumpsys package com.bingemote | grep -E "versionCode|versionName|firstInstallTime|lastUpdateTime" > before.txt
adb exec-out screencap -p > old-app.png
adb shell input keyevent KEYCODE_HOME; sleep 3
adb exec-out screencap -p > home-old.png
adb shell input swipe 540 2000 540 500 300; sleep 3
adb exec-out screencap -p > drawer-old.png
adb shell input keyevent KEYCODE_HOME; sleep 2
echo "== install-over (adb install -r, NO uninstall)" >> report.txt
adb install -r new.apk >> report.txt 2>&1
adb shell dumpsys package com.bingemote | grep -E "versionCode|versionName|firstInstallTime|lastUpdateTime" > after.txt
adb shell am start -n com.bingemote/.MainActivity >/dev/null 2>&1; sleep 6
adb exec-out screencap -p > new-app.png
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell input swipe 540 2000 540 500 300; sleep 3
adb exec-out screencap -p > drawer-new.png
adb shell input keyevent KEYCODE_HOME; sleep 2
adb exec-out screencap -p > home-new.png
# pull the launcher icon as the system resolves it
adb shell dumpsys package com.bingemote | grep -iE "icon" > icon-info.txt
exit 0
