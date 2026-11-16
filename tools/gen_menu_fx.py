#!/usr/bin/env python3
"""Post-effect variants for the Facility MAIN MENU look and its transitions.

A post effect's uniform values are fixed in its JSON, so every look and every
animation frame is its own file. They all run shaders/post/menu_crt.fsh and
differ only in the MenuConfig values. Produced here (facility: namespace):

  post_effect/menu_<look>.json             the idle look behind the menu
  post_effect/menu_<look>_glitch.json      a static burst (dialog navigation)
  post_effect/menu_<look>_out_01..NN.json  glitch + CRT power-off, ends black
  post_effect/menu_<look>_fade_01..NN.json plain fade to black, ends black
  post_effect/menu_black.json              black (held while the player teleports)
  post_effect/menu_reveal_01..NN.json      black -> the untouched game picture

The intro plays the out/fade frames backwards. MenuAudioAndVisualPacketHandler
steps through them; FRAMES and the names MUST match that class. To add a look,
add it to LOOKS, re-run, and set menu.shader.look to its name.

Pure stdlib. Run from the repo root:  python3 tools/gen_menu_fx.py
"""
import glob, json, os

FRAMES = 12
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                   "resource-pack", "assets", "facility", "post_effect")

# name: tint rgb, grade (0 = untouched picture, 1 = fully tinted), gain, noise, scanlines, aberration, vignette
LOOKS = {
    "green": ((0.45, 1.00, 0.55), 0.85, 1.15, 0.06, 0.25, 0.0015, 0.70),   # green phosphor terminal
    "amber": ((1.00, 0.72, 0.28), 0.85, 1.15, 0.06, 0.25, 0.0015, 0.70),   # amber phosphor terminal
    "nvg":   ((0.30, 1.00, 0.35), 1.00, 2.00, 0.12, 0.12, 0.0,    0.85),   # night vision: amplified, grainy
    "mono":  ((0.90, 0.95, 1.00), 0.90, 1.05, 0.08, 0.30, 0.0020, 0.60),   # cold black-and-white CCTV
}
PLAIN = ((1.0, 1.0, 1.0), 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)                    # the untouched game picture


def smoothstep(a, b, x):
    x = min(1.0, max(0.0, (x - a) / (b - a)))
    return x * x * (3 - 2 * x)


def effect(look, glitch=0.0, collapse=0.0, fade=0.0):
    tint, grade, gain, noise, scan, aberration, vignette = look
    floats = [("Gain", gain), ("Noise", noise), ("Scan", scan), ("Aberration", aberration),
              ("Vignette", vignette), ("Glitch", glitch), ("Collapse", collapse), ("Fade", fade)]
    config = [{"name": "Tint", "type": "vec4", "value": [*tint, grade]}]
    config += [{"name": n, "type": "float", "value": round(v, 4)} for n, v in floats]
    return {
        "targets": {"swap": {}},
        "passes": [
            {
                "vertex_shader": "minecraft:core/screenquad",
                "fragment_shader": "facility:post/menu_crt",
                "inputs": [{"sampler_name": "In", "target": "minecraft:main"}],
                "output": "swap",
                "uniforms": {"MenuConfig": config},
            },
            {
                "vertex_shader": "minecraft:core/screenquad",
                "fragment_shader": "minecraft:post/blit",
                "inputs": [{"sampler_name": "In", "target": "swap"}],
                "uniforms": {"BlitConfig": [
                    {"name": "ColorModulate", "type": "vec4", "value": [1.0, 1.0, 1.0, 1.0]}]},
                "output": "minecraft:main",
            },
        ],
    }


def write(name, data):
    with open(os.path.join(OUT, name + ".json"), "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


os.makedirs(OUT, exist_ok=True)
for old in glob.glob(os.path.join(OUT, "menu_*.json")):
    os.remove(old)

for name, look in LOOKS.items():
    write(f"menu_{name}", effect(look))
    write(f"menu_{name}_glitch", effect(look, glitch=0.8))
    for i in range(1, FRAMES + 1):
        p = i / FRAMES
        last = i == FRAMES
        # static builds up, then the tube switches off; the last frame is plain black
        write(f"menu_{name}_out_{i:02d}", effect(look, glitch=min(1.0, p * 2.5),
              collapse=smoothstep(0.4, 1.0, p), fade=1.0 if last else 0.0))
        write(f"menu_{name}_fade_{i:02d}", effect(look, fade=smoothstep(0.0, 1.0, p)))

write("menu_black", effect(PLAIN, fade=1.0))
for i in range(1, FRAMES + 1):
    write(f"menu_reveal_{i:02d}", effect(PLAIN, fade=1.0 - smoothstep(0.0, 1.0, i / FRAMES)))

print(f"wrote {len(glob.glob(os.path.join(OUT, 'menu_*.json')))} post effects to {os.path.normpath(OUT)}")
