set -euo pipefail
mkdir -p nav-evidence
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell wm size 1080x2310
adb shell wm density 480
adb shell settings put system font_scale 1.0
adb shell settings put global hide_error_dialogs 1
adb shell am instrument -w -r -e class com.bingemote.SettingsScrollNavTest,com.bingemote.FeedbackFreshEntryTest,com.bingemote.MoviesNavTest com.bingemote.test/androidx.test.runner.AndroidJUnitRunner | tee nav-evidence/tests.txt || true
for c in settings-nav-kept settings-nav-top fb-first-top fb-scrolled-bottom fb-back-settings-kept fb-reentry-top mv-first-top mv-scrolled mv-folder-back-kept mv-reentry-top; do
  adb exec-out cat "/sdcard/Android/data/com.bingemote/files/bm-$c.png" > "nav-evidence/$c.png" || true
done
adb logcat -d -s AndroidRuntime:E > nav-evidence/crashes.txt || true
grep -q '^OK (3 tests)' nav-evidence/tests.txt
# discrimination run: unchanged 35b334f production source
