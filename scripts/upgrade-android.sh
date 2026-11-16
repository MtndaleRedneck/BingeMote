#!/bin/bash
set -euo pipefail
mkdir -p evidence
adb install old/app/build/outputs/apk/release/app-release.apk
adb install new-test.apk
adb shell am instrument -w -e class 'com.bingemote.UpgradeConnectionTest#seedOldRelease' com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee evidence/seed124.txt
grep -q '^OK (1 test)' evidence/seed124.txt
adb shell am force-stop com.bingemote
adb install -r new/app/build/outputs/apk/release/app-release.apk
adb shell pm revoke com.bingemote android.permission.POST_NOTIFICATIONS
adb shell am instrument -w -e permission denied -e class 'com.bingemote.UpgradeConnectionTest#verifyNewReleaseAfterUpdate' com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee evidence/verify126-denied.txt
adb shell am force-stop com.bingemote
adb shell pm grant com.bingemote android.permission.POST_NOTIFICATIONS
adb shell am instrument -w -e permission granted -e class 'com.bingemote.UpgradeConnectionTest#verifyNewReleaseAfterUpdate' com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee evidence/verify126-granted.txt
adb logcat -d > evidence/logcat.txt
adb exec-out screencap -p > evidence/after-upgrade.png
grep -q '^OK (1 test)' evidence/verify126-denied.txt
grep -q '^OK (1 test)' evidence/verify126-granted.txt

for scenario in token refused vlc unreachable; do
  adb shell am force-stop com.bingemote
  adb shell am instrument -w -e scenario "$scenario" -e class 'com.bingemote.UpgradeConnectionTest#diagnosticReasons' com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee "evidence/diagnostic-$scenario.txt"
  adb pull "/sdcard/Android/data/com.bingemote/files/diagnostic-$scenario.png" "evidence/diagnostic-$scenario.png"
  grep -q '^OK (1 test)' "evidence/diagnostic-$scenario.txt"
done
adb logcat -d > evidence/logcat.txt
adb shell am force-stop com.bingemote
adb shell pm grant com.bingemote android.permission.POST_NOTIFICATIONS
adb shell am instrument -w -e class 'com.bingemote.BarActionsTest' com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee evidence/bar-actions-release.txt
grep -q '^OK (2 tests)' evidence/bar-actions-release.txt
adb logcat -d >> evidence/logcat.txt

for again in no yes; do
  adb shell am force-stop com.bingemote
  adb shell pm revoke com.bingemote android.permission.POST_NOTIFICATIONS
  adb shell pm clear-permission-flags com.bingemote android.permission.POST_NOTIFICATIONS user-set user-fixed
  adb shell am instrument -w -e again "$again" -e class 'com.bingemote.UpgradeConnectionTest#notificationSettingPermissionPopup' com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee "evidence/permission-$again.txt"
  adb pull /sdcard/Android/data/com.bingemote/files/ evidence/settings-files
  grep -q '^OK (1 test)' "evidence/permission-$again.txt"
done
adb pull /sdcard/Android/data/com.bingemote/files/ evidence/settings-files
