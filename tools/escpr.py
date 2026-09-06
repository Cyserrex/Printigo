#!/usr/bin/env python3
"""
Encoder ESC/P-R minimal untuk printer Epson (L3110 dkk).

Ini adalah "kembaran" dari kode Kotlin di app/src/main/java/.../escpr/.
Dipakai untuk:
  1. Memverifikasi byte stream tanpa perlu HP / Android SDK.
  2. Menghasilkan file .prn yang bisa dikirim mentah ke printer dari Windows,
     supaya Anda tahu printernya memang menerima ESC/P-R sebelum build APK.

Referensi byte layout: ezrec/python-epson (MIT) + epson-inkjet-printer-escpr (GPL).
Panjang tiap command sudah dicocokkan silang dengan konstanta di driver C Epson.
"""

import struct
import time

ESC = 0x1B

# ---------------------------------------------------------------- konstanta

class Quality:
    DRAFT = 0
    NORMAL = 1
    HIGH = 2

class ColorMode:
    COLOR = 0
    MONO = 1
    SEPIA = 2

class MediaType:
    PLAIN = 0
    MATTE = 5
    PHOTO = 6
    GLOSSY_PHOTO = 43

class PrintDirection:
    BIDIRECTIONAL = 0
    UNIDIRECTIONAL = 1

# lebar x tinggi dalam milimeter
PAPER_SIZES = {
    "A4":       (210.0, 297.0),
    "LETTER":   (215.9, 279.4),
    "LEGAL":    (215.9, 355.6),
    "A5":       (148.0, 210.0),
    "A6":       (105.0, 148.0),
    "B5":       (176.0, 250.0),
    "4R":       (101.6, 152.4),   # 4x6 inch
    "POSTCARD": (100.0, 148.0),
}

# kode "ir" pada perintah setj
_IR = {360: 0, 720: 1, 300: 2, 600: 3}


def dpi_code(dpi: int) -> int:
    if dpi not in _IR:
        raise ValueError(f"dpi harus salah satu dari {sorted(_IR)}, bukan {dpi}")
    return _IR[dpi]


# ------------------------------------------------------------------ helper

def _u8(v: int) -> bytes:
    return bytes([v & 0xFF])


def _clamp_signed(v: int) -> int:
    """Clamp ke -50..50 lalu jadikan byte (two's complement)."""
    return max(-50, min(50, v)) & 0xFF


def run_length_encode(line: bytes, bpp: int = 3) -> bytes:
    """
    RLE ala ESC/P-R, beroperasi per-piksel (bukan per-byte).

    Byte penghitung n:
        n <= 127  -> (n + 1) piksel literal menyusul
        n >= 128  -> piksel berikutnya diulang (257 - n) kali
    """
    if len(line) % bpp:
        raise ValueError("panjang baris bukan kelipatan bytes-per-pixel")

    out = bytearray()
    n = len(line)
    i = 0
    while i < n:
        cur = line[i:i + bpp]

        # hitung berapa kali piksel ini berulang (maks 129)
        rep = 1
        j = i + bpp
        while j + bpp <= n and rep < 129 and line[j:j + bpp] == cur:
            rep += 1
            j += bpp

        if rep >= 2:
            out.append(257 - rep)          # 128..255
            out += cur
            i = j
            continue

        # tidak berulang -> kumpulkan literal run (maks 128 piksel),
        # berhenti tepat sebelum run berulang berikutnya dimulai
        cnt = 1
        j = i + bpp
        while j + bpp <= n and cnt < 128:
            nxt = line[j:j + bpp]
            if j + 2 * bpp <= n and line[j + bpp:j + 2 * bpp] == nxt:
                break                      # biarkan run itu di-encode sebagai repeat
            cnt += 1
            j += bpp
        out.append(cnt - 1)                # 0..127
        out += line[i:j]
        i = j

    return bytes(out)


def run_length_decode(data: bytes, bpp: int = 3) -> bytes:
    """Kebalikan dari run_length_encode -- dipakai untuk self-test."""
    out = bytearray()
    i = 0
    while i < len(data):
        n = data[i]
        i += 1
        if n <= 127:
            count = n + 1
            out += data[i:i + count * bpp]
            i += count * bpp
        else:
            count = 257 - n
            out += data[i:i + bpp] * count
            i += bpp
    return bytes(out)


# ------------------------------------------------------------- ESC/P dasar

EXIT_PACKET_MODE = b"\x00\x00\x00\x1b\x01@EJL 1284.4\n@EJL     \n"
INIT_PRINTER = b"\x1b@"
ENTER_REMOTE_MODE = b"\x1b(R\x08\x00\x00REMOTE1"
EXIT_REMOTE_MODE = b"\x1b\x00\x00\x00"
ENTER_ESCPR_MODE = b"\x1b(R\x06\x00\x00ESCPR"


def remote_cmd(cmd: bytes, data: bytes = b"") -> bytes:
    """Perintah REMOTE1: <2 huruf> <len+1 LE16> <response=0> <data>"""
    return cmd + struct.pack("<H", len(data) + 1) + b"\x00" + data


def raster_cmd(letter: bytes, code: bytes, data: bytes = b"") -> bytes:
    """Perintah ESC/P-R: ESC <huruf> <len LE32> <kode 4 huruf> <data>"""
    return bytes([ESC]) + letter + struct.pack("<I", len(data)) + code + data


# --------------------------------------------------------- perintah tingkat job

def cmd_time_init(t=None) -> bytes:
    t = t or time.localtime()
    data = struct.pack(">HBBBBB", t.tm_year, t.tm_mon, t.tm_mday,
                       t.tm_hour, t.tm_min, t.tm_sec)
    return remote_cmd(b"TI", data)


def cmd_job_start() -> bytes:
    return remote_cmd(b"JS", b"\x00\x00\x00")


def cmd_job_header(name: str = "AndroidUsbPrint", job_id: int = 0) -> bytes:
    data = b"\x00" + struct.pack(">I", job_id) + name.encode("ascii", "replace")
    return remote_cmd(b"JH", data)


def cmd_hardware_device(platform: int = 4) -> bytes:
    return remote_cmd(b"HD", b"\x03" + _u8(platform))


def cmd_paper_path(dst: int = 1, src: int = 0) -> bytes:
    """Default dst=1 src=0 = rear feeder, yaitu satu-satunya jalur di L3110."""
    return remote_cmd(b"PP", _u8(dst) + _u8(src))


def cmd_load_defaults() -> bytes:
    return remote_cmd(b"LD")


def cmd_job_end() -> bytes:
    return remote_cmd(b"JE")


def cmd_set_quality(media_type=MediaType.PLAIN, quality=Quality.NORMAL,
                    color_mode=ColorMode.COLOR, brightness=0, contrast=0,
                    saturation=0, palette_mode=0) -> bytes:
    data = (_u8(media_type) + _u8(quality) + _u8(color_mode)
            + _u8(_clamp_signed(brightness))
            + _u8(_clamp_signed(contrast))
            + _u8(_clamp_signed(saturation))
            + _u8(palette_mode)
            + struct.pack(">H", 0))          # panjang palet = 0
    assert len(data) == 9
    return raster_cmd(b"q", b"setq", data)


def cmd_set_job(paper_mm, margin_mm=(3.0, 3.0, 3.0, 3.0), dpi=360,
                direction=PrintDirection.BIDIRECTIONAL):
    """
    margin_mm = (kiri, atas, kanan, bawah)
    Mengembalikan (bytes, printable_width_px, printable_height_px).
    """
    mm2in = 1.0 / 25.4
    import math
    paper_w = math.ceil(paper_mm[0] * mm2in * dpi)
    paper_h = math.ceil(paper_mm[1] * mm2in * dpi)
    m_left = math.floor(margin_mm[0] * mm2in * dpi)
    m_top = math.floor(margin_mm[1] * mm2in * dpi)
    m_right = math.floor(margin_mm[2] * mm2in * dpi)
    m_bottom = math.floor(margin_mm[3] * mm2in * dpi)
    pw = paper_w - m_left - m_right
    ph = paper_h - m_top - m_bottom

    data = struct.pack(">IIHHIIBB", paper_w, paper_h, m_top, m_left,
                       pw, ph, dpi_code(dpi), direction)
    assert len(data) == 22
    return raster_cmd(b"j", b"setj", data), pw, ph


def cmd_start_page() -> bytes:
    return raster_cmd(b"p", b"sttp")


def cmd_page_number(page: int) -> bytes:
    return raster_cmd(b"p", b"setn", _u8(min(page, 99)))


def cmd_end_page(pages_remaining: int = 0) -> bytes:
    return raster_cmd(b"p", b"endp", _u8(min(pages_remaining, 99)))


def cmd_end_job() -> bytes:
    return raster_cmd(b"j", b"endj")


def cmd_send_line(x: int, y: int, line: bytes, compress: bool = True) -> bytes:
    """Satu baris raster RGB (3 byte per piksel) pada posisi (x, y)."""
    payload = run_length_encode(line, 3) if compress else line
    if len(payload) > 0xFFFF:              # RLE membengkak -> kirim mentah
        payload, compress = line, False
    header = struct.pack(">HHBH", x, y, 1 if compress else 0, len(payload))
    return raster_cmd(b"d", b"dsnd", header + payload)


# ------------------------------------------------------------------- job

class EscpRJob:
    """Merakit satu job cetak lengkap ke dalam sebuah stream biner."""

    def __init__(self, out, paper="A4", dpi=360, quality=Quality.NORMAL,
                 color_mode=ColorMode.COLOR, media_type=MediaType.PLAIN,
                 margin_mm=(3.0, 3.0, 3.0, 3.0), job_name="AndroidUsbPrint"):
        self.out = out
        self.paper_mm = PAPER_SIZES[paper] if isinstance(paper, str) else paper
        self.dpi = dpi
        self.quality = quality
        self.color_mode = color_mode
        self.media_type = media_type
        self.margin_mm = margin_mm
        self.job_name = job_name
        self.width = 0
        self.height = 0

    def _w(self, b: bytes):
        self.out.write(b)

    def start(self):
        self._w(EXIT_PACKET_MODE)
        self._w(INIT_PRINTER)

        self._w(ENTER_REMOTE_MODE)
        self._w(cmd_time_init())
        self._w(cmd_job_start())
        self._w(cmd_job_header(self.job_name))
        self._w(cmd_hardware_device())
        self._w(cmd_paper_path())
        self._w(EXIT_REMOTE_MODE)

        self._w(ENTER_ESCPR_MODE)
        self._w(cmd_set_quality(self.media_type, self.quality, self.color_mode))
        job, w, h = cmd_set_job(self.paper_mm, self.margin_mm, self.dpi)
        self._w(job)
        self.width, self.height = w, h
        return w, h

    def page(self, lines, page_no=1, pages_remaining=0, compress=True):
        """`lines` = iterable berisi bytes RGB sepanjang width*3."""
        self._w(cmd_start_page())
        self._w(cmd_page_number(page_no))
        for y, line in enumerate(lines):
            if y >= self.height:
                break
            self._w(cmd_send_line(0, y, line, compress))
        self._w(cmd_end_page(pages_remaining))

    def finish(self):
        self._w(cmd_end_job())
        self._w(INIT_PRINTER)
        self._w(ENTER_REMOTE_MODE)
        self._w(cmd_load_defaults())
        self._w(cmd_job_end())
        self._w(EXIT_REMOTE_MODE)
