"""Template art: the rider's 368G cut-out on each template's own scene (or a dark accent gradient).
Each art is made at the shape of the box the template draws it in (ProDashView.backdrop: centre-crop
"cover"), with the bike inside the part that shows at full strength (the rest is feathered or sits
under the gauge / rows).
Usage: python3 make_art.py <bike.png> <res/drawable-nodpi> <outdir>   (swap bike.png to restyle all)"""
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance
bike_src, res, out = sys.argv[1:4]
# Layout boxes: art size (2x the design box) and the clear area (x0, x1, y0, y1 as fractions).
LEFT = ((778, 412), (0.05, 0.70, 0.06, 0.56))      # leftPanel: 0..0.38w, rows cover the lower half
SUPER = ((880, 446), (0.05, 0.70, 0.06, 0.76))     # supercar: 0..0.43w x 0.165..0.69h, ring on the right
TOUR = ((614, 570), (0.03, 0.88, 0.12, 0.92))      # touring: 0..0.30w x 0.165..0.835h, fades out right (the front may fade a little)
TNAV = ((482, 298), (0.05, 0.84, 0.30, 0.92))      # tourNav: 0..0.235w x 0.45..0.8h
FREE = ((528, 348), (0.06, 0.94, 0.08, 0.92))      # navpro: faint backdrop / fallback art
ART = {  # name: (accent, scene, layout)
    'gold': ((255,194,26), 'gold', LEFT), 'adventure': ((255,164,27), 'adventure', LEFT),
    'neoncity': ((255,46,136), 'neoncity', LEFT), 'racing': ((255,42,42), 'racing', LEFT),
    'gt': ((255,30,45), 'gt', SUPER), 'papaya': ((255,128,0), 'papaya', SUPER),
    'grand': ((217,178,106), 'grand', SUPER), 'noir': ((46,155,255), None, SUPER),
    'rosso': ((255,30,30), 'rosso', TOUR), 'touring': ((255,184,28), 'touring', TOUR), 'space': ((46,211,255), 'space', TOUR),
    'tournav': ((255,138,31), None, TNAV), 'navpro': ((184,242,58), None, FREE),
}
bike0 = Image.open(bike_src).convert('RGBA'); bike0 = bike0.crop(bike0.getbbox())
def cover(im, W, H):
    s = max(W / im.width, H / im.height); im = im.resize((round(im.width*s), round(im.height*s)), Image.LANCZOS)
    x, y = (im.width - W)//2, (im.height - H)//2; return im.crop((x, y, x+W, y+H))
for name, (acc, scene, ((W, H), (x0, x1, y0, y1))) in ART.items():
    if scene:
        bg = cover(Image.open(f'{res}/dash_scene_{scene}.webp').convert('RGB'), W, H).filter(ImageFilter.GaussianBlur(max(2, W//200)))
        bg = ImageEnhance.Brightness(bg).enhance(0.5).convert('RGBA')
    else:
        bg = Image.new('RGBA', (W, H), (8, 10, 14, 255))
        g = Image.new('L', (W, H), 0); ImageDraw.Draw(g).ellipse((-W*0.2, H*0.15, W*1.2, H*1.4), fill=120)
        bg = Image.composite(Image.new('RGBA', (W, H), tuple(int(c*0.35) for c in acc)+(255,)), bg, g.filter(ImageFilter.GaussianBlur(W//5)))
    # Bike: as big as the clear area allows, centred in it, wheels on its lower edge.
    aw, ah = (x1-x0)*W, (y1-y0)*H
    s = min(aw / bike0.width, ah / bike0.height)
    bw, bh = round(bike0.width*s), round(bike0.height*s); bike = bike0.resize((bw, bh), Image.LANCZOS)
    x, y = round(x0*W + (aw-bw)/2), round(y1*H - bh)
    glow = Image.new('L', (W, H), 0); ImageDraw.Draw(glow).ellipse((x-bw*0.1, y-bh*0.1, x+bw*1.1, y+bh*1.05), fill=110)
    bg = Image.composite(Image.new('RGBA', (W, H), acc+(255,)), bg, glow.filter(ImageFilter.GaussianBlur(max(8, W//12))).point(lambda v: v*0.4))
    sh = Image.new('L', (W, H), 0); fy = y + bh
    ImageDraw.Draw(sh).ellipse((x+bw*0.04, fy-bh*0.05, x+bw*0.96, fy+bh*0.06), fill=210)
    bg = Image.composite(Image.new('RGBA', (W, H), (0,0,0,255)), bg, sh.filter(ImageFilter.GaussianBlur(max(3, W//80))))
    rim = bike.getchannel('A').filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.GaussianBlur(3)).point(lambda v: v*0.35)
    rl = Image.new('RGBA', (bw, bh), acc+(0,)); rl.putalpha(rim); bg.alpha_composite(rl, (x, y))
    bg.alpha_composite(bike, (x, y))
    bg.convert('RGB').save(f'{out}/dash_art_{name}.webp', 'WEBP', quality=86)
    print(name, W, H)
