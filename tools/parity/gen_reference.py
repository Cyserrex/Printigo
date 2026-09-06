#!/usr/bin/env python3
"""Menghasilkan halaman uji acuan dengan encoder Python (waktu dipatok tetap)."""

import os
import struct
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

import escpr as E
from make_prn import testpage_lines

# Patok waktu agar perintah TI sama dengan yang dihasilkan ParityMain.kt.
E.cmd_time_init = lambda t=None: E.remote_cmd(
    b"TI", struct.pack(">HBBBBB", 2020, 1, 2, 3, 4, 5)
)

out_path = sys.argv[1]
with open(out_path, "wb") as f:
    job = E.EscpRJob(f, paper="A6", dpi=300, quality=E.Quality.NORMAL,
                     margin_mm=(3.0,) * 4)
    w, h = job.start()
    print(f"Python: {w}x{h} px")
    job.page(testpage_lines(w, h), page_no=1, pages_remaining=0)
    job.finish()
