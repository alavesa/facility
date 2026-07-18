#!/usr/bin/env python3
"""Custom menu artwork for the Facility lobby GUIs.

Server plugins can't draw real buttons on the client, so the whole menu look
is a resource-pack trick (the same one the SCiPNET terminal uses, see
Terminal/tools/gen_gui.py):

  1. A negative-space advance rewinds the inventory title's cursor to the GUI
     top-left corner, then ONE big bitmap glyph (ascent 13 -> top edge at the
     container's 0,0) paints a full 176-wide panel over the vanilla chest.
  2. The panel bakes the SCREENSHOT look: a dark facility screen with
     full-width gray BUTTON BARS and their labels (PLAY, SELECT TEAM).
  3. Clickable but INVISIBLE items (facility:blank model) fill every slot under
     a bar, so the whole bar is clickable while only the painted bar shows.
  4. Item icons render above the title text, so team icons stay visible on top.

Two backgrounds are produced - the main menu and the team selector - each its
own bitmap glyph in font/menu.json, plus a transparent item model for the
invisible click targets.

Pure stdlib - no PIL. Run from the repo root:  python3 tools/gen_menu.py
"""
import json, os, struct, zlib

# ---- palette: dark facility screen + light-gray vanilla-style buttons -------
PANEL   = (10, 13, 17, 255)     # near-black screen
EDGE    = (0, 150, 140, 255)    # cyan frame
INNER   = (0, 66, 62, 255)      # dim inner frame
HEADER  = (120, 255, 170, 255)  # CRT green header
AMBER   = (255, 176, 64, 255)   # warning tick
SCAN    = (14, 20, 24, 255)     # faint scanline

BTN_FACE = (99, 99, 99, 255)    # gray button fill (like the screenshot)
BTN_TOP  = (146, 146, 146, 255) # top highlight
BTN_BOT  = (48, 48, 48, 255)    # bottom shadow
BTN_EDGE = (18, 18, 18, 255)    # dark border
LABEL    = (238, 238, 238, 255) # button text
PLAY_AC  = (90, 220, 120, 255)  # green accent for PLAY
TEAM_AC  = (90, 190, 230, 255)  # cyan accent for SELECT TEAM
WELL_BG  = (16, 22, 26, 255)    # team-slot well
WELL_ED  = (30, 72, 60, 255)    # well edge

# ---- a compact 3x5 pixel font (only what the panels bake) -------------------
FONT3X5 = {
    "A": ["111","101","111","101","101"], "B": ["110","101","110","101","110"],
    "C": ["111","100","100","100","111"], "D": ["110","101","101","101","110"],
    "E": ["111","100","111","100","111"], "F": ["111","100","111","100","100"],
    "G": ["111","100","101","101","111"], "H": ["101","101","111","101","101"],
    "I": ["111","010","010","010","111"], "K": ["101","110","100","110","101"],
    "L": ["100","100","100","100","111"], "M": ["101","111","111","101","101"],
    "N": ["101","111","111","111","101"], "O": ["111","101","101","101","111"],
    "P": ["111","101","111","100","100"], "R": ["110","101","110","101","101"],
    "S": ["111","100","111","001","111"], "T": ["111","010","010","010","010"],
    "U": ["101","101","101","101","111"], "V": ["101","101","101","101","010"],
    "Y": ["101","101","010","010","010"], "1": ["010","110","010","010","111"],
    "9": ["111","101","111","001","111"], "0": ["111","101","101","101","111"],
    "/": ["001","001","010","100","100"], "-": ["000","000","111","000","000"],
    " ": ["000","000","000","000","000"],
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
            rows = FONT3X5.get(ch.upper(), FONT3X5[" "])
            for ry, row in enumerate(rows):
                for rx, bit in enumerate(row):
                    if bit == "1":
                        self.fill(x + rx * scale, y + ry * scale, scale, scale, color)
            x += 4 * scale
        return x

    def text_centered(self, cx, y, s, color, scale=2):
        width = len(s) * 4 * scale - scale
        self.text(cx - width // 2, y, s, color, scale)

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


# Chest-54 content geometry: 9 columns x 6 rows of 18px cells; the top-left
# cell's corner sits at (7, 17) in the 176x222 GUI texture.
CELL = 18
GRID_X, GRID_Y = 7, 17
CONTENT_W = 9 * CELL          # 162


def screen(header):
    """A fresh dark facility screen with the header band drawn."""
    c = Canvas(176, 222)
    c.fill(0, 0, c.w, c.h, PANEL)
    for y in range(2, 132, 2):                    # scanlines over the chest area
        c.fill(1, y, c.w - 2, 1, SCAN)
    c.box(0, 0, c.w, c.h, EDGE)
    c.box(1, 1, c.w - 2, c.h - 2, INNER)
    c.fill(3, 3, c.w - 6, 11, (10, 30, 28, 255))  # header band
    c.fill(3, 14, c.w - 6, 1, INNER)
    c.text(8, 4, header, HEADER, scale=2)
    c.fill(c.w - 10, 5, 4, 4, AMBER)              # amber status tick
    return c


def button_bar(c, row, label, accent):
    """A full-width beveled gray button bar (DonutSMP /settings look): light top
    + left edge, dark bottom + right edge, gray face, thin colour accent on the
    left, white centered label. 16px tall inside the 18px row -> a small gap
    between stacked buttons."""
    x = GRID_X
    y = GRID_Y + row * CELL + 1
    w, h = CONTENT_W, CELL - 2                     # 162 x 16, 1px gap top/bottom
    c.fill(x, y, w, h, BTN_FACE)
    c.fill(x, y, w, 1, BTN_TOP)                    # top highlight
    c.fill(x, y, 1, h, BTN_TOP)                    # left highlight
    c.fill(x, y + h - 1, w, 1, BTN_BOT)           # bottom shadow
    c.fill(x + w - 1, y, 1, h, BTN_BOT)           # right shadow
    c.box(x, y, w, h, BTN_EDGE)                    # dark outline
    c.fill(x + 2, y + 2, 2, h - 4, accent)        # thin left accent
    c.text_centered(x + w // 2, y + (h - 10) // 2, label, LABEL, scale=2)


def team_well(c, row, col):
    """One 18px slot well for a team icon."""
    x = GRID_X + col * CELL
    y = GRID_Y + row * CELL
    c.fill(x, y, CELL, CELL, WELL_BG)
    c.box(x, y, CELL, CELL, WELL_ED)


base = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets", "facility")
tex_font = os.path.join(base, "textures", "font")

# ---- main menu: PLAY + SELECT TEAM stacked as a tidy list -------------------
m = screen("SITE-19 // MAIN MENU")
button_bar(m, 1, "PLAY", PLAY_AC)
button_bar(m, 2, "SELECT TEAM", TEAM_AC)
m.text_centered(88, GRID_Y + 5 * CELL + 4, "AWAITING ENTRY", (70, 110, 100, 255), scale=2)
m.png(os.path.join(tex_font, "menu_bg.png"))

# ---- team selector: header + two rows of icon wells (slots 10.. / 19..) -----
t = screen("SITE-19 // SELECT TEAM")
for col in range(1, 8):
    team_well(t, 1, col)
    team_well(t, 3, col)
t.text_centered(88, GRID_Y + 5 * CELL + 4, "CLICK A TEAM TO DEPLOY", (70, 110, 100, 255), scale=2)
t.png(os.path.join(tex_font, "teams_bg.png"))

# ---- transparent 16x16 for the invisible click targets ---------------------
blank = Canvas(16, 16)          # all pixels stay (0,0,0,0)
blank.png(os.path.join(base, "textures", "item", "blank.png"))

# ---- models: a fully transparent item model --------------------------------
model_dir = os.path.join(base, "models", "item")
os.makedirs(model_dir, exist_ok=True)
with open(os.path.join(model_dir, "blank.json"), "w") as f:
    json.dump({"parent": "minecraft:item/generated",
               "textures": {"layer0": "facility:item/blank"}}, f, indent=2)
print(os.path.join(model_dir, "blank.json"))

# ---- the font carrying both panels -----------------------------------------
#  = space advance -8 (rewind past the title's first character cell).
#  = main-menu panel glyph,  = team-selector panel glyph.
font = {
    "providers": [
        {"type": "space", "advances": {"": -8}},
        {"type": "bitmap", "file": "facility:font/menu_bg.png",
         "ascent": 13, "height": 222, "chars": [""]},
        {"type": "bitmap", "file": "facility:font/teams_bg.png",
         "ascent": 13, "height": 222, "chars": [""]},
    ]
}
os.makedirs(os.path.join(base, "font"), exist_ok=True)
with open(os.path.join(base, "font", "menu.json"), "w") as f:
    json.dump(font, f, indent=2, ensure_ascii=False)
print(os.path.join(base, "font", "menu.json"))
