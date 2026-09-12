package com.escpr.usbprint

import com.escpr.usbprint.escpr.Maintenance
import com.escpr.usbprint.escpr.MaintenanceTask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sifat-sifat perintah perawatan.
 *
 * Byte persisnya dijaga [DriverCaptureTest], yang membandingkannya dengan
 * rekaman USB driver Epson sungguhan. Yang diuji di sini sifat yang tetap
 * berlaku apa pun bentuk urutannya: tidak membawa data halaman, tidak
 * membengkak, dan pertanyaan tidak membuka pekerjaan.
 *
 * Pemisahan itu disengaja. Byte persis hanya layak dipatok di satu tempat;
 * kalau dua uji mematok hal yang sama, yang satu pasti tertinggal.
 */
class MaintenanceTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }


    @Test
    fun `bawaan memakai bentuk yang dipakai driver Epson`() {
        // Dibaca dari berkas driver L3110 di Windows: NC 02 00 00 00 ada,
        // NC 01 00 00 tidak ada sama sekali. Cocok dengan hasil di perangkat.
        // Dua sumber bebas menunjuk jawaban yang sama, jadi itulah bawaannya.
        assertEquals(Maintenance.Variant.EXTENDED, Maintenance.DEFAULT_VARIANT)
        assertTrue(hex(Maintenance.nozzleCheck()).contains("4E 43 02 00 00 00"))
        assertTrue(hex(Maintenance.headCleaning()).contains("43 48 02 00 00 00"))
    }


    @Test
    fun `perintah perawatan dibungkus pembuka dan penutup pekerjaan`() {
        // Inilah sebab kertas tertahan: printer menerima perintah di luar
        // pekerjaan mana pun, jadi rutinitas akhir yang mengeluarkan kertas
        // tidak pernah dijalankan. Jalur cetak membungkusnya dengan benar
        // sejak awal, dan jalur cetak tidak pernah menyisakan kertas tertahan.
        listOf(MaintenanceTask.NOZZLE_CHECK, MaintenanceTask.HEAD_CLEANING).forEach { task ->
            val hex = hex(task.bytes())
            assertTrue(task.name + ": tidak ada JS", hex.contains("4A 53"))
            assertTrue(task.name + ": tidak ada JE", hex.contains("4A 45"))
            assertTrue(
                task.name + ": JE harus sesudah perintahnya",
                hex.indexOf("4A 45") > hex.indexOf("4A 53"),
            )
        }
    }

    @Test
    fun `membaca status tidak membuka pekerjaan`() {
        // Menanyakan sesuatu tidak boleh menggerakkan apa pun. Penutup
        // pekerjaan justru yang memicu penanganan kertas.
        val hex = hex(Maintenance.statusRequest())
        assertFalse("permintaan status membuka pekerjaan", hex.contains("4A 53"))
        assertFalse("permintaan status menutup pekerjaan", hex.contains("4A 45"))
    }

    @Test
    fun `bentuk extended menambah satu byte parameter, bukan menggantinya`() {
        val classic = Maintenance.nozzleCheck(Maintenance.Variant.CLASSIC)
        val extended = Maintenance.nozzleCheck(Maintenance.Variant.EXTENDED)
        assertEquals(classic.size + 1, extended.size)
        assertTrue(hex(extended).contains("4E 43 02 00 00 00"))
    }

    @Test
    fun `urutan tidak lagi diakhiri reset printer`() {
        // ESC @ di akhir adalah tersangka kertas yang berhenti separuh keluar:
        // printer melaporkan diri idle sambil menahan kertas, seolah reset
        // membatalkan pengeluaran kertas yang belum sempat terjadi.
        listOf(MaintenanceTask.NOZZLE_CHECK, MaintenanceTask.HEAD_CLEANING).forEach { task ->
            val hex = hex(task.bytes())
            assertTrue(task.name + " masih diakhiri ESC @", !hex.endsWith("1B 40"))
        }
    }

    @Test
    fun `keluarkan kertas hanya form feed, tanpa perintah lain`() {
        val hex = hex(MaintenanceTask.EJECT.bytes())
        assertTrue(hex, hex.endsWith("0C"))
        // Tidak boleh ada remote mode maupun reset: yang diminta cuma kertas
        // maju keluar, bukan printer diatur ulang.
        assertTrue(hex, !hex.contains("52 45 4D 4F 54 45 31"))
        assertTrue(hex, !hex.contains("4E 43"))
    }

    @Test
    fun `perintah REMOTE1 masuk lalu keluar dari remote mode`() {
        listOf(MaintenanceTask.NOZZLE_CHECK, MaintenanceTask.HEAD_CLEANING).forEach { task ->
            val text = hex(task.bytes())
            val enter = text.indexOf("1B 28 52 08 00 00")
            val exit = text.indexOf("1B 00 00 00")
            assertTrue(task.name + ": tidak masuk remote mode", enter >= 0)
            assertTrue(task.name + ": tidak keluar remote mode", exit >= 0)
            assertTrue(task.name + ": keluar sebelum masuk", exit > enter)
        }
    }

    @Test
    fun `tidak ada perintah raster yang ikut terbawa`() {
        // Perintah perawatan tidak boleh membawa data halaman apa pun. Kalau
        // ada kode raster di sini, printer akan menunggu gambar yang tidak
        // pernah datang dan pekerjaan berikutnya ikut kacau.
        MaintenanceTask.entries.forEach { task ->
            val text = String(task.bytes(), Charsets.ISO_8859_1)
            listOf("dsnd", "sttp", "endp", "setj", "endj").forEach { code ->
                assertFalse(task.name + " memuat " + code, text.contains(code))
            }
        }
    }

    @Test
    fun `cek nozzle memakai kertas, pembersihan head tidak`() {
        assertTrue(MaintenanceTask.NOZZLE_CHECK.usesPaper)
        assertFalse(MaintenanceTask.HEAD_CLEANING.usesPaper)
    }

    @Test
    fun `pembersihan head memperingatkan soal tinta sebelum dijalankan`() {
        // Kalimat persetujuannya harus menyebut biayanya. Tanpa itu, orang akan
        // menekannya berkali-kali sampai tinta habis.
        // Isi kalimatnya -- peringatan soal tinta -- diperiksa dalam kedua
        // bahasa oleh TranslationTest; yang dijaga di sini hanya bahwa
        // pembersihan head memang punya kalimat persetujuannya sendiri.
        assertNotEquals(
            MaintenanceTask.NOZZLE_CHECK.confirmation,
            MaintenanceTask.HEAD_CLEANING.confirmation,
        )
    }

    @Test
    fun `perintah tetap pendek`() {
        // Puluhan byte, bukan ribuan: kalau tiba-tiba membengkak, ada data
        // halaman yang ikut masuk tanpa sengaja.
        MaintenanceTask.entries.forEach { task ->
            // Batasnya disesuaikan dengan urutan driver yang terekam: 132 byte
            // untuk cek nozzle. Yang dijaga tetap sama -- puluhan byte, bukan
            // ribuan, supaya data halaman yang ikut masuk langsung ketahuan.
            assertTrue(task.name, task.bytes().size < 200)
        }
    }

    @Test
    fun `bytes selalu menghasilkan larik baru`() {
        val a = MaintenanceTask.NOZZLE_CHECK.bytes()
        val b = MaintenanceTask.NOZZLE_CHECK.bytes()
        assertArrayEquals(a, b)
        a[0] = 0x7F
        assertEquals(0x00, b[0].toInt())
    }
}
