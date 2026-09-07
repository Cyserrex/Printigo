package com.escpr.usbprint

import androidx.test.core.app.ApplicationProvider
import com.escpr.usbprint.escpr.ColorMode
import com.escpr.usbprint.escpr.Dpi
import com.escpr.usbprint.escpr.MediaType
import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.escpr.PrintSettings
import com.escpr.usbprint.escpr.Quality
import com.escpr.usbprint.util.SettingsStore
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pengaturan yang diingat antar-sesi.
 *
 * Dua hal yang benar-benar penting di sini: jumlah salinan tidak boleh ikut
 * tersimpan, dan nilai tersimpan yang sudah tidak dikenal harus kembali ke
 * bawaan alih-alih membuat aplikasi gagal terbuka.
 */
// Robolectric 4.13 belum mengenal SDK 35; SharedPreferences tidak berubah
// perilakunya di antara keduanya, jadi 34 sudah mewakili.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun store() = SettingsStore(context)

    @Test
    fun `tanpa apa pun tersimpan, hasilnya bawaan`() {
        assertEquals(PrintSettings(), store().load())
    }

    @Test
    fun `pengaturan kembali persis seperti disimpan`() {
        val saved = PrintSettings(
            paper = PaperSize.entries.last(),
            dpi = Dpi.entries.last(),
            quality = Quality.entries.last(),
            colorMode = ColorMode.MONO,
            mediaType = MediaType.entries.last(),
            marginMm = 7.5f,
        )
        store().save(saved)
        assertEquals(saved, store().load())
    }

    @Test
    fun `jumlah salinan sengaja tidak diingat`() {
        store().save(PrintSettings(copies = 9))
        // Mencetak sembilan salinan sekali waktu tidak berarti sembilan lagi
        // besok, dan salah cetak sembilan lembar jauh lebih mahal daripada
        // mengetik ulang angkanya.
        assertEquals(PrintSettings().copies, store().load().copies)
    }

    @Test
    fun `nama enum yang tidak dikenal kembali ke bawaan`() {
        context.getSharedPreferences("cetak", Context.MODE_PRIVATE)
            .edit()
            .putString("paper", "KERTAS_DARI_VERSI_MASA_DEPAN")
            .apply()
        assertEquals(PrintSettings().paper, store().load().paper)
    }

    @Test
    fun `batas cetak tersimpan di luar rentang ditarik ke dalam rentang`() {
        context.getSharedPreferences("cetak", Context.MODE_PRIVATE)
            .edit()
            .putFloat("margin", 900f)
            .apply()
        assertEquals(20f, store().load().marginMm, 0.001f)
    }

    @Test
    fun `menyimpan dua kali menang yang terakhir`() {
        store().save(PrintSettings(colorMode = ColorMode.MONO))
        store().save(PrintSettings(colorMode = ColorMode.COLOR))
        assertEquals(ColorMode.COLOR, store().load().colorMode)
    }
}
