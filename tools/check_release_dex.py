"""Memeriksa APK rilis yang sudah dikecilkan R8.

R8 membuang apa yang tampaknya tidak dipakai. Yang dicari lewat refleksi --
terutama konstruktor PrintViewModel(Application) yang dipanggil
AndroidViewModelFactory -- tidak terlihat olehnya. Kalau aturan keep salah
tulis, hilangnya baru ketahuan saat aplikasi dibuka di HP, bukan saat dibangun.
Skrip ini membongkar DEX-nya dan memastikan hal itu memang ada.

Ditulis dengan Python, bukan PowerShell, supaya satu implementasi yang sama
berjalan di komputer maupun di runner GitHub. Dua implementasi yang
berdampingan pasti berbeda perilakunya cepat atau lambat, dan yang berbahaya
adalah kalau yang berbeda itu justru yang berjalan sebelum rilis.

Pemakaian:
    python tools/check_release_dex.py [path/ke/app.apk]

Tanpa argumen, dipakai APK rilis yang paling baru.
"""

import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile


def cari_apk(argv):
    if len(argv) > 1:
        return argv[1]
    kandidat = glob.glob(os.path.join("app", "build", "outputs", "apk", "release", "*.apk"))
    if not kandidat:
        raise SystemExit("Tidak ada APK rilis. Jalankan gradlew assembleRelease dulu.")
    return max(kandidat, key=os.path.getmtime)


def cari_dexdump():
    akar = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    akar = akar or r"C:\Claude\android-sdk"
    pola = os.path.join(akar, "build-tools", "*", "dexdump*")
    kandidat = [p for p in glob.glob(pola) if os.path.isfile(p) and os.access(p, os.X_OK)]
    if not kandidat:
        raise SystemExit("dexdump tidak ada di " + os.path.join(akar, "build-tools"))
    # Versi build-tools tertinggi, diurutkan sebagai angka bukan sebagai teks:
    # "9.0.0" > "35.0.0" kalau dibandingkan sebagai teks.
    def versi(p):
        nama = os.path.basename(os.path.dirname(p))
        return [int(x) if x.isdigit() else 0 for x in nama.split(".")]
    return max(kandidat, key=versi)


def dump(apk, dexdump):
    with tempfile.TemporaryDirectory() as kerja:
        berkas = []
        with zipfile.ZipFile(apk) as z:
            for nama in z.namelist():
                if re.fullmatch(r"classes\d*\.dex", nama):
                    z.extract(nama, kerja)
                    berkas.append(os.path.join(kerja, nama))
        if not berkas:
            raise SystemExit("Tidak ada classes.dex di dalam APK")
        print("DEX ditemukan: %d" % len(berkas))
        hasil = subprocess.run(
            [dexdump, "-d"] + berkas,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )
        return hasil.stdout.decode("utf-8", "replace")


def periksa(teks):
    """Mengembalikan daftar hal yang seharusnya ada tapi tidak ditemukan."""
    gagal = []

    def catat(ada, apa):
        print(("  OK    " if ada else "  HILANG ") + apa)
        if not ada:
            gagal.append(apa)

    # Konstruktor diperiksa DI DALAM blok kelasnya sendiri. Mencari
    # "(Landroid/app/Application;)V" di seluruh dump tidak membuktikan apa-apa:
    # kelas lain mana pun bisa punya tanda tangan yang sama.
    blok = re.split(r"Class descriptor\s*:\s*", teks)

    def blok_kelas(deskriptor):
        for b in blok:
            if b.startswith("'" + deskriptor + "'"):
                return b
        return None

    vm = blok_kelas("Lcom/escpr/usbprint/ui/PrintViewModel;")
    catat(vm is not None, "kelas PrintViewModel")
    if vm is not None:
        catat("(Landroid/app/Application;" in vm,
              "konstruktor PrintViewModel(Application)")

    catat(blok_kelas("Lcom/escpr/usbprint/ui/MainActivity;") is not None, "MainActivity")
    catat(blok_kelas("Lcom/escpr/usbprint/escpr/PaperSize;") is not None,
          "kelas PaperSize tidak berganti nama")

    # Nama enum inilah yang disimpan SettingsStore di SharedPreferences. Kalau
    # teksnya tidak ada lagi di DEX, pengaturan lama tidak akan pernah cocok.
    for nama in ("A4", "LETTER", "PHOTO_4R"):
        catat('"%s"' % nama in teks, "nama enum " + nama)

    # Pilihan bahasa disimpan dengan cara yang sama, tetapi enumnya ada di
    # paket lain -- dan aturan R8-nya sempat tidak menyebut paket itu. Kalau
    # namanya hilang, bahasa pilihan pengguna kembali ke "ikut sistem" setiap
    # kali aplikasi dibuka, hanya pada APK rilis.
    catat(blok_kelas("Lcom/escpr/usbprint/util/AppLanguage;") is not None,
          "kelas AppLanguage tidak berganti nama")
    for nama in ("INDONESIAN", "ENGLISH", "SYSTEM"):
        catat('"%s"' % nama in teks, "nama enum bahasa " + nama)

    return gagal


def main():
    apk = cari_apk(sys.argv)
    if not os.path.exists(apk):
        raise SystemExit("APK tidak ditemukan: " + apk)
    print("Memeriksa: " + os.path.basename(apk))

    gagal = periksa(dump(apk, cari_dexdump()))
    if gagal:
        raise SystemExit("%d hal yang seharusnya dijaga tidak ada di DEX rilis" % len(gagal))
    print("Semua yang dijaga ada di dalam APK rilis.")


if __name__ == "__main__":
    main()
