#!/usr/bin/env python3
"""生成扁平图标（黄底黑「大字幕」）和 leanback banner。

这台电视的启动器按图片原始比例渲染，所以图标做成 2:1 的扁条，
和「我的手机」「电视」这些自带图标视觉一致。
"""
import os
from PIL import Image, ImageDraw, ImageFont

FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEXT = "大字幕"
AMBER = (255, 179, 0, 255)
INK = (26, 26, 26, 255)


def _draw_text(d, w, h, size, fill):
    f = ImageFont.truetype(FONT, size, index=2)
    boxes = [d.textbbox((0, 0), c, font=f) for c in TEXT]
    widths = [b[2] - b[0] for b in boxes]
    total = sum(widths)
    gap = (w * 0.86 - total) / (len(TEXT) - 1)
    x = (w - (total + gap * (len(TEXT) - 1))) / 2
    for c, b, cw in zip(TEXT, boxes, widths):
        d.text((x - b[0], (h - (b[3] - b[1])) / 2 - b[1]), c, font=f, fill=fill)
        x += cw + gap


def make_flat(w, h):
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=int(h * 0.28), fill=AMBER)
    _draw_text(d, w, h, int(h * 0.62), INK)
    return img


def main():
    make_flat(512, 256).save(os.path.join(ROOT, "docs/icon.png"))
    for name, h in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        d = os.path.join(ROOT, f"res/mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        make_flat(h * 2, h).save(os.path.join(d, "ic_launcher.png"))

    W, H = 320, 180
    b = Image.new("RGB", (W, H), (255, 179, 0))
    bd = ImageDraw.Draw(b)
    _draw_text(bd, W, H, 92, INK)
    os.makedirs(os.path.join(ROOT, "res/drawable"), exist_ok=True)
    b.save(os.path.join(ROOT, "res/drawable/banner.png"))
    print("icons generated")


if __name__ == "__main__":
    main()
