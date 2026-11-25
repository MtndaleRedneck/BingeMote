# Structural check of the real workflow: gate needs == every other job; the gate command lists
# exactly those; release needs only release-gate, requires its success, has no always()/failure(),
# and nothing uses continue-on-error.
import re, sys
t = open(".github/workflows/build.yml").read()
if re.search(r"continue-on-error", t):
    print("BAD  continue-on-error present"); sys.exit(1)
body = t.split("\njobs:\n", 1)[1]
blocks = re.split(r"(?m)^  ([a-z0-9-]+):\s*$", body)
jobs = {blocks[i]: blocks[i + 1] for i in range(1, len(blocks), 2)}
req = set(jobs) - {"release-gate", "release"}
gneeds = {x.strip() for x in re.search(r"needs: \[([^\]]*)\]", jobs["release-gate"]).group(1).split(",")}
cmd = re.search(r"release-gate\.sh ([a-z0-9 -]+)", jobs["release-gate"]).group(1).split()
ok = True
if gneeds != req: print("BAD  gate needs", sorted(gneeds), "!= jobs", sorted(req)); ok = False
if set(cmd) != req or len(cmd) != len(set(cmd)): print("BAD  gate command list", cmd); ok = False
rn = re.search(r"needs: \[([^\]]*)\]", jobs["release"]).group(1).strip()
if rn != "release-gate": print("BAD  release needs", rn); ok = False
rif = re.search(r"(?m)^    if: (.*)$", jobs["release"]).group(1)
if "needs.release-gate.result == 'success'" not in rif or "always()" in rif or "failure()" in rif:
    print("BAD  release if:", rif); ok = False
gif = re.search(r"(?m)^    if: (.*)$", jobs["release-gate"]).group(1)
for need in ("always()", "github.event_name == 'workflow_dispatch'", "inputs.publish == true", "inputs.only == 'all'"):
    if need not in gif: print("BAD  gate if: missing", need); ok = False
for need in ("github.event_name == 'workflow_dispatch'", "inputs.publish == true", "inputs.only == 'all'"):
    if need not in rif: print("BAD  release if: missing", need); ok = False
if "if:" in "".join(l for l in jobs["release"].split("steps:")[1].split("\n")) and False: pass
if "--draft" in jobs["release"] and False: pass
if "github.run_attempt" not in jobs["release-gate"]: print("BAD  gate does not receive run_attempt"); ok = False
if "pattern:" in jobs["release"] or "merge-multiple" in jobs["release"]: print("BAD  release downloads by broad pattern"); ok = False
print("ok   workflow structure: required=%s" % sorted(req) if ok else "BAD  workflow structure")
sys.exit(0 if ok else 1)
