#!/usr/bin/env python3
"""把两帧截图拼成上下对比图（上：原画面，下：放大后）。

用法：
    python3 tools/make_before_after.py before.png after.png
    python3 tools/make_before_after.py before.png after.png -o docs/before-after.png

要求两张图分辨率一致（同一路输入、同一帧）。默认输出宽 1280，浅色分隔线，
左上角各一行小字标注，不做任何装饰性色块。
"""
import argparse
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

CJK_FONTS = [
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
]
STRIP_BG = (28, 28, 28)
STRIP_FG = (255, 255, 255)
GAP = (60, 60, 60)


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for path in CJK_FONTS:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    raise SystemExit("找不到中文字体，请安装 fonts-noto-cjk 或文泉驿正黑")


def strip(text: str, width: int, height: int, font: ImageFont.FreeTypeFont) -> Image.Image:
    """一条纯色标题条，压在图的上方而不是叠在画面上，避免遮住台标和字幕。"""
    bar = Image.new("RGB", (width, height), STRIP_BG)
    draw = ImageDraw.Draw(bar)
    box = draw.textbbox((0, 0), text, font=font)
    draw.text(((12, (height - (box[3] - box[1])) // 2 - box[1])), text, font=font, fill=STRIP_FG)
    return bar


def main() -> int:
    ap = argparse.ArgumentParser(description="拼一张电视字幕放大前后的对比图")
    ap.add_argument("before", help="原画面截图（放大条关闭）")
    ap.add_argument("after", help="放大后截图（放大条打开）")
    ap.add_argument("-o", "--out", default="docs/before-after.png")
    ap.add_argument("-w", "--width", type=int, default=1280, help="单帧输出宽度，默认 1280")
    ap.add_argument("--before-label", default="原画面")
    ap.add_argument("--after-label", default="放大 3 倍后")
    args = ap.parse_args()

    a = Image.open(args.before).convert("RGB")
    b = Image.open(args.after).convert("RGB")
    if a.size != b.size:
        print(f"尺寸不一致：{a.size} vs {b.size}（必须是同一路输入的两帧）", file=sys.stderr)
        return 1

    height = round(a.height * args.width / a.width)
    a = a.resize((args.width, height), Image.LANCZOS)
    b = b.resize((args.width, height), Image.LANCZOS)

    font = load_font(max(18, args.width // 42))
    bar_h = max(34, args.width // 26)
    top = strip(args.before_label, args.width, bar_h, font)
    bottom = strip(args.after_label, args.width, bar_h, font)

    canvas = Image.new("RGB", (args.width, bar_h * 2 + height * 2 + 3), GAP)
    canvas.paste(top, (0, 0))
    canvas.paste(a, (0, bar_h))
    canvas.paste(bottom, (0, bar_h + height + 3))
    canvas.paste(b, (0, bar_h * 2 + height + 3))

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out, optimize=True)
    print(f"{out}  {canvas.width}x{canvas.height}  {out.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
