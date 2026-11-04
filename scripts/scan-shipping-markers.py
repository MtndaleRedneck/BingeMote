#!/usr/bin/env python3
"""Binary-level absence proof: the SHIPPING helper exe must contain no CI-hook
markers. Scans raw bytes for ASCII and UTF-16LE encodings (.NET single-file keeps
managed strings UTF-16LE). A positive control proves the scan itself works on this
artifact. Exits 1 on any marker hit."""
import sys

MARKERS = [
    "ci/hold", "KEEPAWAKE_HOOK", "PAIR_CODE_HOOK", "WINDOW_SHOT_HOOK",
    "hold-for-ci", "__ci_hold", "ci_hold", "KEEPAWAKE",
    "--osd-shot", "BINGEMOTE_OSD_SHOT", "BINGEMOTE_OSD_SHOT_MUTED",
    "CaptureShot", "RenderPng", "osd-muted",
    "--osd-show", "BINGEMOTE_OSD_SHOW_SECONDS", "BINGEMOTE_OSD_SHOW_MUTED",
    "ShowForCapture",
]
POSITIVE_CONTROL = "BingeMote"

def variants(m: str):
    return (m.encode(), m.encode("utf-16-le"))

def main(path: str) -> int:
    blob = open(path, "rb").read()
    hits = [m for m in MARKERS if any(v in blob for v in variants(m))]
    ok = all(v in blob for v in variants(POSITIVE_CONTROL))
    if not ok:
        print(f"POSITIVE CONTROL FAILED: {POSITIVE_CONTROL!r} not found - scan proves nothing")
        return 2
    if hits:
        print(f"MARKERS FOUND in shipping exe: {hits}")
        return 1
    print(f"PASS: {len(MARKERS)} markers absent (positive control present) in {path} ({len(blob)} bytes)")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
