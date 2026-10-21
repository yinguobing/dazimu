#!/usr/bin/env python3
"""生成桌面图标（蓝底白「字」+ 黄色放大镜）和 leanback banner。"""
import os
from PIL import Image, ImageDraw, ImageFont

FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def make_icon(size=512):
    W = size
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    grad = Image.new("RGB", (1, W))
    for y in range(W):
        t = y / (W - 1)
        grad.putpixel((0, y), (int(13 + 17 * t), int(71 + 65 * t), int(161 + 68 * t)))
    grad = grad.resize((W, W))
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, W - 1], radius=int(W * 0.22), fill=255)
    img.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(img)
    s = W / 512.0

    font = ImageFont.truetype(FONT, int(286 * s), index=2)
    bb = d.textbbox((0, 0), "字", font=font)
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    d.text(((W - tw) / 2 - bb[0] - 22 * s, (W - th) / 2 - bb[1] - 42 * s),
           "字", font=font, fill=(255, 255, 255, 255))

    cx, cy, r, lw = 372 * s, 368 * s, 92 * s, 24 * s
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(255, 213, 79, 255), width=int(lw))
    hx, hy = 70 * s, 70 * s
    d.line([cx + hx, cy + hy, cx + hx + 52 * s, cy + hy + 52 * s],
           fill=(255, 213, 79, 255), width=int(34 * s))
    ex, ey, rr = cx + hx + 52 * s, cy + hy + 52 * s, 17 * s
    d.ellipse([ex - rr, ey - rr, ex + rr, ey + rr], fill=(255, 213, 79, 255))
    return img


def main():
    base = make_icon(512)
    base.save(os.path.join(ROOT, "docs/icon.png"))
    for name, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        d = os.path.join(ROOT, f"res/mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        base.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))

    W, H = 320, 180
    b = Image.new("RGB", (W, H), (13, 71, 161))
    bd = ImageDraw.Draw(b)
    for y in range(H):
        t = y / (H - 1)
        bd.line([(0, y), (W, y)], fill=(int(13 + 17 * t), int(71 + 65 * t), int(161 + 68 * t)))
    ic = base.resize((120, 120), Image.LANCZOS)
    b.paste(ic, (24, 30), ic)
    f = ImageFont.truetype(FONT, 58, index=2)
    bd.text((156, 86), "大字幕", font=f, fill=(255, 255, 255), anchor="lm")
    os.makedirs(os.path.join(ROOT, "res/drawable"), exist_ok=True)
    b.save(os.path.join(ROOT, "res/drawable/banner.png"))
    print("icons generated")


if __name__ == "__main__":
    main()
