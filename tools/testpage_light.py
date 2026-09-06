#!/usr/bin/env python3
"""
Halaman uji ESC/P-R yang hemat tinta.

Berbeda dari make_prn.py yang membanjiri halaman dengan bar warna, halaman ini
hanya memakai sekitar 3% cakupan tinta tapi tetap membuktikan hal-hal penting:

  * printer menerima dan memahami aliran ESC/P-R,
  * warna CMY dan hitam keluar semua,
  * gradasi abu-abu mulus,
  * garis setipis 1 piksel terbentuk pada resolusi yang dipilih,
  * skala dan margin benar -- ada penggaris milimeter di tepi kiri dan atas
    yang bisa dicocokkan dengan penggaris sungguhan.

Pemakaian:
    python testpage_light.py keluaran.prn --paper A4 --dpi 300
"""

import argparse
import os

import escpr as E


def build_page(width, height, dpi):
    """Menghasilkan baris demi baris RGB, 3 byte per piksel."""
    white = b"\xff\xff\xff"
    black = (0, 0, 0)
    swatches = [
        (0, 0, 0),        # hitam
        (0, 255, 255),    # cyan
        (255, 0, 255),    # magenta
        (255, 255, 0),    # kuning
        (255, 0, 0),
        (0, 255, 0),
        (0, 0, 255),
    ]

    px_per_mm = dpi / 25.4
    border = max(1, int(dpi / 150))          # bingkai tipis
    tick_len_small = int(2 * px_per_mm)
    tick_len_large = int(5 * px_per_mm)
    tick_thick = max(1, int(dpi / 300))

    # Tata letak vertikal, dalam pecahan tinggi halaman.
    sw_y0, sw_y1 = int(height * 0.10), int(height * 0.14)
    gr_y0, gr_y1 = int(height * 0.20), int(height * 0.23)
    ln_y0, ln_y1 = int(height * 0.29), int(height * 0.35)
    bx_y0, bx_y1 = int(height * 0.42), int(height * 0.52)

    x0, x1 = int(width * 0.18), int(width * 0.82)   # lebar blok uji
    span = x1 - x0

    for y in range(height):
        row = bytearray(white * width)

        def paint(xs, xe, colour):
            xs = max(0, xs)
            xe = min(width, xe)
            if xe > xs:
                row[xs * 3:xe * 3] = bytes(colour) * (xe - xs)

        # Bingkai halaman: hanya garis tepi, bukan blok pejal.
        if y < border or y >= height - border:
            paint(0, width, black)
            yield bytes(row)
            continue
        paint(0, border, black)
        paint(width - border, width, black)

        # Penggaris milimeter di tepi atas.
        if border <= y < border + tick_len_large:
            for mm in range(0, int(width / px_per_mm) + 1, 5):
                x = int(mm * px_per_mm)
                length = tick_len_large if mm % 50 == 0 else tick_len_small
                if y < border + length:
                    paint(x, x + tick_thick, black)

        # Penggaris milimeter di tepi kiri.
        for mm in range(0, int(height / px_per_mm) + 1, 5):
            if int(mm * px_per_mm) <= y < int(mm * px_per_mm) + tick_thick:
                length = tick_len_large if mm % 50 == 0 else tick_len_small
                paint(border, border + length, black)

        # Petak warna.
        if sw_y0 <= y < sw_y1:
            each = span // len(swatches)
            for i, colour in enumerate(swatches):
                paint(x0 + i * each, x0 + (i + 1) * each - int(2 * px_per_mm), colour)

        # Gradasi abu-abu.
        elif gr_y0 <= y < gr_y1:
            for x in range(x0, x1):
                g = 255 - ((x - x0) * 255) // max(1, span - 1)
                row[x * 3:(x + 1) * 3] = bytes((g, g, g))

        # Uji ketajaman garis: kerapatan 1, 2, 4, dan 8 piksel.
        elif ln_y0 <= y < ln_y1:
            quarter = span // 4
            for group, step in enumerate((2, 4, 8, 16)):
                gx0 = x0 + group * quarter
                gx1 = gx0 + quarter - int(3 * px_per_mm)
                for x in range(gx0, gx1):
                    if (x - gx0) % step < step // 2:
                        row[x * 3:(x + 1) * 3] = bytes(black)

        # Kotak garis tepi bersarang: memeriksa keselarasan horizontal.
        elif bx_y0 <= y < bx_y1:
            depth = min(y - bx_y0, bx_y1 - 1 - y)
            thick = max(1, int(dpi / 300))
            if depth < thick:
                paint(x0, x1, black)
            else:
                inset = depth * (span // (2 * (bx_y1 - bx_y0)))
                paint(x0 + inset, x0 + inset + thick, black)
                paint(x1 - inset - thick, x1 - inset, black)

        yield bytes(row)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("output")
    ap.add_argument("--paper", default="A4", choices=sorted(E.PAPER_SIZES))
    ap.add_argument("--dpi", type=int, default=300, choices=[300, 360, 600, 720])
    ap.add_argument("--margin", type=float, default=3.0)
    args = ap.parse_args()

    with open(args.output, "wb") as f:
        job = E.EscpRJob(
            f, paper=args.paper, dpi=args.dpi,
            quality=E.Quality.NORMAL, color_mode=E.ColorMode.COLOR,
            margin_mm=(args.margin,) * 4, job_name="Uji ESCPR",
        )
        w, h = job.start()
        print(f"Area cetak {w} x {h} piksel ({args.paper} @ {args.dpi} dpi, "
              f"margin {args.margin} mm)")
        job.page(build_page(w, h, args.dpi), page_no=1, pages_remaining=0)
        job.finish()

    size = os.path.getsize(args.output)
    print(f"Tertulis {args.output} -- {size:,} byte")


if __name__ == "__main__":
    main()
