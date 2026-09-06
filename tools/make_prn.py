#!/usr/bin/env python3
"""
Membuat file .prn berisi ESC/P-R mentah untuk Epson L3110.

Halaman uji tidak butuh library apa pun. Untuk mencetak gambar asli,
pasang Pillow dulu:  pip install pillow

Contoh:
    python make_prn.py test.prn
    python make_prn.py out.prn --image foto.jpg --dpi 720 --quality high
    python make_prn.py bw.prn  --image scan.png --mono

Cara mengirim ke printer di Windows: lihat README.md bagian "Tes cepat".
"""

import argparse
import sys

import escpr as E


def testpage_lines(width, height):
    """Halaman uji: bar warna, gradasi abu, bingkai, garis kisi. Tanpa dependensi."""
    bars = [(0, 0, 0), (255, 0, 0), (0, 255, 0), (0, 0, 255),
            (0, 255, 255), (255, 0, 255), (255, 255, 0)]
    white = (255, 255, 255)
    border = 4                      # tebal bingkai (piksel)
    band = max(1, height // 12)     # tinggi tiap zona

    for y in range(height):
        row = bytearray()

        # bingkai atas / bawah
        if y < border or y >= height - border:
            yield bytes((0, 0, 0)) * width
            continue

        for x in range(width):
            if x < border or x >= width - border:
                px = (0, 0, 0)                      # bingkai kiri/kanan
            elif y < band * 3:
                px = bars[(x * len(bars)) // width]  # bar warna
            elif y < band * 5:
                g = (x * 255) // max(1, width - 1)
                px = (g, g, g)                       # gradasi abu-abu
            elif y < band * 7:
                g = 255 - (x * 255) // max(1, width - 1)
                px = (255, g, g)                     # gradasi merah
            elif (x % 100) < 2 or (y % 100) < 2:
                px = (0, 0, 0)                       # kisi 100 px
            else:
                px = white
            row += bytes(px)

        yield bytes(row)


def image_lines(path, width, height, mono=False):
    """Skala gambar agar pas di area cetak (rasio dijaga), sisanya putih."""
    try:
        from PIL import Image
    except ImportError:
        sys.exit("Butuh Pillow untuk --image. Jalankan: pip install pillow")

    img = Image.open(path)
    if img.mode != "RGB":
        img = img.convert("RGB")
    if mono:
        img = img.convert("L").convert("RGB")

    scale = min(width / img.width, height / img.height)
    new_w = max(1, int(img.width * scale))
    new_h = max(1, int(img.height * scale))
    img = img.resize((new_w, new_h), Image.LANCZOS)

    pad_left = (width - new_w) // 2
    blank = b"\xff\xff\xff" * width
    pad_top = (height - new_h) // 2

    for y in range(height):
        sy = y - pad_top
        if sy < 0 or sy >= new_h:
            yield blank
            continue
        row = img.crop((0, sy, new_w, sy + 1)).tobytes()
        yield (b"\xff\xff\xff" * pad_left) + row + \
              (b"\xff\xff\xff" * (width - pad_left - new_w))


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("output", help="file .prn yang dihasilkan")
    ap.add_argument("--image", help="gambar yang dicetak (butuh Pillow); "
                                    "tanpa ini akan dibuat halaman uji")
    ap.add_argument("--paper", default="A4", choices=sorted(E.PAPER_SIZES))
    ap.add_argument("--dpi", type=int, default=360, choices=[300, 360, 600, 720])
    ap.add_argument("--quality", default="normal", choices=["draft", "normal", "high"])
    ap.add_argument("--mono", action="store_true", help="cetak hitam-putih")
    ap.add_argument("--margin", type=float, default=3.0, help="margin mm (default 3)")
    ap.add_argument("--no-compress", action="store_true", help="matikan RLE")
    args = ap.parse_args()

    quality = {"draft": E.Quality.DRAFT,
               "normal": E.Quality.NORMAL,
               "high": E.Quality.HIGH}[args.quality]

    with open(args.output, "wb") as f:
        job = E.EscpRJob(
            f,
            paper=args.paper,
            dpi=args.dpi,
            quality=quality,
            color_mode=E.ColorMode.MONO if args.mono else E.ColorMode.COLOR,
            margin_mm=(args.margin,) * 4,
        )
        w, h = job.start()
        print(f"Area cetak: {w} x {h} px  ({args.paper} @ {args.dpi} dpi)")

        lines = (image_lines(args.image, w, h, args.mono) if args.image
                 else testpage_lines(w, h))
        job.page(lines, page_no=1, pages_remaining=0,
                 compress=not args.no_compress)
        job.finish()

    import os
    size = os.path.getsize(args.output)
    print(f"Tertulis {args.output} -- {size:,} byte ({size / 1048576:.1f} MB)")


if __name__ == "__main__":
    main()
