# Builds the Terrain dash plate (1024x425) from the rider's mockup: live values erased, squashed to the panel,
# bike pasted back unsquashed. Usage: terrain_plate.py <mockup.png> <out.webp> [preview.png]
import sys
from PIL import Image
src = Image.open(sys.argv[1]).convert('RGB')
W, H = src.size                      # 1671x941
TW, TH = 1024, 425
im = src.copy(); px = im.load()

lum = lambda c: c[0] * 0.3 + c[1] * 0.59 + c[2] * 0.11

def erase(x0, y0, x1, y1, color=None):
    """Fill the box per column: blend the row above into the row below, or take the darker one when an
    edge touches a glow (blending would drag the glow across the box). [color] = flat fill."""
    for x in range(x0, x1):
        a = px[x, y0 - 1]; b = px[x, y1]
        flat = color or (min(a, b, key=lum) if abs(lum(a) - lum(b)) > 18 else None)
        for y in range(y0, y1):
            t = (y - y0 + 0.5) / (y1 - y0)
            px[x, y] = flat or tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def erase_h(x0, y0, x1, y1):
    """Like [erase] but blends the column left of the box into the one right of it (panels whose
    top and bottom edges are glows)."""
    for y in range(y0, y1):
        a = px[x0 - 1, y]; b = px[x1, y]
        for x in range(x0, x1):
            t = (x - x0 + 0.5) / (x1 - x0)
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def feather(img, f):
    """Alpha mask fading [img]'s edges over [f] px, so a pasted sprite has no hard rectangle."""
    m = Image.new('L', img.size, 0); mp = m.load()
    for y in range(img.height):
        for x in range(img.width):
            d = min(x, y, img.width - 1 - x, img.height - 1 - y)
            mp[x, y] = 255 if d >= f else int(255 * d / f)
    return m

BOXES = [
    (830, 16, 1110, 62), (1360, 14, 1512, 62), (1530, 14, 1648, 62),          # gps · sats, weather, clock
    (372, 578, 805, 648), (940, 585, 1040, 648), (1170, 585, 1312, 648), (1400, 585, 1600, 650),  # turn, dest, left, eta
        (135, 788, 322, 862),                                                    # ride mode
    (405, 783, 505, 848), (632, 783, 735, 848), (842, 783, 965, 852), (1095, 783, 1298, 850),  # cell texts
    (358, 853, 522, 874), (563, 853, 738, 874), (778, 853, 982, 874), (1028, 853, 1298, 874),  # level bars
    (1392, 788, 1508, 852), (1532, 788, 1655, 852),                          # trip, odo
]
bike = src.crop((22, 515, 332, 735))
DARK = (6, 6, 8)
# Left column inside its glowing border: speed gauge, gear, rpm and bike all go (code draws gauge + bike).
# Right border x by row, traced off the mockup (it bows in toward the bike, then out behind its wheel).
RIGHT = [(100, 322), (185, 320), (290, 317), (305, 315), (380, 301), (440, 290), (500, 281), (515, 282),
         (545, 288), (575, 300), (605, 313), (640, 328), (738, 330)]
def right(y):
    for (y0, x0), (y1, x1) in zip(RIGHT, RIGHT[1:]):
        if y0 <= y <= y1: return x0 + (x1 - x0) * (y - y0) / (y1 - y0)
    return RIGHT[-1][1]
# Left edge: the gauge arc and the bike's tail reach almost to the outer glow (x < 20), the gear/rpm boxes don't.
left = lambda y: 22 if y < 335 or y > 560 else 56
from PIL import ImageDraw, ImageFilter
mask = Image.new('L', src.size, 0); md = ImageDraw.Draw(mask)
for y in range(122, 738): md.line([(left(y), y), (int(right(y)) - 7, y)], fill=255)
im.paste(Image.new('RGB', src.size, DARK), (0, 0), mask.filter(ImageFilter.GaussianBlur(4)))  # soft edges, no hard box
px = im.load()
for b in BOXES: erase(*b)
erase_h(530, 668, 1490, 748)                                             # height, profile, climb/descent

plate = im.resize((TW, TH), Image.LANCZOS)
k = 0.47                                  # sprites keep their shape: one scale for both axes
bw, bh = int(bike.width * k), int(bike.height * k)
cx, cy = 177 / W * TW, 625 / H * TH       # where the bike sat in the mockup
sp = bike.resize((bw, bh), Image.LANCZOS)
plate.paste(sp, (int(cx - bw / 2), int(cy - bh / 2)), feather(sp, 12))
plate.save(sys.argv[2], 'WEBP', quality=90)
if len(sys.argv) > 3: plate.save(sys.argv[3])
