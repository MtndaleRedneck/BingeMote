#!/usr/bin/env bash
# Discrimination for release-gate.sh: it must pass only when ALL required jobs are success.
set -uo pipefail
REQ=(android helper helper-e2e scanner-test focused-ui screenshots)
mk() { # $1 = job to override, $2 = its result ("" = all success, "OMIT" = job absent)
  python3 - "$1" "$2" <<'PY'
import json, sys
jobs = ["android","helper","helper-e2e","scanner-test","focused-ui","screenshots"]
d = {j: {"result": "success"} for j in jobs}
j, r = sys.argv[1], sys.argv[2]
if j:
    if r == "OMIT": d.pop(j)
    else: d[j]["result"] = r
print(json.dumps(d))
PY
}
fail=0
expect() { # $1 = expected exit class (pass|block), rest = override args
  local want="$1"; shift
  mk "$@" | RUN_ATTEMPT=1 bash scripts/release-gate.sh "${REQ[@]}" >/tmp/gate.out 2>&1; local rc=$?
  if [ "$want" = pass ] && [ $rc -eq 0 ]; then echo "ok   all-success -> pass"
  elif [ "$want" = block ] && [ $rc -ne 0 ]; then echo "ok   $1:$* -> blocked (rc=$rc)"
  else echo "BAD  expected $want for '$*' got rc=$rc"; cat /tmp/gate.out; fail=1; fi
}
expect pass "" ""
expect block focused-ui failure
expect block focused-ui skipped
expect block focused-ui cancelled
expect block screenshots skipped
expect block scanner-test failure
expect block helper-e2e OMIT
expect block android failure
python3 scripts/release-gate-structure.py || fail=1
mk "" "" | RUN_ATTEMPT=2 bash scripts/release-gate.sh "${REQ[@]}" >/dev/null 2>&1 && { echo "BAD  rerun attempt 2 passed"; fail=1; } || echo "ok   all-success but run_attempt=2 -> blocked"
mk "" "" | env -u RUN_ATTEMPT bash scripts/release-gate.sh "${REQ[@]}" >/dev/null 2>&1 && { echo "BAD  unset attempt passed"; fail=1; } || echo "ok   all-success but run_attempt unset -> blocked"
echo '{}' | RUN_ATTEMPT=1 bash scripts/release-gate.sh "${REQ[@]}" >/dev/null 2>&1 && { echo "BAD  empty needs passed"; fail=1; } || echo "ok   empty needs -> blocked"
echo 'not json' | RUN_ATTEMPT=1 bash scripts/release-gate.sh "${REQ[@]}" >/dev/null 2>&1 && { echo "BAD  garbage passed"; fail=1; } || echo "ok   garbage -> blocked"
[ $fail -eq 0 ] && echo "RELEASE GATE SELFTEST PASS" || { echo "RELEASE GATE SELFTEST FAIL"; exit 1; }
