# Aturan R8 untuk Printigo.
#
# Yang dijaga di sini hanyalah hal-hal yang dicari lewat refleksi saat berjalan.
# R8 tidak bisa melihat pemanggilan seperti itu, jadi tanpa aturan ini kodenya
# terbuang diam-diam dan aplikasi baru gagal saat sudah di tangan pengguna --
# bukan saat dibangun.

# AndroidViewModelFactory mencari konstruktor (Application) dengan refleksi.
# Ini persis penyebab aplikasi tidak bisa dibuka di versi 1.5: begitu
# konstruktor itu hilang, pembuatan ViewModel gagal saat layar pertama dibuka.
#
# Catatan jujur: saat aturan ini dicabut lalu APK rilis diperiksa ulang,
# konstruktornya ternyata tetap ada -- androidx.lifecycle sudah membawa aturan
# keep-nya sendiri. Jadi baris ini lapis kedua, bukan satu-satunya penahan.
# Yang benar-benar memastikan tetap tools/check_release_dex.ps1, karena ia
# memeriksa hasilnya di dalam DEX, bukan mengandaikan aturan mana yang bekerja.
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Nama enum ikut disimpan sebagai teks di SharedPreferences oleh SettingsStore,
# lalu dicocokkan kembali dengan Enum.name. Enum.name mengembalikan nama field
# apa adanya, jadi kalau R8 mengganti nama field-nya, pengaturan yang sudah
# tersimpan tidak akan pernah cocok lagi dan diam-diam kembali ke bawaan --
# tanpa satu pun pesan galat. Karena itu namanya dipertahankan, bukan sekadar
# anggotanya. keepnames masih mengizinkan enum yang benar-benar tak terpakai
# dibuang seluruhnya.
-keepnames class com.escpr.usbprint.escpr.** extends java.lang.Enum
-keepclassmembers class com.escpr.usbprint.escpr.** extends java.lang.Enum {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Nama ViewModel dipertahankan supaya jejak tumpukan dari HP pengguna masih
# menyebut kelas yang bisa dicari, dan supaya tools/check_release_dex.ps1 bisa
# benar-benar memastikan konstruktornya ada -- bukan menemukan konstruktor
# milik kelas lain yang kebetulan bertanda tangan sama.
-keepnames class com.escpr.usbprint.ui.PrintViewModel

# Baris berkas dan nomor baris dipertahankan supaya laporan galat masih bisa
# dibaca. Tanpa SourceFile, jejak tumpukan hanya berisi "Unknown Source".
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
