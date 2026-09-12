package com.escpr.usbprint.escpr

import androidx.annotation.StringRes
import com.escpr.usbprint.R

/**
 * Dua cara mencetak yang mencakup hampir semua kebutuhan.
 *
 * Sebelum ini ada empat baris chip teknis di layar -- kualitas, resolusi, jenis
 * kertas, arah cetak -- dan keempatnya sama menonjol, sehingga orang merasa
 * harus memutuskan keempatnya. Padahal keempat pilihan itu bergerak bersama:
 * mencetak di kertas HVS berarti resolusi rendah, kualitas biasa, dan jenis
 * kertas biasa sekaligus.
 *
 * Menggabungkannya bukan sekadar menyederhanakan tampilan. Ia menutup satu
 * kesalahan yang sungguh terjadi: memilih jenis kertas **Matte** sementara yang
 * dimuat HVS biasa membuat printer menyemprot tinta jauh lebih banyak daripada
 * yang bisa diserap, dan hasilnya berbayang. Dengan preset, jenis kertas selalu
 * ikut berganti bersama yang lain, jadi pasangan yang mustahil itu tidak bisa
 * terbentuk tanpa sengaja.
 *
 * Arah cetak sengaja sama-sama dua arah. Berbayang yang kemarin terjadi
 * terbukti berasal dari jenis kertas, bukan dari arah cetak, dan menjadikan
 * preset foto satu arah berarti melipatduakan waktu tunggu demi menutup sebab
 * yang sudah terbukti bukan penyebabnya.
 */
enum class PrintPreset(
    @StringRes val label: Int,
    @StringRes val hint: Int,
    val quality: Quality,
    val dpi: Dpi,
    val mediaType: MediaType,
    val direction: PrintDirection,
) {
    EVERYDAY(
        label = R.string.preset_everyday,
        hint = R.string.preset_everyday_hint,
        quality = Quality.NORMAL,
        dpi = Dpi.DPI300,
        mediaType = MediaType.PLAIN,
        direction = PrintDirection.BIDIRECTIONAL,
    ),
    PHOTO(
        label = R.string.preset_photo,
        hint = R.string.preset_photo_hint,
        quality = Quality.HIGH,
        dpi = Dpi.DPI600,
        mediaType = MediaType.PHOTO,
        direction = PrintDirection.BIDIRECTIONAL,
    );

    /**
     * Menerapkan preset tanpa menyentuh pilihan lain.
     *
     * Ukuran kertas, margin, warna, dan jumlah salinan sengaja tidak ikut:
     * itu keputusan tentang **apa** yang dicetak, sedangkan preset tentang
     * **bagaimana** mencetaknya. Mengganti preset tidak boleh diam-diam
     * mengubah ukuran kertas yang sudah dipilih orang.
     */
    fun applyTo(settings: PrintSettings): PrintSettings = settings.copy(
        quality = quality,
        dpi = dpi,
        mediaType = mediaType,
        direction = direction,
    )

    fun matches(settings: PrintSettings): Boolean =
        settings.quality == quality &&
            settings.dpi == dpi &&
            settings.mediaType == mediaType &&
            settings.direction == direction
}

/**
 * Preset mana yang sedang berlaku, atau null kalau setelannya campuran.
 *
 * Diturunkan dari setelan, bukan disimpan tersendiri. Kalau disimpan
 * tersendiri, tombol preset dan setelan lanjutan bisa saling bertentangan --
 * tombol menyala "Cetak biasa" sementara resolusinya sudah diubah jadi 720 dpi
 * di bawah. Dengan diturunkan, keadaan itu mustahil: begitu satu nilai diubah
 * sendiri, tidak ada preset yang menyala.
 */
fun presetOf(settings: PrintSettings): PrintPreset? =
    PrintPreset.entries.firstOrNull { it.matches(settings) }
