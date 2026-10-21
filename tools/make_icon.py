#!/usr/bin/env python3
"""生成桌面图标（黄底黑「字」，单字高对比，给视力不好的老人用）和 leanback banner。"""
import os
from PIL import Image, ImageDraw, ImageFont

FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def make_icon(size=512):
    W = size
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, W - 1, W - 1], radius=int(W * 0.17), fill=(255, 179, 0, 255))

    font = ImageFont.truetype(FONT, int(W * 0.86), index=2)
    bb = d.textbbox((0, 0), "字", font=font)
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    d.text(((W - tw) / 2 - bb[0], (W - th) / 2 - bb[1] - W * 0.00),
           "字", font=font, fill=(26, 26, 26, 255))
    return img


def main():
    base = make_icon(512)
    base.save(os.path.join(ROOT, "docs/icon.png"))
    for name, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        d = os.path.join(ROOT, f"res/mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        base.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))

    W, H = 320, 180
    b = Image.new("RGB", (W, H), (255, 179, 0))
    bd = ImageDraw.Draw(b)
    ic = base.resize((120, 120), Image.LANCZOS)
    b.paste(ic, (24, 30), ic)
    f = ImageFont.truetype(FONT, 62, index=2)
    bd.text((160, 90), "大字幕", font=f, fill=(26, 26, 26), anchor="lm")
    os.makedirs(os.path.join(ROOT, "res/drawable"), exist_ok=True)
    b.save(os.path.join(ROOT, "res/drawable/banner.png"))
    print("icons generated")


if __name__ == "__main__":
    main()
