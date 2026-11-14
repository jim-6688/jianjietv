#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Draw the 吾爱电视 brand assets: launcher icons (every density), the Android TV banner
and the README hero banner. Edit the palette or the wordmark here and re-run:
    python tools/make_brand_assets.py
Needs Pillow (pip install pillow)."""
import math, os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_DIR = os.environ.get("WUAI_FONT_DIR") or next(
    (d for d in ("C:/Windows/Fonts", "/usr/share/fonts/truetype/dejavu",
                 "/System/Library/Fonts", "/Library/Fonts")
     if os.path.isdir(d)), "C:/Windows/Fonts")

TEAL   = (31, 182, 201)
BLUE   = (30, 111, 224)
WHITE  = (255, 255, 255)
SCREEN = (232, 244, 255)
HEART  = (255, 77, 94)
DARK   = (16, 24, 38)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def diagonal_gradient(size, c1, c2):
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / (w - 1) + y / (h - 1)) / 2.0
            px[x, y] = lerp(c1, c2, t)
    return img


def heart_polygon(cx, cy, scale):
    pts = []
    for i in range(361):
        t = math.radians(i)
        x = 16 * (math.sin(t) ** 3)
        y = -(13 * math.cos(t) - 5 * math.cos(2*t) - 2 * math.cos(3*t) - math.cos(4*t))
        pts.append((cx + x * scale, cy + y * scale))
    return pts


def draw_tv(draw, box, fg=WHITE, screen=SCREEN, heart=HEART, aspect=1.45):
    """A flat television: wide bezel + inner screen with a heart, on a stand.
    `box` is the full footprint (screen + stand)."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    stand_h = h * 0.24
    body_h = h - stand_h
    body_w = w
    bx1 = x0 + body_w

    # bezel
    draw.rounded_rectangle([x0, y0, bx1, y0 + body_h], radius=body_h * 0.11, fill=fg)
    # inner screen
    pad = body_h * 0.135
    sx0, sy0, sx1, sy1 = x0 + pad, y0 + pad, bx1 - pad, y0 + body_h - pad
    draw.rounded_rectangle([sx0, sy0, sx1, sy1], radius=body_h * 0.05, fill=screen)
    # heart, scaled to fit inside the screen with a margin
    sw, sh = sx1 - sx0, sy1 - sy0
    scale = min(sw * 0.58 / 32.0, sh * 0.58 / 22.0)
    hcx = (sx0 + sx1) / 2
    hcy = (sy0 + sy1) / 2 + 6 * scale   # heart bbox is offset, keep it centred
    draw.polygon(heart_polygon(hcx, hcy, scale), fill=heart)
    # stand: neck + base
    cx = (x0 + bx1) / 2
    neck_w, neck_h = body_w * 0.075, stand_h * 0.46
    ny = y0 + body_h
    draw.polygon([(cx - neck_w, ny), (cx + neck_w, ny),
                  (cx + neck_w * 0.5, ny + neck_h), (cx - neck_w * 0.5, ny + neck_h)], fill=fg)
    base_w, base_h = body_w * 0.40, stand_h * 0.30
    by = ny + neck_h
    draw.rounded_rectangle([cx - base_w / 2, by, cx + base_w / 2, by + base_h],
                           radius=base_h * 0.45, fill=fg)


def fit_font(draw, text, fonts, max_w, start_size):
    """Largest size from `fonts` that keeps `text` within max_w."""
    best = None
    for path in fonts:
        size = start_size
        while size > 10:
            f = ImageFont.truetype(path, size)
            if draw.textbbox((0, 0), text, font=f)[2] <= max_w:
                return f
            size -= 2
        if best is None:
            best = ImageFont.truetype(path, 10)
    return best


FONT_BOLD = [os.path.join(FONT_DIR, "msyhbd.ttc"), os.path.join(FONT_DIR, "simhei.ttf")]
FONT_REG = [os.path.join(FONT_DIR, "msyh.ttc"), os.path.join(FONT_DIR, "simhei.ttf")]


def make_icon(size):
    img = diagonal_gradient((size, size), TEAL, BLUE)
    d = ImageDraw.Draw(img)
    m = int(size * 0.14)
    draw_tv(d, (m, m, size - m, size - m), aspect=1.35)
    return img


# ---- 1) launcher icons ----
densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for name, px in densities.items():
    out_dir = os.path.join(ROOT, "launcher/app/src/main/res", f"mipmap-{name}")
    os.makedirs(out_dir, exist_ok=True)
    make_icon(px).save(os.path.join(out_dir, "ic_launcher.png"), "PNG")
print("icons done:", densities)


# ---- 2) Android TV banner ----
def make_banner(W, H, path, title, sub, dark=False):
    if dark:
        bg = diagonal_gradient((W, H), lerp(TEAL, DARK, 0.30), lerp(BLUE, DARK, 0.60))
    else:
        bg = diagonal_gradient((W, H), TEAL, BLUE)
    d = ImageDraw.Draw(bg)

    tv_h = int(H * 0.60)
    tv_w = int(tv_h * 1.55)
    tv_x = int(W * 0.08)
    tv_y = (H - tv_h) // 2
    draw_tv(d, (tv_x, tv_y, tv_x + tv_w, tv_y + tv_h))

    text_x = tv_x + tv_w + int(W * 0.05)
    avail = W - text_x - int(W * 0.05)
    f_big = fit_font(d, title, FONT_BOLD, avail, int(H * 0.21))
    f_sub = fit_font(d, sub, FONT_REG, avail, int(H * 0.068))
    bb = d.textbbox((0, 0), title, font=f_big)
    d.text((text_x, (H - (bb[3] - bb[1])) / 2 - H * 0.06), title, font=f_big, fill=WHITE)
    sb = d.textbbox((0, 0), sub, font=f_sub)
    d.text((text_x, (H - (bb[3] - bb[1])) / 2 - H * 0.06 + (bb[3] - bb[1]) + H * 0.045),
           sub, font=f_sub, fill=(232, 243, 255))
    bg.save(path, "PNG")
    print("saved", path, (W, H))


make_banner(1280, 720,
            os.path.join(ROOT, "launcher/app/src/main/res/drawable-nodpi/banner.png"),
            "领先桌面", "让电视回归简洁 · 隐私清理与极简桌面")

make_banner(1600, 500,
            os.path.join(ROOT, "docs/banner.png"),
            "领先桌面", "让电视桌面回归简洁 — 隐私清理 + 极简桌面", dark=True)

print("ALL DONE")
