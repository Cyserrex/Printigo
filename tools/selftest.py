#!/usr/bin/env python3
"""Uji mandiri encoder ESC/P-R: RLE round-trip + panjang tiap perintah."""

import random
import struct
import sys

import escpr as E

fails = []


def check(name, cond, extra=""):
    if cond:
        print(f"  OK   {name}")
    else:
        print(f"  GAGAL {name} {extra}")
        fails.append(name)


print("1. RLE round-trip (3 byte/piksel)")
random.seed(7)
cases = {
    "semua putih 2480px": b"\xff\xff\xff" * 2480,
    "acak 1000px": bytes(random.randrange(256) for _ in range(3000)),
    "selang-seling": (b"\x00\x00\x00" + b"\xff\xff\xff") * 500,
    "run panjang 5000px": b"\x12\x34\x56" * 5000,
    "satu piksel": b"\xaa\xbb\xcc",
    "campur": (b"\xff\xff\xff" * 300 + bytes(random.randrange(256) for _ in range(900))
               + b"\x00\x00\x00" * 200),
}
for name, raw in cases.items():
    enc = E.run_length_encode(raw, 3)
    dec = E.run_length_decode(enc, 3)
    ratio = len(enc) / len(raw)
    check(f"{name} ({len(raw)}B -> {len(enc)}B, {ratio:.3f}x)", dec == raw)

print("\n2. Batas penghitung RLE")
enc = E.run_length_encode(b"\x01\x02\x03" * 129, 3)
check("129 pengulangan -> counter 0x80", enc[0] == 128, f"dapat {enc[0]}")
check("129 pengulangan -> 4 byte", len(enc) == 4, f"dapat {len(enc)}")
enc = E.run_length_encode(b"\x01\x02\x03" * 130, 3)
check("130 pengulangan -> pecah jadi 2 blok", len(enc) == 8, f"dapat {len(enc)}")
raw = b"".join(bytes((i, 0, 255 - i)) for i in range(128))  # 128 piksel unik
enc = E.run_length_encode(raw, 3)
check("128 piksel unik -> counter 0x7f", enc[0] == 127, f"dapat {enc[0]}")
check("128 piksel unik -> 385 byte", len(enc) == 1 + 128 * 3, f"dapat {len(enc)}")
check("128 piksel unik round-trip", E.run_length_decode(enc, 3) == raw)

print("\n3. Panjang perintah cocok dengan konstanta driver C Epson")
# epson-escpr-api.c: JobCmd  = 1B 6A 16 00 00 00 's','e','t','j' + 22 byte
j, w, h = E.cmd_set_job(E.PAPER_SIZES["A4"], dpi=360)
check("setj total 32 byte", len(j) == 32, f"dapat {len(j)}")
check("setj field panjang = 0x16", j[2:6] == b"\x16\x00\x00\x00", j[2:6].hex())
check("setj berawalan ESC 'j' ... 'setj'", j[0:2] == b"\x1bj" and j[6:10] == b"setj")

# PrintQualityCmd = 1B 71 09 00 00 00 's','e','t','q' + 9 byte
q = E.cmd_set_quality()
check("setq total 19 byte", len(q) == 19, f"dapat {len(q)}")
check("setq field panjang = 0x09", q[2:6] == b"\x09\x00\x00\x00", q[2:6].hex())

# StartPage = 1B 70 00 00 00 00 's','t','t','p'
check("sttp = 1b7000000000 73747470",
      E.cmd_start_page() == b"\x1bp\x00\x00\x00\x00sttp",
      E.cmd_start_page().hex())
# EndPage = 1B 70 01 00 00 00 'e','n','d','p' 00
check("endp = 1b7001000000 656e6470 00",
      E.cmd_end_page(0) == b"\x1bp\x01\x00\x00\x00endp\x00",
      E.cmd_end_page(0).hex())
# EndJob = 1B 6A 00 00 00 00 'e','n','d','j'
check("endj = 1b6a00000000 656e646a",
      E.cmd_end_job() == b"\x1bj\x00\x00\x00\x00endj",
      E.cmd_end_job().hex())
# RemoteJS = 'J','S',04,00, 00,00,00,00
check("REMOTE JS = 4a53 0400 00 000000",
      E.cmd_job_start() == b"JS\x04\x00\x00\x00\x00\x00",
      E.cmd_job_start().hex())
check("Enter remote mode", E.ENTER_REMOTE_MODE == b"\x1b(R\x08\x00\x00REMOTE1")
check("Exit remote mode", E.EXIT_REMOTE_MODE == b"\x1b\x00\x00\x00")

print("\n4. Geometri halaman")
_, w360, h360 = E.cmd_set_job(E.PAPER_SIZES["A4"], (3, 3, 3, 3), 360)
# A4 = 210x297mm; @360dpi -> 2977x4210 px kertas, margin 3mm = 42 px per sisi
check(f"A4 @360dpi area cetak = {w360}x{h360} px",
      (w360, h360) == (2977 - 84, 4210 - 84), f"harusnya {2977 - 84}x{4210 - 84}")
_, w720, h720 = E.cmd_set_job(E.PAPER_SIZES["A4"], (3, 3, 3, 3), 720)
check(f"A4 @720dpi area cetak = {w720}x{h720} px",
      abs(w720 - 2 * w360) <= 6 and abs(h720 - 2 * h360) <= 6,
      "harus kira-kira dua kali ukuran 360dpi")
check("kode dpi 360/720/300/600 = 0/1/2/3",
      [E.dpi_code(d) for d in (360, 720, 300, 600)] == [0, 1, 2, 3])

print("\n5. Header dsnd")
line = b"\xff\xff\xff" * 100
pkt = E.cmd_send_line(0, 5, line, compress=True)
check("dsnd berawalan ESC 'd'", pkt[0:2] == b"\x1bd")
declared = struct.unpack("<I", pkt[2:6])[0]
check("panjang di header cocok dengan sisa paket", declared == len(pkt) - 10,
      f"{declared} vs {len(pkt) - 10}")
check("kode = 'dsnd'", pkt[6:10] == b"dsnd")
x, y, comp, n = struct.unpack(">HHBH", pkt[10:17])
check("x=0 y=5 compress=1", (x, y, comp) == (0, 5, 1), f"{(x, y, comp)}")
check("panjang payload cocok", n == len(pkt) - 17, f"{n} vs {len(pkt) - 17}")
check("payload ter-decode kembali ke baris asli",
      E.run_length_decode(pkt[17:], 3) == line)

print("\n6. Job utuh terurut benar")
import io
buf = io.BytesIO()
job = E.EscpRJob(buf, paper="A4", dpi=300)
pw, ph = job.start()
job.page([b"\xff\xff\xff" * pw for _ in range(20)], page_no=1, pages_remaining=0)
job.finish()
data = buf.getvalue()
order = ["\x00\x00\x00\x1b\x01@EJL", "REMOTE1", "ESCPR", "setq", "setj",
         "sttp", "dsnd", "endp", "endj", "JE"]
pos = -1
ok = True
for token in order:
    p = data.find(token.encode("latin1"), pos + 1)
    if p <= pos:
        ok = False
        print(f"      urutan salah pada '{token}'")
        break
    pos = p
check(f"urutan perintah benar ({len(data)} byte)", ok)
check("job diakhiri exit-remote-mode", data.endswith(b"\x1b\x00\x00\x00"))

print()
if fails:
    print(f"{len(fails)} PEMERIKSAAN GAGAL: {fails}")
    sys.exit(1)
print("Semua pemeriksaan lolos.")
