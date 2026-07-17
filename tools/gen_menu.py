#!/usr/bin/env python3
"""Full-screen main-menu background for the Facility lobby GUI.

The background is drawn INTO THE INVENTORY TITLE as a font glyph, the same
trick the SCiPNET terminal screens use (see Terminal/tools/gen_gui.py):

  1. A negative-space advance rewinds the text cursor to the GUI's top-left
     corner (advance -8 pulls back past the title's first character cell).
  2. One big bitmap glyph with ascent 13 puts its TOP EDGE exactly at the
     container's (0,0), painting a full 176-wide panel over the vanilla chrome.
  3. Item icons render at a higher z-level than title text, so the menu buttons
     stay visible on top of the painted background.

mainmenu.yml's `title:` embeds:  "<neg-8><neg-176 pull><glyph>SITE-19 // MAIN MENU"
so the glyph is drawn first (as the background), then the readable header text.

Pure stdlib - no PIL. Writes:
  resource-pack/assets/facility/textures/font/menu_bg.png   (176 x 222)
  resource-pack/assets/facility/font/menu.json              (space + bitmap providers)

Run from the repo root:  python3 tools/gen_menu.py
"""
import json, os, struct, zlib

# SCP facility terminal palette - dark with cyan/green phosphor accents.
BG      = (6, 10, 12, 255)      # near-black panel
BORDER  = (0, 150, 140, 255)    # cyan frame
INNER   = (0, 70, 66, 255)      # dim inner frame
HEADER  = (120, 255, 170, 255)  # CRT green header text
AMBER   = (255, 176, 64, 255)   # warning amber accent
SLOT_BG = (12, 20, 18, 255)     # slot well
SLOT_ED = (28, 70, 56, 255)     # slot outline
SCAN    = (10, 26, 24, 255)     # faint scanline

FONT3X5 = {
    "S": ["111","100","111","001","111"], "I": ["111","010","010","010","111"],
    "T": ["111","010","010","010","010"], "E": ["111","100","111","100","111"],
    "1": ["010","110","010","010","111"], "9": ["111","101","111","001","111"],
    "M": ["101","111","111","101","101"], "A": ["111","101","111","101","101"],
    "N": ["101","111","111","111","101"], "U": ["101","101","101","101","111"],
    "/": ["001","001","010","100","100"], " ": ["000","000","000","000","000"],
    "-": ["000","000","111","000","000"],
}

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

    def text(self, x, y, s, color, scale=2):
        for ch in s:
            rows = FONT3X5.get(ch, FONT3X5[" "])
            for ry, row in enumerate(rows):
                for rx, bit in enumerate(row):
                    if bit == "1":
                        self.fill(x + rx * scale, y + ry * scale, scale, scale, color)
            x += 4 * scale

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


out = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets", "facility")
tex = os.path.join(out, "textures", "font")

# 6-row chest (size 54): 176 wide x 222 tall - the full menu panel.
c = Canvas(176, 222)
c.fill(0, 0, c.w, c.h, BG)
# faint horizontal scanlines for CRT flavour
for y in range(2, c.h, 2):
    c.fill(1, y, c.w - 2, 1, SCAN)
c.box(0, 0, c.w, c.h, BORDER)
c.box(1, 1, c.w - 2, c.h - 2, INNER)
# header band
c.fill(3, 3, c.w - 6, 11, (10, 30, 28, 255))
c.fill(3, 14, c.w - 6, 1, INNER)
c.text(8, 4, "SITE-19 // MAIN MENU", HEADER, scale=2)
# amber status tick, top-right
c.fill(c.w - 10, 5, 4, 4, AMBER)
# the 6x9 slot grid the buttons sit in (top edge at y=17, 18px cells)
for r in range(6):
    for col in range(9):
        x = 7 + col * 18
        y = 17 + r * 18
        c.fill(x, y, 18, 18, SLOT_BG)
        c.box(x, y, 18, 18, SLOT_ED)
# footer divider
c.fill(3, c.h - 6, c.w - 6, 1, INNER)
c.png(os.path.join(tex, "menu_bg.png"))

# The font that carries the overlay. Two negative-space advances:
#   "" = -8   (rewind past the first title character cell)
#   "" = -168 (pull the cursor back to the panel's left edge after glyph)
# and the bitmap glyph "" (ascent 13 -> top edge at container 0,0).
font = {
    "providers": [
        {"type": "space", "advances": {"": -8, "": -168}},
        {"type": "bitmap", "file": "facility:font/menu_bg.png",
         "ascent": 13, "height": 222, "chars": [""]},
    ]
}
os.makedirs(os.path.join(out, "font"), exist_ok=True)
with open(os.path.join(out, "font", "menu.json"), "w") as f:
    json.dump(font, f, indent=2, ensure_ascii=False)
print(os.path.join(out, "font", "menu.json"))
