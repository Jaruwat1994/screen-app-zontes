# Builds the "Race Bar" dash plate (1024x425) from the rider's mockup docs/boder-xadv.png: every live value
# (and the speed bar, drawn live) painted black, squashed to the panel, the icons pasted back at their own shape.
# Usage: racebar_plate.py <mockup.png> <out.webp> [preview.png]
import sys
from PIL import Image, ImageDraw
src = Image.open(sys.argv[1]).convert('RGBA')
bg = Image.new('RGBA', src.size, (0, 0, 0, 255)); bg.alpha_composite(src); src = bg.convert('RGB')
W, H = src.size                      # 1672x941
TW, TH = 1024, 425
im = src.copy(); d = ImageDraw.Draw(im)
icons = [src.crop(b) for b in [(712, 600, 772, 664), (82, 748, 202, 815), (500, 738, 556, 820), (822, 742, 908, 808), (1180, 742, 1260, 815)]]
for b in [(105, 45, 515, 108),                                  # "ZONTES 368G": the bike's own name, drawn live
          (812, 55, 960, 100), (990, 55, 1135, 100),            # GPS FIX, 12 SAT
          (1358, 52, 1490, 102), (1505, 52, 1625, 102),         # weather, clock
          (46, 140, 1012, 300), (46, 300, 225, 690),            # the rpm bar and its numbers: a speed bar now
          (350, 335, 815, 540),                                 # 95 km/h
          (858, 355, 988, 470), (862, 478, 984, 520),           # S, GEAR (the heading now)
          (250, 592, 430, 675), (486, 592, 670, 675),           # trip, odo (labels too: drawn in the rider's language)
          (712, 600, 1005, 664),                                # fuel pump, level, %
          (1046, 144, 1627, 431),                               # the turn + little map: the live map goes here
          (1046, 470, 1627, 676),                               # RPM card: lean angle now
          (82, 730, 410, 825), (498, 732, 712, 825),            # ride mode, ENG TEMP
          (820, 740, 912, 810), (938, 730, 1090, 825),          # battery
          (1178, 740, 1262, 818), (1295, 730, 1600, 815)]:      # tyres
    d.rectangle(b, fill=(0, 0, 0))
plate = im.resize((TW, TH), Image.LANCZOS)
sy = TH / H
centres = [(742, 632), (142, 781), (528, 779), (865, 775), (1220, 778)]
for sp, (cx, cy) in zip(icons, centres):
    s = sp.resize((max(1, int(sp.width * sy * 1.1)), max(1, int(sp.height * sy * 1.1))), Image.LANCZOS)
    plate.paste(s, (int(cx * TW / W - s.width / 2), int(cy * sy - s.height / 2)))
plate.save(sys.argv[2], 'WEBP', quality=90)
if len(sys.argv) > 3: plate.save(sys.argv[3])
