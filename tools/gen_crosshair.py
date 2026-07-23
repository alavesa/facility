#!/usr/bin/env python3
"""Interact-crosshair highlight glyph for the Facility plugin.

A small yellow corner-bracket frame drawn around the crosshair when the player
looks at something right-clickable (chest, barrel, door, stash interaction box...).
Sent on the action bar in the facility:crosshair font; the glyph sits in a tall
transparent canvas and a big ascent (LIFT) raises it from the action-bar line up
to the middle of the screen where the crosshair is.

  LIFT - raise the frame to the crosshair. Tune if it's too high/low.

Writes:
  resource-pack/assets/facility/textures/font/crosshair_hl.png
  resource-pack/assets/facility/font/crosshair.json
"""
import json, os, struct, zlib

LIFT = 92
CANVAS_H = 100
W = 15
YEL = (255, 224, 92, 255)
SHA = (0, 0, 0, 150)


class C:
    def __init__(s, w, h): s.w, s.h, s.px = w, h, [[(0,0,0,0)]*w for _ in range(h)]
    def set(s, x, y, c):
        if 0 <= x < s.w and 0 <= y < s.h: s.px[y][x] = c
    def png(s, p):
        rows = b"".join(b"\x00" + b"".join(bytes(px) for px in line) for line in s.px)
        def ch(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t+d))
        data = b"\x89PNG\r\n\x1a\n" + ch(b"IHDR", struct.pack(">IIBBBBB", s.w, s.h, 8, 6, 0,0,0)) \
               + ch(b"IDAT", zlib.compress(rows,9)) + ch(b"IEND", b"")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p,"wb").write(data); print(f"{p} ({s.w}x{s.h})")


c = C(W, CANVAS_H)
# four L-shaped corner brackets of a 14x14 frame in the top of the canvas
corners = [(0,0,1,1),(1,0,0,1),(13,0,-1,1),(12,0,0,1),
           (0,13,1,-1),(1,13,0,-1),(13,13,-1,-1),(12,13,0,-1)]
def bracket(cx, cy, dx, dy):
    for k in range(4):
        if dx: c.set(cx+dx*k, cy, YEL)
        if dy: c.set(cx, cy+dy*k, YEL)
for (cx,cy,dx,dy) in [(0,0,1,1),(13,0,-1,1),(0,13,1,-1),(13,13,-1,-1)]:
    bracket(cx,cy,dx,dy)

root = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets", "facility")
c.png(os.path.join(root, "textures", "font", "crosshair_hl.png"))
font = {"providers": [{"type":"bitmap","file":"facility:font/crosshair_hl.png",
        "ascent": LIFT, "height": CANVAS_H, "chars": [""]}]}
fp = os.path.join(root, "font", "crosshair.json")
os.makedirs(os.path.dirname(fp), exist_ok=True)
json.dump(font, open(fp,"w"), indent=2, ensure_ascii=False); open(fp,"a").write("\n")
print(fp); print("crosshair done")
