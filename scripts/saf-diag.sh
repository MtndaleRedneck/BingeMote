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
# A: picker launched with the test provider's DOCUMENT uri as the initial uri (the app's DebugRig does the same)
U="content://com.bingemote.test.documents/tree/test-root/document/test-root"
adb shell am force-stop com.google.android.documentsui
adb shell am start -a android.intent.action.OPEN_DOCUMENT_TREE --eu android.provider.extra.INITIAL_URI "$U" >/dev/null; sleep 6
snap A-initial-uri
# B: bare picker, then tap the root breadcrumb/title to open the roots list
adb shell am force-stop com.google.android.documentsui
adb shell am start -a android.intent.action.OPEN_DOCUMENT_TREE >/dev/null; sleep 6
snap B-bare
tapnode() { # $1 xml, $2 attr regex fragment (text or content-desc), prints tapped centre
  python3 - "$1" "$2" <<'PY'
import re,sys,subprocess
x=open(sys.argv[1]).read(); pat=sys.argv[2]
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    if re.search(pat,n):
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n)
        if b:
            a=list(map(int,b.groups())); cx=(a[0]+a[2])//2; cy=(a[1]+a[3])//2
            print(cx,cy); subprocess.run(['adb','shell','input','tap',str(cx),str(cy)]); sys.exit(0)
sys.exit(1)
PY
}
tapnode parity-evidence/B-bare.xml 'text="sdk_gphone64_x86_64"' | tee -a parity-evidence/notes.txt; sleep 3
snap C-after-breadcrumb-tap
# D: same but via the title in the app bar
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell cmd package query-activities --brief -a android.intent.action.OPEN_DOCUMENT_TREE > parity-evidence/picker-activities.txt 2>&1 || true
adb logcat -d | grep -iE "documentsui|bingemote.test.documents|RootsLoader|RootInfo" | tail -80 > parity-evidence/saf-logcat.txt
exit 0
