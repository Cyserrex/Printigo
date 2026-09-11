package com.escpr.usbprint

import com.escpr.usbprint.escpr.ColorMode
import com.escpr.usbprint.escpr.Dpi
import com.escpr.usbprint.escpr.MediaType
import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.escpr.PrintPreset
import com.escpr.usbprint.escpr.PrintSettings
import com.escpr.usbprint.escpr.presetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preset cetak.
 *
 * Dua hal yang dijaga di sini. Pertama, preset hanya boleh menyentuh cara
 * mencetak dan tidak boleh menyentuh apa yang dicetak -- mengganti preset yang
 * diam-diam mengubah ukuran kertas orang adalah kerusakan, bukan kemudahan.
 * Kedua, preset diturunkan dari setelan; begitu satu nilai diubah sendiri,
 * tidak boleh ada preset yang tetap menyala seolah semuanya masih serasi.
 */
class PrintPresetTest {

    @Test
    fun `bawaan aplikasi adalah cetak biasa`() {
        // Bawaan yang tidak cocok preset mana pun berarti pengguna baru
        // langsung melihat "setelan campuran" sebelum menyentuh apa pun.
        assertEquals(PrintPreset.EVERYDAY, presetOf(PrintSettings()))
    }

    @Test
    fun `tiap preset dikenali kembali setelah diterapkan`() {
        PrintPreset.entries.forEach { preset ->
            val hasil = preset.applyTo(PrintSettings())
            assertEquals(preset, presetOf(hasil))
        }
    }

    @Test
    fun `preset tidak menyentuh apa yang dicetak`() {
        val pilihanPengguna = PrintSettings(
            paper = PaperSize.PHOTO_4R,
            marginMm = 12f,
            colorMode = ColorMode.MONO,
            copies = 5,
        )
        PrintPreset.entries.forEach { preset ->
            val hasil = preset.applyTo(pilihanPengguna)
            assertEquals("kertas berubah", pilihanPengguna.paper, hasil.paper)
            assertEquals("margin berubah", pilihanPengguna.marginMm, hasil.marginMm, 0f)
            assertEquals("warna berubah", pilihanPengguna.colorMode, hasil.colorMode)
            assertEquals("salinan berubah", pilihanPengguna.copies, hasil.copies)
        }
    }

    @Test
    fun `mengubah satu nilai sendiri memadamkan seluruh preset`() {
        val biasa = PrintPreset.EVERYDAY.applyTo(PrintSettings())
        assertNull(presetOf(biasa.copy(dpi = Dpi.DPI720)))
        assertNull(presetOf(biasa.copy(mediaType = MediaType.GLOSSY_PHOTO)))
    }

    @Test
    fun `pasangan kertas foto dengan resolusi rendah bukan preset mana pun`() {
        // Justru pasangan semacam inilah yang dulu merusak cetakan: jenis
        // kertas tidak cocok dengan yang benar-benar dimuat. Preset tidak boleh
        // mengaku serasi untuk campuran seperti ini.
        val campuran = PrintSettings(mediaType = MediaType.MATTE, dpi = Dpi.DPI300)
        assertNull(presetOf(campuran))
    }

    @Test
    fun `cetak biasa memakai kertas biasa, kualitas foto tidak`() {
        assertEquals(MediaType.PLAIN, PrintPreset.EVERYDAY.mediaType)
        assertTrue(PrintPreset.PHOTO.mediaType != MediaType.PLAIN)
    }

    @Test
    fun `cetak biasa mengirim data jauh lebih sedikit`() {
        val biasa = PrintPreset.EVERYDAY.dpi.value
        val foto = PrintPreset.PHOTO.dpi.value
        // Luas berbanding kuadrat resolusi: bedanya bukan dua kali, tapi empat.
        assertTrue("foto seharusnya jauh lebih rapat", foto >= biasa * 2)
    }

    @Test
    fun `kedua preset memakai arah cetak yang sama`() {
        // Berbayang yang pernah terjadi terbukti berasal dari jenis kertas,
        // bukan arah cetak. Menjadikan preset foto satu arah berarti
        // melipatduakan waktu tunggu demi menutup sebab yang bukan penyebabnya.
        assertEquals(PrintPreset.EVERYDAY.direction, PrintPreset.PHOTO.direction)
    }

    @Test
    fun `menerapkan preset yang sudah berlaku tidak mengubah apa pun`() {
        val biasa = PrintPreset.EVERYDAY.applyTo(PrintSettings())
        assertEquals(biasa, PrintPreset.EVERYDAY.applyTo(biasa))
    }

    @Test
    fun `tiap preset punya penjelasan yang tidak kosong`() {
        PrintPreset.entries.forEach { preset ->
            assertTrue(preset.name, preset.label.isNotBlank())
            assertTrue(preset.name, preset.hint.isNotBlank())
        }
    }

    @Test
    fun `label preset tidak ada yang kembar`() {
        val label = PrintPreset.entries.map { it.label }
        assertEquals(label.size, label.distinct().size)
    }
}
