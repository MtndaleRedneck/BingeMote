#!/usr/bin/env python3
"""Compare named regions of two PNGs using only the stdlib (CI runners have no
PIL). Usage: pixel_region_compare.py BEFORE.png AFTER.png BOUNDS.txt
BOUNDS.txt lines: "<name> L T R B" in pixel coordinates. Fails loudly (exit 1)
when a region differs in more than 1% of its pixels (channel delta > 16) or
any input is unreadable - a rig defect must never read as a pass."""
import struct, sys, zlib

def read_png(path):
    data = open(path, 'rb').read()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError(f"{path}: not a PNG")
    pos, w, h, ch, idat = 8, None, None, None, bytearray()
    while pos < len(data):
        (ln,) = struct.unpack('>I', data[pos:pos+4])
        typ, chunk = data[pos+4:pos+8], data[pos+8:pos+8+ln]
        pos += 12 + ln
        if typ == b'IHDR':
            w, h, bitd, ctype, _c, _f, inter = struct.unpack('>IIBBBBB', chunk)
            if bitd != 8 or inter != 0:
                raise ValueError(f"{path}: unsupported bit depth {bitd} / interlace {inter}")
            ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
        elif typ == b'IDAT':
            idat += chunk
        elif typ == b'IEND':
            break
    raw = zlib.decompress(bytes(idat))
    stride = w * ch
    px, prev, p = bytearray(w * h * ch), bytearray(stride), 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if f == 1:
            for i in range(ch, stride): line[i] = (line[i] + line[i-ch]) & 255
        elif f == 2:
            for i in range(stride): line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                b, c = prev[i], (prev[i-ch] if i >= ch else 0)
                q = a + b - c
                pa, pb, pc = abs(q-a), abs(q-b), abs(q-c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        elif f != 0:
            raise ValueError(f"{path}: bad filter {f}")
        px[y*stride:(y+1)*stride] = line
        prev = line
    return w, h, ch, px

def main():
    before_path, after_path, bounds_path = sys.argv[1], sys.argv[2], sys.argv[3]
    bw, bh, bch, bimg = read_png(before_path)
    aw, ah, ach, aimg = read_png(after_path)
    if (bw, bh, bch) != (aw, ah, ach):
        print(f"COMPARE FAIL: geometry differs {bw}x{bh}x{bch} vs {aw}x{ah}x{ach}")
        return 1
    rc = 0
    for line in open(bounds_path):
        parts = line.split()
        if len(parts) != 5:
            continue
        name, l, t, r, b = parts[0], *map(int, parts[1:5])
        if r > bw or b > bh or l < 0 or t < 0 or r <= l or b <= t:
            print(f"COMPARE FAIL: region '{name}' ({l},{t},{r},{b}) outside {bw}x{bh}")
            rc = 1
            continue
        total = diff = 0
        for y in range(t, b):
            row = y * bw * bch
            for x in range(l, r):
                o = row + x * bch
                total += 1
                for c in range(3):
                    if abs(bimg[o+c] - aimg[o+c]) > 16:
                        diff += 1
                        break
        pct = (diff * 100.0 / total) if total else 100.0
        status = "OK" if pct <= 1.0 else "FAIL"
        print(f"region {name}: {diff}/{total} pixels differ ({pct:.2f}%) -> {status}")
        if pct > 1.0:
            rc = 1
    return rc

if __name__ == '__main__':
    sys.exit(main())
