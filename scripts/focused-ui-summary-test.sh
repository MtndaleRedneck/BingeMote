#!/usr/bin/env bash
# Runs the ACTUAL strict-summary tail of scripts/focused-ui.sh under the SAME shell options as the
# script (set -euo pipefail) against fixtures. Healthy 38 must PASS; every bad shape must FAIL.
set -uo pipefail
tail_part=$(sed -n '/^# Strict summary/,$p' scripts/focused-ui.sh)
hdr=$(sed -n '1p' scripts/focused-ui.sh)   # the script's own "set -euo pipefail"
[ "$hdr" = "set -euo pipefail" ] || { echo "BAD  focused-ui.sh line 1 is not set -euo pipefail"; exit 1; }
w=$(mktemp -d); mkdir -p "$w/focused-ui"; fail=0
run() { # $1 want(pass|fail) $2 label ; fixture on stdin
  cat > "$w/focused-ui/tests.txt"
  (cd "$w" && bash -c "$hdr"$'\n'"$tail_part" >"$w/out.txt" 2>&1); rc=$?
  if [ "$1" = pass ] && [ $rc -eq 0 ] && grep -q 'FOCUSED-UI GATE PASS' "$w/out.txt"; then echo "ok   $2 -> PASS"
  elif [ "$1" = fail ] && [ $rc -ne 0 ] && grep -q 'FOCUSED-UI GATE FAIL' "$w/out.txt"; then echo "ok   $2 -> FAIL (gate message)"
  else echo "BAD  $2: want $1 rc=$rc"; cat "$w/out.txt"; fail=1; fi
}
blocks() { for i in $(seq 1 "$2"); do printf 'INSTRUMENTATION_STATUS: numtests=%s\r\nINSTRUMENTATION_STATUS: test=t%s\r\n' "$1" "$i"; done; }
{ blocks 38 76; printf '\r\nOK (38 tests)\r\n\r\n'; } | run pass "healthy 38 blocks + OK (38 tests), CRLF"
{ blocks 38 75; blocks 37 1; printf 'OK (38 tests)\r\n'; } | run fail "one block numtests=37"
printf 'OK (38 tests)\r\n' | run fail "no numtests"
{ blocks 38 76; printf 'OK (37 tests)\r\n'; } | run fail "OK (37 tests)"
{ blocks 38 76; printf 'FAILURES!!!\r\nTests run: 38,  Failures: 1\r\n'; } | run fail "FAILURES!!! present"
{ blocks 38 76; printf 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\r\n'; } | run fail "process crashed"
: | run fail "empty file"
[ $fail -eq 0 ] && echo "FOCUSED-UI SUMMARY SELFTEST PASS" || { echo "FOCUSED-UI SUMMARY SELFTEST FAIL"; exit 1; }
