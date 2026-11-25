#!/usr/bin/env bash
# Exact-signed-APK navigation check. Installs the already-built staged APK as-is (never rebuilds
# the app) and runs the three approved navigation classes against it. Evidence is captured on
# EVERY exit, before any teardown, and never contains an APK.
# Required env: NEW_HASH (sha256 of staged.apk), EXPECTED_SIGNER (signer cert sha256 hex, lowercase).
set -uo pipefail
EV=nav-evidence
APP=com.bingemote
TESTS='com.bingemote.MoviesNavTest,com.bingemote.FeedbackFreshEntryTest,com.bingemote.SettingsScrollNavTest'
EXPECTED_TESTS=3
READY_DEADLINE_S=${READY_DEADLINE_S:-90}
INSTRUMENT_DEADLINE_S=${INSTRUMENT_DEADLINE_S:-600}
# Exactly the tests the approved rule covers, one @Test per class (verified in source at 72404a82).
EXPECTED_PAIRS='com.bingemote.MoviesNavTest#moviesFreshEntryTopAndFolderBackKeepsPlace
com.bingemote.FeedbackFreshEntryTest#feedbackReopensAtTopButBackKeepsSettingsPlace
com.bingemote.SettingsScrollNavTest#backFromSubpageKeepsPositionAndReentryResetsToTop'
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
TESTAPK=app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
mkdir -p "$EV"
# Every adb call is bounded (default 20 s; override per call with ADB_CALL_S=N) so a wedged emulator cannot starve
# the evidence trap. A timed-out call returns 124 and is recorded as a capture failure, never as success.
adb() { timeout "${ADB_CALL_S:-20}" "$(type -P adb)" "$@"; }
CLASS=NOT_STARTED
DETAIL="script did not reach a classified outcome"

log() { printf '%s %s\n' "$(date -u +%H:%M:%SZ)" "$*" | tee -a "$EV/steps.txt"; }

capture_evidence() { # $1 = tag; never fails the script, never tears the app down; failures are recorded honestly
  local tag="$1" rc
  cap() { local out="$1"; shift; "$@" > "$out" 2>/dev/null; rc=$?; [ "$rc" -eq 0 ] || echo "CAPTURE_FAIL $tag $(basename "$out") rc=$rc" >> "$EV/capture-status.txt"; return 0; }
  cap "$EV/$tag-screen.png" adb exec-out screencap -p
  adb shell uiautomator dump /sdcard/nav-$tag.xml >/dev/null 2>&1 || echo "CAPTURE_FAIL $tag uiautomator-dump rc=$?" >> "$EV/capture-status.txt"
  cap "$EV/$tag-ui.xml" adb exec-out cat /sdcard/nav-$tag.xml
  adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp|mFocusedWindow' > "$EV/$tag-focus.txt"; [ "${PIPESTATUS[0]}" -eq 0 ] || echo "CAPTURE_FAIL $tag focus rc=${PIPESTATUS[0]}" >> "$EV/capture-status.txt"
  adb shell dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|ResumedActivity' > "$EV/$tag-activity.txt"; [ "${PIPESTATUS[0]}" -eq 0 ] || echo "CAPTURE_FAIL $tag activity rc=${PIPESTATUS[0]}" >> "$EV/capture-status.txt"
  adb logcat -d -v threadtime 2>/dev/null | tail -n 1500 > "$EV/$tag-logcat.txt"; [ "${PIPESTATUS[0]}" -eq 0 ] || echo "CAPTURE_FAIL $tag logcat rc=${PIPESTATUS[0]}" >> "$EV/capture-status.txt"
}

pull_app_files() { # verified pull of the test captures (real PNG / non-stub only)
  local names n sz sig
  names=$(adb shell 'ls /sdcard/Android/data/com.bingemote/files/ 2>/dev/null' | tr -d '\r' | grep -E '^bm-' || true)
  for n in $names; do
    adb exec-out cat "/sdcard/Android/data/$APP/files/$n" > "$EV/app-$n.tmp" 2>/dev/null || { rm -f "$EV/app-$n.tmp"; echo "PULL: $n transport failed" >> "$EV/steps.txt"; continue; }
    sz=$(stat -c%s "$EV/app-$n.tmp" 2>/dev/null || echo 0)
    sig=$(file -b "$EV/app-$n.tmp" | cut -c1-40)
    case "$n" in
      *.png) echo "$sig" | grep -q 'PNG image' || { rm -f "$EV/app-$n.tmp"; echo "PULL: $n not a PNG, not kept" >> "$EV/steps.txt"; continue; } ;;
    esac
    if [ "$sz" -lt 200 ] && grep -qm1 'No such file\|Permission denied' "$EV/app-$n.tmp"; then rm -f "$EV/app-$n.tmp"; echo "PULL: $n error stub, not kept" >> "$EV/steps.txt"; continue; fi
    mv "$EV/app-$n.tmp" "$EV/app-$n"; echo "PULLED: $n ($sz bytes)" >> "$EV/steps.txt"
  done
}

on_exit() {
  local rc=$?
  set +e
  capture_evidence final
  pull_app_files
  {
    echo "classification: $CLASS"
    echo "detail: $DETAIL"
    echo "exit_code: $rc"
    if [ -s "$EV/capture-status.txt" ]; then echo "evidence_capture_failures: $(wc -l < "$EV/capture-status.txt") (see capture-status.txt; the evidence set is INCOMPLETE)"; else echo "evidence_capture_failures: 0"; fi
  } > "$EV/classification.txt"
  cat "$EV/classification.txt"
  # No APK may leave the runner through the evidence folder.
  # Allow only PNG, text and XML by CONTENT (not name); anything else (archives, APKs renamed, oversized) is deleted.
  find "$EV" -type f | while read -r f; do
    t=$(file -b "$f" | cut -c1-60); sz=$(stat -c%s "$f")
    if [ "$sz" -gt 8388608 ] || ! echo "$t" | grep -Eq 'PNG image|text|XML|empty|ASCII|UTF-8'; then
      echo "GUARD: removed $f ($t, $sz bytes)" >> "$EV/guard.txt"; rm -f "$f"
    fi
  done
  return $rc
}
trap on_exit EXIT
trap '[ -n "${IPID:-}" ] && kill "$IPID" 2>/dev/null; CLASS=RUN_SIGNALLED; DETAIL="script received a termination signal (job cancel or timeout); outcome NOT established"; exit 143' TERM INT HUP

fail() { CLASS="$1"; DETAIL="$2"; log "FAIL $CLASS: $DETAIL"; exit 1; }

# 1. Exact bytes and signer (before anything is installed).
[ -x "$APKSIGNER" ] || fail BUILD_TOOLS_MISSING "apksigner not found at $APKSIGNER on this runner image; navigation NOT RUN (a tool path problem, not a signer result)"
[ -f staged.apk ] || fail ASSET_MISSING "staged.apk was not fetched; navigation NOT RUN"
[ -f "$TESTAPK" ] || fail TEST_APK_MISSING "test APK not found at $TESTAPK; navigation NOT RUN (a build/path problem, not a signer result)"
printf '%s  staged.apk\n' "$NEW_HASH" | sha256sum -c - >> "$EV/steps.txt" 2>&1 || fail ASSET_HASH_MISMATCH "staged.apk does not match the input sha256; nothing installed, navigation NOT RUN"
signer=$("$APKSIGNER" verify --print-certs staged.apk 2>>"$EV/steps.txt" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)
tsigner=$("$APKSIGNER" verify --print-certs "$TESTAPK" 2>>"$EV/steps.txt" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)
log "staged signer=$signer test signer=$tsigner expected=$EXPECTED_SIGNER"
[ -n "$signer" ] && [ "$signer" = "$EXPECTED_SIGNER" ] && [ "$tsigner" = "$EXPECTED_SIGNER" ] || fail SIGNER_MISMATCH "staged or test APK signer differs from the pinned signer; navigation NOT RUN"

# 2. Emulator display as focused-ui uses (the tests' swipe coordinates assume it).
adb shell wm size 1080x2310 >>"$EV/steps.txt" 2>&1 || echo "SETUP_CMD_FAILED: wm size 1080x2310" >> "$EV/steps.txt"
adb shell wm density 480 >>"$EV/steps.txt" 2>&1 || echo "SETUP_CMD_FAILED: wm density 480" >> "$EV/steps.txt"
adb shell settings put system font_scale 1.0 >>"$EV/steps.txt" 2>&1 || echo "SETUP_CMD_FAILED: settings put system font_scale 1.0" >> "$EV/steps.txt"
adb shell settings put global hide_error_dialogs 1 >>"$EV/steps.txt" 2>&1 || echo "SETUP_CMD_FAILED: settings put global hide_error_dialogs 1" >> "$EV/steps.txt"

# 3. Install exact staged bytes, then bind the INSTALLED file back to the hash.
ADB_CALL_S=180 adb install -r staged.apk >> "$EV/steps.txt" 2>&1 || fail INSTALL_FAILED "adb install of the staged APK failed; navigation NOT RUN"
ADB_CALL_S=180 adb install -r "$TESTAPK" >> "$EV/steps.txt" 2>&1 || fail INSTALL_FAILED "adb install of the test APK failed; navigation NOT RUN"
paths=$(adb shell pm path "$APP" | tr -d '\r' | sed -n 's/^package://p')
[ "$(printf '%s\n' "$paths" | grep -c .)" -eq 1 ] || fail INSTALLED_BYTES_MISMATCH "expected exactly one installed APK path for $APP, got: $paths; navigation NOT RUN"
base="$paths"
mkdir -p "$RUNNER_TEMP/installed"
ADB_CALL_S=120 ADB_CALL_S=120 adb pull "$base" "$RUNNER_TEMP/installed/base.apk" >/dev/null 2>&1 || fail INSTALLED_PULL_FAILED "could not pull the installed APK back to hash it; navigation NOT RUN"
inst_hash=$(sha256sum "$RUNNER_TEMP/installed/base.apk" | cut -d' ' -f1)
log "installed base.apk sha256=$inst_hash"
[ "$inst_hash" = "$NEW_HASH" ] || fail INSTALLED_BYTES_MISMATCH "installed APK differs from staged bytes (device path $base); navigation NOT RUN"
adb shell dumpsys package "$APP" | grep -E 'versionCode|versionName|apkSigningVersion' > "$EV/package-version.txt" || true

# 4. Launch readiness with a deadline. "Ready" means ONLY: com.bingemote has window focus AND its accessibility tree has
#    at least 3 nodes, at least one with text or content-description (Compose semantics render). It does NOT mean Home (prefs are unseeded here, the first screen may be Setup) and it is not a
#    pixel check. The navigation tests themselves assert the real screens.
adb shell am force-stop "$APP"
adb logcat -c
ADB_CALL_S=60 adb shell am start -W -n "$APP/.MainActivity" > "$EV/am-start.txt" 2>&1 || log "am start -W did not return success within 60 s"
start=$(date +%s); ready=0; last=""
while [ $(( $(date +%s) - start )) -lt "$READY_DEADLINE_S" ]; do
  focus=$(adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus' | head -1)
  adb shell uiautomator dump /sdcard/ready.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/ready.xml > "$EV/ready-poll.xml" 2>/dev/null
  read -r nodes content < <(python3 - "$EV/ready-poll.xml" "$APP" <<'PY'
import sys, xml.etree.ElementTree as ET
try:
    r = ET.parse(sys.argv[1]).getroot()
    app = [n for n in r.iter('node') if n.get('package') == sys.argv[2]]
    rich = [n for n in app if (n.get('text') or '').strip() or (n.get('content-desc') or '').strip()]
    print(len(app), len(rich))
except Exception:
    print(0, 0)
PY
)
  last="focus=[$focus] app_nodes=$nodes app_nodes_with_text_or_desc=$content"
  if echo "$focus" | grep -q "$APP/" && [ "${nodes:-0}" -ge 3 ] && [ "${content:-0}" -ge 1 ]; then ready=1; break; fi
  sleep 2   # poll interval inside a bounded wait; readiness is the condition, not elapsed time
done
capture_evidence ready
log "readiness: ready=$ready after $(( $(date +%s) - start ))s; $last"
[ "$ready" = 1 ] || fail LAUNCH_NOT_READY "no focused com.bingemote window with >=3 accessibility nodes of which >=1 carries text or content description within ${READY_DEADLINE_S}s ($last); launch/readiness failure, navigation tests NOT RUN"
adb shell am force-stop "$APP"   # tests seed prefs and start their own fresh task
adb logcat -c   # so crash-lines.txt only reflects the instrumented run, not the launch phase

# 5. One instrument call, three classes. Output is kept whatever happens.
adb shell rm -f "/sdcard/Android/data/$APP/files/bm-*" 2>/dev/null || true
# Run in the background and wait, so a termination signal interrupts `wait` and the trap runs at once
# (bash otherwise defers traps until a foreground child exits, which could be the full deadline).
ADB_CALL_S="$INSTRUMENT_DEADLINE_S" adb shell am instrument -w -r -e class "$TESTS" "$APP.test/androidx.test.runner.AndroidJUnitRunner" > "$EV/tests.txt" 2>&1 &
IPID=$!
wait "$IPID"
irc=$?
capture_evidence post-instrument   # before any cleanup, also on the timeout path
if [ "$irc" -eq 124 ]; then adb shell am force-stop "$APP.test" >/dev/null 2>&1; adb shell am force-stop "$APP" >/dev/null 2>&1; fi
tr -d '\r' < "$EV/tests.txt" > "$EV/tests.clean.txt"
adb logcat -d -v threadtime 2>/dev/null | grep -E 'AndroidRuntime|FATAL|ANR in|NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|IncompatibleClassChangeError|VerifyError' | tail -n 80 > "$EV/crash-lines.txt" || true
# Per-test accounting from the -r status stream: a block is class=/test=/CODE. CODE 1 = started, 0 = ok.
# Any other code (-1 error, -2 failure, -3 ignored/assumption) is recorded and is never a pass.
awk '
  /^INSTRUMENTATION_STATUS: class=/ { cls=substr($0, index($0,"class=")+6) }
  /^INSTRUMENTATION_STATUS: test=/  { tst=substr($0, index($0,"test=")+5) }
  /^INSTRUMENTATION_STATUS_CODE: / { c=$2; print c "\t" cls "#" tst; cls="?"; tst="?" }
' "$EV/tests.clean.txt" > "$EV/tests.codes.tsv"
started=$(awk -F'\t' '$1==1' "$EV/tests.codes.tsv" | wc -l)
passed=$(awk -F'\t' '$1==0' "$EV/tests.codes.tsv" | wc -l)
other=$(awk -F'\t' '$1!=0 && $1!=1' "$EV/tests.codes.tsv" | wc -l)
pairs_ok=1
for p in $EXPECTED_PAIRS; do
  [ "$(awk -F'\t' -v p="$p" '$1==0 && $2==p' "$EV/tests.codes.tsv" | wc -l)" -eq 1 ] || pairs_ok=0
  [ "$(awk -F'\t' -v p="$p" '$1==1 && $2==p' "$EV/tests.codes.tsv" | wc -l)" -eq 1 ] || pairs_ok=0
done
log "accounting: started=$started passed=$passed other_codes=$other expected=$EXPECTED_TESTS expected_pairs_each_started_once_and_passed_once=$pairs_ok instrument_exit=$irc"
{ echo "started=$started passed=$passed other_codes=$other expected=$EXPECTED_TESTS pairs_ok=$pairs_ok"; } > "$EV/test-accounting.txt"

# 5b. Post-test custody: the installed bytes must still be the staged bytes after the tests ran.
post_hash=""
if ADB_CALL_S=120 adb pull "$base" "$RUNNER_TEMP/installed/post.apk" >/dev/null 2>&1; then post_hash=$(sha256sum "$RUNNER_TEMP/installed/post.apk" | cut -d' ' -f1); fi
log "post-test installed sha256=${post_hash:-PULL_FAILED}"
if [ "$post_hash" != "$NEW_HASH" ]; then
  fail POST_TEST_BYTES_MISMATCH "installed APK after the tests is not the staged bytes (got ${post_hash:-pull failed}); results not trusted"
fi

# 6. Classify from what was actually seen.
if [ "$irc" -eq 124 ]; then
  fail INSTRUMENTATION_TIMEOUT "instrument exceeded ${INSTRUMENT_DEADLINE_S}s (started=$started passed=$passed); navigation NOT established. Evidence captured before cleanup"
fi
if grep -qx "OK ($EXPECTED_TESTS tests)" "$EV/tests.clean.txt" && ! grep -q 'FAILURES!!!' "$EV/tests.clean.txt" \
   && [ "$started" -eq "$EXPECTED_TESTS" ] && [ "$passed" -eq "$EXPECTED_TESTS" ] && [ "$other" -eq 0 ] && [ "$pairs_ok" -eq 1 ]; then
  CLASS=NAVIGATION_PASS; DETAIL="OK ($EXPECTED_TESTS tests) on the exact staged signed bytes: MoviesNavTest, FeedbackFreshEntryTest, SettingsScrollNavTest"; log "$CLASS"; exit 0
fi
if grep -qE 'NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|IncompatibleClassChangeError|VerifyError' "$EV/tests.clean.txt" "$EV/crash-lines.txt"; then
  fail RELEASE_LINKAGE_FAILURE "class/method linkage error between the test APK and the minified app (see crash-lines.txt); navigation result NOT established. Cause not assumed beyond the logged error"
fi
if ! grep -q '^INSTRUMENTATION_STATUS: numtests=' "$EV/tests.clean.txt" || grep -qE 'INSTRUMENTATION_FAILED|Process crashed|shortMsg=' "$EV/tests.clean.txt"; then
  fail INSTRUMENTATION_ABORTED "instrumentation did not complete (instrument exit=$irc); navigation NOT established. See tests.txt, crash-lines.txt, final logcat"
fi
if awk -F'\t' '$1==-3 || $1==-4' "$EV/tests.codes.tsv" | grep -q .; then
  fail TEST_NOT_EXECUTED "a test reported ignored/assumption-failed (code -3/-4); navigation NOT established (not an assertion failure). See tests.txt"
fi
if grep -q 'FAILURES!!!' "$EV/tests.clean.txt" || [ "$other" -gt 0 ]; then
  fail NAVIGATION_ASSERTION_FAILED "a navigation test ran and failed on the signed bytes (see tests.txt and bm-* captures). Cause not yet read"
fi
fail UNCLASSIFIED "no recognised outcome (instrument exit=$irc, passed=$passed started=$started other=$other pairs_ok=$pairs_ok, expected $EXPECTED_TESTS); read tests.txt before saying anything"
