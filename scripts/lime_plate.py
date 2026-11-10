# Builds the "Moto Live" dash plate (1024x425) from the rider's mockup docs/border-moto.png: live values and the
# other app's name and logo painted out, squashed to the panel. The bike's own name is drawn live.
# Usage: lime_plate.py <mockup.png> <out.webp> [preview.png]
import sys
from PIL import Image
src = Image.open(sys.argv[1]).convert('RGB')
W, H = src.size                      # 1674x940
TW, TH = 1024, 425
im = src.copy(); px = im.load()
lum = lambda c: c[0] * 0.3 + c[1] * 0.59 + c[2] * 0.11

def erase(x0, y0, x1, y1):
    """Per column: blend the row above into the row below, or the darker one when an edge is a glow."""
    for x in range(x0, x1):
        a = px[x, y0 - 1]; b = px[x, y1]
        flat = min(a, b, key=lum) if abs(lum(a) - lum(b)) > 18 else None
        for y in range(y0, y1):
            t = (y - y0 + 0.5) / (y1 - y0)
            px[x, y] = flat or tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def erase_flat(x0, y0, x1, y1):
    """One colour: the median of the ring round the box (no streaks where text edges touched it)."""
    ring = [px[x, y] for x in range(x0 - 2, x1 + 2) for y in (y0 - 2, y1 + 1)] + [px[x, y] for y in range(y0, y1) for x in (x0 - 2, x1 + 1)]
    c = tuple(sorted(r[i] for r in ring)[len(ring) // 2] for i in range(3))
    for x in range(x0, x1):
        for y in range(y0, y1): px[x, y] = c

def erase_h(x0, y0, x1, y1):
    """Per row: blend the column left of the box into the one right of it."""
    for y in range(y0, y1):
        a = px[x0 - 1, y]; b = px[x1, y]
        for x in range(x0, x1):
            t = (x - x0 + 0.5) / (x1 - x0)
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

for b in [(30, 12, 112, 97), (122, 14, 360, 95),                               # "M" logo, MOTO//HUB + RIDE / LIVE (drawn live, left)
          (752, 32, 962, 88), (988, 38, 1108, 82), (1192, 38, 1362, 82),        # gps, sats, fps
          (1535, 28, 1648, 86),                                                 # clock
          (1496, 132, 1646, 179),                                               # charging
          (1262, 228, 1424, 299), (1266, 299, 1474, 331), (1508, 294, 1628, 331),   # 63 %, power source, mV
          (1262, 386, 1412, 462), (1512, 466, 1628, 502),                       # 34 °C, NORMAL
          (1256, 538, 1410, 592), (1512, 538, 1604, 592),                       # dBm, storage
          (1264, 656, 1394, 705), (1466, 656, 1642, 705),                       # READY, hPa
          ]:
    (erase if 300 < b[0] < 1200 else erase_flat)(*b)                          # header + phone cards: flat, no streaks                                  # the phone cards: flat, no streaks
for b in [(368, 716, 457, 792), (1092, 708, 1194, 788),                         # album art, little status + clock
          (198, 846, 300, 894), (556, 846, 630, 894), (848, 846, 958, 894), (1156, 846, 1294, 894),  # bottom values
          ]:
    erase_h(*b)
erase_flat(1440, 238, 1628, 290); erase_flat(1460, 400, 1628, 454)              # level bars (drawn live)
plate = im.resize((TW, TH), Image.LANCZOS)
plate.save(sys.argv[2], 'WEBP', quality=90)
if len(sys.argv) > 3: plate.save(sys.argv[3])
