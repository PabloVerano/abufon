"""Remove the phone icon baked into the centre of a button PNG.

The designer's call/retry buttons have a white handset in the middle, exactly where
the label must go. The button face is a vertical gradient (colour depends only on y),
so each icon row is repainted with the face colour from the same row just left of it.
Usage: python strip_button_icon.py in.png out.png
"""
import sys
from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
im = Image.open(src).convert("RGBA")
w, h = im.size
px = im.load()

# Search only the middle of the face, away from the white outline at the edges.
x0, x1, y0, y1 = int(w * .2), int(w * .8), int(h * .15), int(h * .85)
icon = [(x, y) for y in range(y0, y1) for x in range(x0, x1)
        if px[x, y][3] > 0 and min(px[x, y][:3]) > 170]          # white-ish = icon
if not icon:
    sys.exit(f"no icon found in {src}")
ix0 = min(p[0] for p in icon) - 6; ix1 = max(p[0] for p in icon) + 6
iy0 = min(p[1] for p in icon) - 6; iy1 = max(p[1] for p in icon) + 6
sample_x = ix0 - 8
for y in range(iy0, iy1 + 1):
    face = px[sample_x, y]
    for x in range(ix0, ix1 + 1):
        px[x, y] = face
im.save(dst)
print(f"{src}: icon box x{ix0}-{ix1} y{iy0}-{iy1}, sampled column x={sample_x}")
