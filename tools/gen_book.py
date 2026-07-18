#!/usr/bin/env python3
"""Book-GUI menu artwork for the Facility lobby (DonutSMP /settings style).

The main menu is a WRITTEN BOOK opened with player.openBook(), not a chest.
Each button is a real clickable text component (run_command). Its appearance is
a bitmap FONT GLYPH - a full-width gray bar with the label baked in - so there
are no item slots and no chest chrome. On hover the component's tooltip shows a
WHITE-OUTLINED variant of the same bar; a transparent tooltip texture makes that
read as an overlay lighting up the button (exactly like the reference).

Baking the label into each fixed button's glyph sidesteps blind label-centering:
one glyph = one finished button. Dynamic team buttons use plain clickable text.

Generates (pure stdlib, no PIL):
  resource-pack/assets/facility/textures/font/btn_play.png  (+ _h hover)
  resource-pack/assets/facility/textures/font/btn_team.png  (+ _h hover)
  resource-pack/assets/facility/font/book.json              (glyphs + spacing)
  resource-pack/assets/minecraft/textures/gui/sprites/tooltip/background.png (transparent)
  resource-pack/assets/minecraft/textures/gui/sprites/tooltip/frame.png      (transparent)

Run from the repo root:  python3 tools/gen_book.py
"""
import json, os, struct, zlib

# Button palette tuned to the reference: light gray face, beveled, dark border,
# white label; the hover variant adds a bright white outline.
FACE   = (108, 108, 108, 255)
TOP    = (150, 150, 150, 255)
BOT    = (60, 60, 60, 255)
EDGE   = (24, 24, 24, 255)
LABEL  = (240, 240, 240, 255)
OUTLINE = (255, 255, 255, 255)

# 3x5 font for the baked labels.
FONT3X5 = {
    "A": ["111","101","111","101","101"], "B": ["110","101","110","101","110"],
    "C": ["111","100","100","100","111"], "D": ["110","101","101","101","110"],
    "E": ["111","100","111","100","111"], "L": ["100","100","100","100","111"],
    "M": ["101","111","111","101","101"], "P": ["111","101","111","100","100"],
    "S": ["111","100","111","001","111"], "T": ["111","010","010","010","010"],
    "Y": ["101","101","010","010","010"], " ": ["000","000","000","000","000"],
}

BAR_W, BAR_H = 110, 18     # a single button bar


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[(0, 0, 0, 0)] * w for _ in range(h)]

    def fill(self, x, y, w, h, color):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                if 0 <= xx < self.w and 0 <= yy < self.h:
                    self.px[yy][xx] = color

    def text(self, x, y, s, color, scale=2):
        for ch in s:
            rows = FONT3X5.get(ch.upper(), FONT3X5[" "])
            for ry, row in enumerate(rows):
                for rx, bit in enumerate(row):
                    if bit == "1":
                        self.fill(x + rx * scale, y + ry * scale, scale, scale, color)
            x += 4 * scale
        return x

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


def bar(label, hover):
    c = Canvas(BAR_W, BAR_H)
    c.fill(0, 0, BAR_W, BAR_H, FACE)
    c.fill(0, 0, BAR_W, 1, TOP)
    c.fill(0, 0, 1, BAR_H, TOP)
    c.fill(0, BAR_H - 1, BAR_W, 1, BOT)
    c.fill(BAR_W - 1, 0, 1, BAR_H, BOT)
    c.fill(0, 0, BAR_W, 1, EDGE); c.fill(0, BAR_H - 1, BAR_W, 1, EDGE)
    c.fill(0, 0, 1, BAR_H, EDGE); c.fill(BAR_W - 1, 0, 1, BAR_H, EDGE)
    if hover:
        # bright inner outline, like the selected button in the reference
        c.fill(1, 1, BAR_W - 2, 1, OUTLINE); c.fill(1, BAR_H - 2, BAR_W - 2, 1, OUTLINE)
        c.fill(1, 1, 1, BAR_H - 2, OUTLINE); c.fill(BAR_W - 2, 1, 1, BAR_H - 2, OUTLINE)
    lw = len(label) * 4 * 2 - 2
    c.text((BAR_W - lw) // 2, (BAR_H - 10) // 2, label, LABEL, scale=2)
    return c


base = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets", "facility")
tex = os.path.join(base, "textures", "font")

bar("PLAY", False).png(os.path.join(tex, "btn_play.png"))
bar("PLAY", True).png(os.path.join(tex, "btn_play_h.png"))
bar("SELECT TEAM", False).png(os.path.join(tex, "btn_team.png"))
bar("SELECT TEAM", True).png(os.path.join(tex, "btn_team_h.png"))

# transparent tooltip so the hover glyph reads as an overlay (GLOBAL - removes
# the box behind every tooltip). 1x1 fully transparent, 9-sliced by the game.
mc = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets",
                  "minecraft", "textures", "gui", "sprites", "tooltip")
Canvas(1, 1).png(os.path.join(mc, "background.png"))
Canvas(1, 1).png(os.path.join(mc, "frame.png"))

# Font: bitmap glyphs for the four bars + a couple of spacing chars.
#   U+E010 play, U+E011 play-hover, U+E012 team, U+E013 team-hover
#   U+F810 = +2 nudge, U+F811 = big rewind for hover-overlay positioning
GLYPHS = [
    ("btn_play.png", chr(0xE010)), ("btn_play_h.png", chr(0xE011)),
    ("btn_team.png", chr(0xE012)), ("btn_team_h.png", chr(0xE013)),
]
providers = [{"type": "space", "advances": {chr(0xF810): 2, chr(0xF811): -112}}]
for f, ch in GLYPHS:
    providers.append({"type": "bitmap", "file": "facility:font/" + f,
                      "ascent": 13, "height": BAR_H, "chars": [ch]})
os.makedirs(os.path.join(base, "font"), exist_ok=True)
with open(os.path.join(base, "font", "book.json"), "w") as f:
    json.dump({"providers": providers}, f, indent=2, ensure_ascii=False)
print(os.path.join(base, "font", "book.json"))
