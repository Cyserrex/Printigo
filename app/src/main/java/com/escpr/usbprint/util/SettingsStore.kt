package com.escpr.usbprint.util

import android.content.Context
import com.escpr.usbprint.escpr.ColorMode
import com.escpr.usbprint.escpr.Dpi
import com.escpr.usbprint.escpr.MediaType
import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.escpr.PrintSettings
import com.escpr.usbprint.escpr.Quality

/**
 * Mengingat pengaturan cetak antar-sesi.
 *
 * Jumlah salinan sengaja tidak disimpan: mencetak sepuluh lembar sekali waktu
 * hampir tidak pernah berarti sepuluh lembar juga besok, dan tidak sengaja
 * mencetak sepuluh salinan jauh lebih merugikan daripada mengatur ulang angka.
 *
 * Nilainya disimpan sebagai nama enum, bukan nomor urut. Nomor urut akan
 * bergeser diam-diam kalau suatu saat ada ukuran kertas baru disisipkan.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("cetak", Context.MODE_PRIVATE)

    fun load(): PrintSettings {
        val default = PrintSettings()
        return PrintSettings(
            paper = read(KEY_PAPER, PaperSize.entries, default.paper),
            dpi = read(KEY_DPI, Dpi.entries, default.dpi),
            quality = read(KEY_QUALITY, Quality.entries, default.quality),
            colorMode = read(KEY_COLOR, ColorMode.entries, default.colorMode),
            mediaType = read(KEY_MEDIA, MediaType.entries, default.mediaType),
            marginMm = prefs.getFloat(KEY_MARGIN, default.marginMm)
                .coerceIn(0f, 20f),
        )
    }

    fun save(settings: PrintSettings) {
        prefs.edit()
            .putString(KEY_PAPER, settings.paper.name)
            .putString(KEY_DPI, settings.dpi.name)
            .putString(KEY_QUALITY, settings.quality.name)
            .putString(KEY_COLOR, settings.colorMode.name)
            .putString(KEY_MEDIA, settings.mediaType.name)
            .putFloat(KEY_MARGIN, settings.marginMm)
            .apply()
    }

    /** Nama yang tidak dikenali -- misalnya sisa versi lama -- kembali ke bawaan. */
    private fun <T : Enum<T>> read(key: String, values: List<T>, fallback: T): T {
        val name = prefs.getString(key, null) ?: return fallback
        return values.firstOrNull { it.name == name } ?: fallback
    }

    private companion object {
        const val KEY_PAPER = "paper"
        const val KEY_DPI = "dpi"
        const val KEY_QUALITY = "quality"
        const val KEY_COLOR = "color"
        const val KEY_MEDIA = "media"
        const val KEY_MARGIN = "margin"
    }
}
