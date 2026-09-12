package com.escpr.usbprint.util

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.escpr.usbprint.R
import java.util.Locale

/**
 * Bahasa aplikasi.
 *
 * [SYSTEM] sengaja jadi bawaan: menebak dari bahasa HP hampir selalu benar, dan
 * pilihan yang tidak perlu diambil adalah pilihan yang tidak bisa salah. Dua
 * pilihan lainnya ada karena tebakan itu tidak selalu benar -- banyak orang
 * memakai HP berbahasa Inggris tetapi lebih paham bahasa Indonesia, dan
 * sebaliknya.
 *
 * Disimpan sebagai nama enum, sama seperti setelan cetak, supaya menambah
 * bahasa ketiga suatu saat tidak menggeser arti nilai yang sudah tersimpan.
 */
enum class AppLanguage(
    @StringRes val label: Int,
    /**
     * Kode bahasa untuk [Locale], atau null kalau mengikuti sistem.
     *
     * "in", bukan "id". Java membekukan kode ISO-639 lama untuk bahasa
     * Indonesia sejak lama dan Android ikut: `Locale("id").language` justru
     * mengembalikan "in", jadi menulis "id" di sini akan membuat pencarian
     * resource meleset tanpa pesan kesalahan apa pun.
     */
    val tag: String?,
) {
    SYSTEM(R.string.language_system, null),
    INDONESIAN(R.string.language_indonesian, "in"),
    ENGLISH(R.string.language_english, "en"),
}

/**
 * Context yang resource-nya berbahasa [language].
 *
 * Dipakai di dua tempat yang berbeda sifatnya: [android.app.Activity.attachBaseContext]
 * supaya seluruh layar memakai bahasa yang dipilih, dan di ViewModel supaya
 * kalimat yang ditulis ke catatan memakai bahasa yang sama dengan layarnya.
 * Tanpa yang kedua, aplikasi berbahasa Inggris akan menulis catatan berbahasa
 * Indonesia ketika bahasa HP-nya Indonesia.
 */
fun localizedContext(base: Context, language: AppLanguage): Context {
    val tag = language.tag ?: return base
    val locale = Locale(tag)
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    return base.createConfigurationContext(config)
}
