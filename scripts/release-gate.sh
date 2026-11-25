#!/usr/bin/env bash
# Release gate: every REQUIRED job must have result "success". Skipped, cancelled, failure,
# missing or unknown all fail. Input: the `needs` context as JSON on stdin (toJSON(needs)).
# Usage: release-gate.sh job1 job2 ... < needs.json
set -uo pipefail
required=("$@")
# A rerun (run_attempt > 1) must never publish: "re-run failed jobs" would turn an old red
# into a green gate inside the same run. Publication needs a fresh dispatch (attempt 1).
attempt="${RUN_ATTEMPT:-}"
echo "run_attempt=${attempt:-UNSET}"
[ "$attempt" = "1" ] || { echo "GATE FAIL: run_attempt must be 1 (got '${attempt:-unset}'); publish needs a fresh dispatch"; exit 3; }
[ "${#required[@]}" -gt 0 ] || { echo "GATE FAIL: no required jobs given"; exit 2; }
json=$(cat)
python3 - "$json" "${required[@]}" <<'PY'
import json, sys
try:
    needs = json.loads(sys.argv[1])
except Exception as e:
    print("GATE FAIL: unreadable needs context:", e); sys.exit(2)
bad = 0
print("REQUIRED GATE TABLE")
for job in sys.argv[2:]:
    res = (needs.get(job) or {}).get("result", "MISSING")
    print(f"  {job:16} {res}")
    if res != "success":
        bad += 1
if bad:
    print(f"GATE FAIL: {bad} required job(s) not success")
    sys.exit(1)
print("GATE PASS: all required jobs success")
PY
