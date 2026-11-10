# Builds the "Smart Ride" dash plate (1024x425) from the rider's mockup docs/borer-zride.png: live values
# erased, squashed to the panel, bike and ZONTES logo pasted back at their own shape.
# Usage: zride_plate.py <mockup.png> <out.webp> [preview.png]
import sys
from PIL import Image
src = Image.open(sys.argv[1]).convert('RGB')
W, H = src.size                      # 1672x941
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

def erase_h(x0, y0, x1, y1):
    """Per row: blend the column left of the box into the one right of it."""
    for y in range(y0, y1):
        a = px[x0 - 1, y]; b = px[x1, y]
        for x in range(x0, x1):
            t = (x - x0 + 0.5) / (x1 - x0)
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def feather(img, f):
    m = Image.new('L', img.size, 0); mp = m.load()
    for y in range(img.height):
        for x in range(img.width):
            d = min(x, y, img.width - 1 - x, img.height - 1 - y)
            mp[x, y] = 255 if d >= f else int(255 * d / f)
    return m

bike = src.crop((0, 195, 300, 505)); logo = src.crop((60, 508, 268, 594))
erase_h(148, 14, 520, 90)                                                     # "RIDEHUB 368G / SMART RIDING SYSTEM"
erase(55, 505, 275, 598)                                                      # the logo: pasted back unsquashed
for b in [(728, 24, 930, 70), (975, 24, 1135, 70), (1180, 24, 1335, 70),       # gps, sats, fps
          (1515, 20, 1640, 76),                                                # clock
          (1478, 128, 1660, 186),                                              # charging
          (1295, 212, 1425, 278), (1282, 279, 1460, 306), (1530, 278, 1636, 306),  # 63 %, power source, mV
          (1340, 348, 1455, 410), (1535, 410, 1636, 442),                      # 34 °C, NORMAL
          (1335, 492, 1450, 534), (1560, 492, 1640, 534),                      # dBm, storage
          (1320, 614, 1430, 658), (1520, 614, 1645, 652),                      # READY, hPa
          ]:
    erase(*b)
for b in [(488, 680, 584, 768), (1012, 698, 1175, 748),                     # album art, volume
          (175, 838, 300, 880), (470, 838, 560, 880), (810, 838, 920, 880), (1118, 838, 1262, 880)]:  # bottom values
    erase_h(*b)
erase_h(1455, 222, 1636, 266); erase_h(1495, 358, 1636, 402)                   # level bars (drawn live)

plate = im.resize((TW, TH), Image.LANCZOS)
sx = TW / W
for sp, (cx, cy) in ((bike, (150, 350)), (logo, (164, 551))):                  # centres in the mockup
    s = sp.resize((int(sp.width * sx), int(sp.height * sx)), Image.LANCZOS)
    ty = {350: 150, 551: 268}[cy]                                              # stacked to fit the column
    plate.paste(s, (int(cx * sx - s.width / 2), int(ty - s.height / 2)), feather(s, 10))
plate.save(sys.argv[2], 'WEBP', quality=90)
if len(sys.argv) > 3: plate.save(sys.argv[3])
