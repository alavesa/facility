#!/usr/bin/env python3
"""Background artwork for the Facility MAIN MENU dialog.

A native /dialog can't take an image directly, so the background is the same
resource-pack FONT-GLYPH trick the SCiPNET terminal uses for its chest GUIs
(see Terminal/tools/gen_gui.py): a bitmap glyph is dropped into the dialog
BODY as a text component in a custom font, and a negative-space advance nudges
it back so it sits under the buttons instead of pushing them down.

Produced here (all under the facility: namespace - NEVER assets/minecraft/...):
  assets/facility/textures/font/dialog_bg.png   the panel bitmap
  assets/facility/font/dialog.json              the font:
      - a "space" provider giving two negative advances (rewind + a tuning nudge)
      - a "bitmap" provider mapping ONE glyph char to the panel PNG

DialogMenu references this as font "facility:dialog" and emits the glyph char
with the leading rewind space. The codepoints MUST match DialogMenu.java:
      REWIND glyph      (big negative advance to pull the panel left)
      NUDGE  glyph      (small negative advance for fine tuning)
      PANEL  glyph      (the bitmap itself)

Pure stdlib - no PIL. Run from the repo root:  python3 tools/gen_dialog_bg.py

NOTE: dialog-body pixel origin is not knowable server-side, so the negative
advances (REWIND_ADVANCE / NUDGE_ADVANCE) and ASCENT are a first pass and will
need in-game screenshot tuning. They are named constants here AND in
DialogMenu.java so tuning is a one-line change on each side.
"""
import json, os, struct, zlib

# ---- codepoints (keep in sync with DialogMenu.java) -------------------------
REWIND_CHAR = ""   # big negative advance
NUDGE_CHAR  = ""   # small negative advance
PANEL_CHAR  = ""   # the panel bitmap glyph

# ---- blind-tuning constants (need in-game screenshot tuning) ----------------
PANEL_W, PANEL_H = 256, 160      # the panel bitmap size in px
ASCENT           = 8             # baseline offset; raises/lowers the panel
REWIND_ADVANCE   = -PANEL_W - 4  # pull the cursor back the panel width (+slack)
NUDGE_ADVANCE    = -1            # 1px fine nudge (repeat the char to shift more)

# ---- palette: dark SITE-19 facility panel -----------------------------------
PANEL  = (10, 13, 17, 235)      # near-black screen, slightly translucent
EDGE   = (0, 150, 140, 255)     # cyan frame
INNER  = (0, 66, 62, 255)       # dim inner frame
HEADER = (120, 255, 170, 255)   # CRT green header text
AMBER  = (255, 176, 64, 255)    # hazard tick
SCAN   = (14, 20, 24, 90)       # faint scanline
HAZ_A  = (40, 40, 12, 255)      # hazard stripe dark
HAZ_B  = (120, 100, 20, 255)    # hazard stripe amber


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[(0, 0, 0, 0)] * w for _ in range(h)]

    def fill(self, x, y, w, h, color):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                if 0 <= xx < self.w and 0 <= yy < self.h:
                    self.px[yy][xx] = color

    def box(self, x, y, w, h, color):
        self.fill(x, y, w, 1, color); self.fill(x, y + h - 1, w, 1, color)
        self.fill(x, y, 1, h, color); self.fill(x + w - 1, y, 1, h, color)

    def png(self, path):
        rows = b"".join(b"\x00" + b"".join(bytes(p) for p in line) for line in self.px)
        def chunk(tag, data):
            return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))
        data = (b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
        print(f"{path} ({self.w}x{self.h})")


def build_panel():
    c = Canvas(PANEL_W, PANEL_H)
    c.fill(0, 0, c.w, c.h, PANEL)
    for y in range(2, c.h - 2, 2):                 # scanlines over the whole panel
        c.fill(2, y, c.w - 4, 1, SCAN)
    c.box(0, 0, c.w, c.h, EDGE)                     # cyan outer frame
    c.box(2, 2, c.w - 4, c.h - 4, INNER)           # dim inner frame
    c.fill(4, 4, c.w - 8, 10, (10, 30, 28, 255))   # header band
    # hazard stripe along the bottom edge
    for x in range(6, c.w - 6, 8):
        c.fill(x, c.h - 8, 4, 4, HAZ_B)
        c.fill(x + 4, c.h - 8, 4, 4, HAZ_A)
    # a couple of corner ticks for the "facility screen" read
    c.fill(6, 6, 12, 2, HEADER)
    c.fill(c.w - 18, 6, 12, 2, AMBER)
    return c


out = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets", "facility")
tex = os.path.join(out, "textures", "font")

build_panel().png(os.path.join(tex, "dialog_bg.png"))

font = {
    "providers": [
        {"type": "space", "advances": {REWIND_CHAR: REWIND_ADVANCE, NUDGE_CHAR: NUDGE_ADVANCE}},
        {"type": "bitmap", "file": "facility:font/dialog_bg.png",
         "ascent": ASCENT, "height": PANEL_H, "chars": [PANEL_CHAR]},
    ]
}
os.makedirs(os.path.join(out, "font"), exist_ok=True)
with open(os.path.join(out, "font", "dialog.json"), "w") as f:
    json.dump(font, f, indent=2, ensure_ascii=False)
print(os.path.join(out, "font", "dialog.json"))
print("codepoints: REWIND=U+%04X NUDGE=U+%04X PANEL=U+%04X"
      % (ord(REWIND_CHAR), ord(NUDGE_CHAR), ord(PANEL_CHAR)))
