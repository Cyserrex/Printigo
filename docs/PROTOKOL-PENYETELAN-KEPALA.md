# Protokol penyetelan kepala cetak (Print Head Alignment)

Direkam dengan USBPcap dari driver Epson resmi pada **Epson L3110**, menjalankan
**Printing Preferences → Maintenance → Print Head Alignment** sampai tuntas.

Belum diterapkan di aplikasi. Ditulis supaya temuannya tidak hilang.

---

## Kenapa ini layak dibuat

L3110 tidak punya layar maupun menu. Tanpa komputer, pemiliknya **tidak bisa
menyetel kepala cetak sama sekali** — dan kepala yang meleset persis penyebab
cetakan tampak berbayang pada mode dua arah.

## Yang membuatnya mungkin

Lembar polanya **digambar printer sendiri**. Dari 4.194.095 byte yang dikirim
driver, hanya **1.553 byte yang bukan nol** — sisanya isian kosong `ESC ( d`.
Tidak ada satu pun perintah raster di sana.

Artinya aplikasi tidak perlu menggambar polanya. Cukup memicunya.

## Dua perintah

| Perintah | Bentuk | Arti |
|---|---|---|
| `DT` | `44 54 03 00 <grup> <sub>` | cetak lembar pola untuk grup itu |
| `DA` | `44 41 04 00 <grup> <sub> <nilai>` | simpan nilai yang dipilih pengguna |

Byte `00` sesudah panjang adalah byte respons, sama seperti perintah REMOTE1
lainnya.

## Urutan yang terekam

**Putaran pertama** — tujuh pola sekaligus dalam satu pekerjaan:

```
REMOTE1
  TI <tanggal jam>
  JS 00 00 00
  SN 00
  MI 01 00 00
  PP 01 00              <- jalur kertas, sama dengan yang dipakai jalur cetak
  DT C0 00
  DT 02 00 · DT 02 01
  DT 04 00 · DT 04 01
  DT 05 00 · DT 05 01
keluar, lalu isian kosong, lalu JE
```

Pengguna melihat lembarnya dan memilih satu nomor per pola. Nilainya dikirim
balik:

```
REMOTE1
  DA C0 00 03
  DA 02 00 06 · DA 02 01 06
  DA 04 00 05 · DA 04 01 05
  DA 05 00 06 · DA 05 01 06
```

**Putaran kedua** — satu pola lagi, dengan susunan yang sama:

```
DT 80 00     lalu     DA 80 00 04
```

## Yang belum diketahui

- Arti `SN` dan `MI`. Disertakan driver, fungsinya belum ditelusuri.
- Rentang nilai `DA` yang sah. Yang terekam 03 sampai 06; besar kemungkinan
  1–9 mengikuti jumlah pola di lembarnya, tetapi **itu belum dipastikan**.
- Apakah `PP 01 00` wajib. Tabel string di driver memuat `PP 01 FE` untuk
  konteks lain yang belum diketahui.

## Kalau diterapkan

Alurnya tidak bisa satu tombol, karena di tengahnya ada manusia yang harus
melihat kertas:

1. Tombol **Cetak lembar penyetelan** → kirim `DT` untuk semua grup
2. Layar berisi delapan pemilih angka, satu per grup, dengan gambar contoh
3. Tombol **Simpan** → kirim `DA` untuk semua grup

Dua hal yang harus dijaga kalau dikerjakan:

**Nilai yang salah membuat cetakan lebih buruk, bukan sekadar tidak membaik.**
Jadi pemilihnya harus memulai dari nilai yang sekarang berlaku — bukan dari
nol — dan harus ada cara membatalkan tanpa menyimpan.

**Rentang nilainya belum dipastikan.** Sebelum mengirim `DA`, batas atas dan
bawahnya perlu diketahui, bukan ditebak. Cara paling murah memastikannya:
cetak lembar polanya dan hitung ada berapa pilihan di sana.
