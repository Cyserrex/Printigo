# Rencana: reset level tinta dan waste ink pad counter

Status: **rencana, belum satu baris pun dikerjakan.** Ditulis setelah v2.2,
menunggu hasil uji perangkat.

Dokumen ini sengaja panjang di bagian risiko dan pendek di bagian kode. Bukan
karena kodenya sulit — justru sebaliknya, kodenya sedikit. Yang sulit adalah
memastikan kode itu tidak merusak printer yang tidak bisa dikembalikan.

---

## Dua permintaan yang sangat berbeda

Keduanya sering disebut dalam satu tarikan napas, padahal jaraknya jauh sekali.

| | Reset level tinta | Reset waste ink pad counter |
|---|---|---|
| Apa yang diubah | Angka perkiraan isi tangki | Penghitung tinta buangan di EEPROM |
| Didukung Epson | **Ya**, ada prosedurnya | Tidak |
| Sudah bisa tanpa aplikasi ini | **Ya**, lewat tombol printer | Tidak |
| Risiko kalau salah | Angka di layar meleset | **Tinta tumpah di dalam printer**, EEPROM rusak |
| Bisa dibatalkan | Ya | Tidak |

Karena itu keduanya **tidak akan digabung dalam satu fitur**, tidak berbagi
tombol, dan tidak dikerjakan dalam versi yang sama.

---

## Bagian 1 — Reset level tinta

### Apa yang sebenarnya terjadi

L3110 tidak punya sensor di dalam tangki tintanya. Angka "sisa tinta" murni
hasil hitungan: printer menghitung berapa tetes yang sudah ia semprotkan sejak
terakhir kali diberitahu "tangki penuh". Jadi reset level tinta bukan menipu
printer — ia memang **satu-satunya cara** printer bisa tahu Anda sudah mengisi.

### Yang perlu diketahui sebelum meminta ini dibuat

**Prosedur resminya sudah ada di printer Anda dan tidak butuh apa pun.** Setelah
mengisi tangki, tekan dan tahan tombol tetesan tinta di panel L3110 sekitar
lima detik. Itu saja.

Jadi fitur ini di aplikasi adalah kenyamanan, bukan kebutuhan. Saya menulis ini
di depan karena kalau Anda tahu prosedur tombolnya, mungkin Anda tidak
membutuhkannya sama sekali — dan fitur yang tidak dibutuhkan tetap punya biaya.

### Kalau tetap dikerjakan

Perintahnya bukan bagian dari ESC/P-R yang dipakai aplikasi ini untuk mencetak.
Ia perintah REMOTE1 milik Epson yang tidak didokumentasikan publik, jadi
langkahnya sama dengan cek nozzle di v2.2: ambil urutan byte dari driver
terbuka, kirim, lihat reaksi printer.

Bedanya dengan cek nozzle: **kalau salah, tidak ada yang memberi tahu.** Cek
nozzle yang gagal terlihat langsung karena kertas tidak keluar. Reset level yang
salah tidak menghasilkan apa-apa yang bisa dilihat sampai berminggu-minggu
kemudian ketika angkanya ternyata meleset.

**Syarat sebelum saya kerjakan:** aplikasi harus lebih dulu bisa **membaca**
level tinta dari balasan status printer. Tanpa itu tidak ada cara memverifikasi
reset berhasil, dan fitur yang tidak bisa diverifikasi tidak layak dikirim.
Pembacaannya sendiri sudah setengah jalan — `parsePrinterStatus` di
[PrinterStatus.kt](../app/src/main/java/com/escpr/usbprint/usb/PrinterStatus.kt)
sudah menguraikan blok TLV `@BDC ST2`; sisa tinta ada di blok yang belum
ditangani.

### Urutan kerjanya

1. Tampilkan sisa tinta di aplikasi (baca saja, tidak mengubah apa pun).
2. Cocokkan angkanya dengan yang dilaporkan Windows. Kalau cocok, pembacaan
   terbukti.
3. Baru tambahkan tombol reset, dengan verifikasi: baca sebelum, reset, baca
   sesudah, tampilkan keduanya.

Langkah 1 dan 2 berguna sendiri walau langkah 3 tidak pernah dikerjakan.

---

## Bagian 2 — Reset waste ink pad counter

### Ini hak Anda, dan saya akan membantunya

Printer itu milik Anda. Memperbaiki barang milik sendiri adalah hal yang wajar,
dan menolak membantu hanya akan mendorong Anda ke perkakas bajakan yang jauh
lebih berbahaya daripada kode yang bisa Anda baca sendiri.

Tapi saya tidak akan menuliskan kodenya berdasarkan tebakan, dan alasannya
teknis, bukan moral.

### Apa yang sebenarnya dihitung

Setiap pembersihan head menyemprotkan tinta ke sebuah **bantalan penyerap** di
dalam printer. Printer menghitung berapa banyak yang sudah masuk ke sana. Ketika
hitungannya mencapai batas, printer berhenti dan menyalakan lampu servis.

Batas itu bukan jebakan dagang. Itu perkiraan kapan bantalannya penuh.

### Kenapa reset tanpa membereskan bantalannya berbahaya

**Bantalan yang penuh tidak berhenti penuh karena penghitungnya dinolkan.**
Tinta berikutnya tetap masuk ke bantalan yang sudah jenuh, lalu meluber:
ke meja, ke lantai, dan ke papan elektronik di dalam printer. Ini bukan
kemungkinan yang jauh — itu memang yang terjadi pada printer yang di-reset
berulang kali tanpa disentuh fisiknya.

Jadi urutan yang benar selalu: **bereskan bantalannya dulu, baru nolkan
penghitungnya.** Reset adalah paruh kedua dari perbaikan, bukan perbaikannya.

Pilihannya: ganti bantalan (suku cadangnya ada), cuci dan keringkan, atau pasang
selang pembuangan ke tangki luar. Untuk L3110 yang paling umum yang terakhir.

### Kenapa saya tidak akan menebak byte-nya

Penghitung itu ada di **EEPROM** printer — memori yang juga menyimpan data
kalibrasi head, koreksi warna, dan nomor seri. Alamatnya berbeda antar model.

Menulis ke alamat yang salah tidak menghasilkan pesan galat. Ia menimpa data
kalibrasi, dan printer yang tadinya hanya butuh reset berubah jadi printer yang
mencetak miring atau tidak menyala sama sekali. **Tidak ada cara
mengembalikannya** kalau isi lamanya tidak pernah dibaca lebih dulu.

Saya tidak punya alamat EEPROM L3110 yang terverifikasi. Menuliskan angka yang
saya kira-kira benar ke dalam kode yang akan Anda jalankan pada printer sungguhan
adalah hal yang tidak akan saya lakukan.

### Jalan yang aman, kalau Anda mau menempuhnya

Tiga tahap, dengan gerbang di antaranya. Tahap berikutnya tidak dimulai sebelum
tahap sebelumnya terbukti.

**Tahap A — hanya membaca.** Buat perintah baca EEPROM, dan **tidak ada perintah
tulis sama sekali di dalam kode**. Baca alamat yang sudah diketahui isinya
(misalnya nomor seri printer, yang bisa dicocokkan dengan stiker di badan
printer). Kalau yang terbaca cocok, berarti protokolnya benar.

Gerbang: tidak lanjut sebelum ada nilai yang bisa dicocokkan dari luar.

**Tahap B — memetakan.** Baca seluruh wilayah EEPROM, simpan ke berkas.
Lakukan satu pembersihan head, baca lagi, bandingkan. **Alamat yang angkanya
naik itulah penghitungnya** — ditemukan lewat pengamatan, bukan lewat daftar
alamat dari internet yang tidak bisa Anda verifikasi.

Gerbang: alamatnya harus naik lagi pada pembersihan kedua. Sekali bisa
kebetulan.

**Tahap C — menulis.** Baru di sini ada perintah tulis, dengan syarat mutlak:
salinan penuh EEPROM tersimpan di HP lebih dulu, aplikasi menolak menulis tanpa
salinan itu, hanya alamat hasil Tahap B yang boleh ditulis, dan ada peringatan
tegas bahwa bantalannya harus sudah dibereskan.

### Kalau Anda mau hasilnya sekarang

Jalan tercepat bukan aplikasi ini: perkakas reset Epson untuk L3110 sudah ada
dan sudah teruji orang banyak. Kekurangannya Anda tidak bisa membaca isinya.

Kalau printernya sedang jadi satu-satunya printer yang Anda punya dan sedang
dipakai untuk hal penting, pakai perkakas yang sudah teruji. Tahap A sampai C di
atas ada gunanya kalau Anda memang ingin memahami dan mengendalikan sendiri apa
yang ditulis ke printer Anda — dan itu alasan yang sah.

---

## Ringkas

| | Kapan | Syarat |
|---|---|---|
| Baca sisa tinta | Bisa segera | — |
| Reset level tinta | Setelah pembacaan terbukti | Bandingkan dengan Windows |
| EEPROM: baca | Setelah semua di atas | Nomor seri cocok |
| EEPROM: petakan | Setelah baca terbukti | Alamat naik dua kali berturut-turut |
| EEPROM: tulis | Terakhir, dan tidak harus | Bantalan sudah dibereskan, salinan EEPROM tersimpan |

Yang paling saya sarankan dikerjakan lebih dulu adalah baris pertama. Ia berguna
setiap hari, tidak berisiko sama sekali, dan menjadi dasar untuk semua yang lain.
