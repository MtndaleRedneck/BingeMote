# Builds the ISOLATED gate-proof workflow from the real build.yml (same repo tree, scripts untouched):
# six trivial jobs with the REAL ids, REAL `if:` and `needs:` lines; the REAL release-gate job block
# BYTE-EXACT; the REAL release job's `if`/`needs` lines with an echo body (no publish, no write token).
# Fault injection only touches the trivial jobs (focused-fail / skip-scanner).
import re, sys, subprocess
src = subprocess.check_output(["git", "show", "gate-1125:.github/workflows/build.yml"], text=True)
i = src.index("\njobs:\n") + 7
head, body = src[:i], src[i:]
parts = [p for p in re.split(r"(?m)^(?=  [a-z0-9-]+:\s*$)", body) if p.strip()]
d = {re.match(r"  ([a-z0-9-]+):", p).group(1): p for p in parts}
head = head.replace("      restage:\n", "      force:\n        description: GATE-PROOF fault injection\n        type: choice\n        options: [none, focused-fail, skip-scanner]\n        default: none\n      restage:\n", 1)
head = head.replace("name: Build", "name: Build", 1)
out = head
for job in ["android", "helper", "helper-e2e", "scanner-test", "focused-ui", "screenshots"]:
    v = d[job]
    iff = re.search(r"(?m)^    if: (.*)$", v).group(1)
    need = re.search(r"(?m)^    needs: .*$", v)
    if job == "scanner-test":
        iff = "inputs.force != 'skip-scanner' && (" + iff + ")"
    out += f"  {job}:\n    if: {iff}\n" + (need.group(0) + "\n" if need else "") + "    runs-on: ubuntu-latest\n    steps:\n"
    if job == "focused-ui":
        out += "      - name: forced failure\n        if: inputs.force == 'focused-fail'\n        run: echo forced focused-ui failure; exit 1\n"
    out += f"      - run: echo trivial {job} ok\n"
out += d["release-gate"]  # BYTE-EXACT real gate job
rel = d["release"]
rif = re.search(r"(?m)^    if: .*$", rel).group(0)
rneed = re.search(r"(?m)^    needs: .*$", rel).group(0)
out += "  release:\n" + rif + "\n" + rneed + "\n    runs-on: ubuntu-latest\n    permissions:\n      contents: read\n    steps:\n      - run: echo \"DRY RELEASE JOB RAN - gate passed. Nothing published.\"\n"
open(".github/workflows/build.yml", "w").write(out)
# verify byte-exactness
new = open(".github/workflows/build.yml").read()
assert d["release-gate"] in new, "release-gate block not byte-exact"
assert rif in new and rneed in new
print("gateproof built; release-gate block byte-exact; release if/needs copied")
