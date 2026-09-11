package com.escpr.usbprint

import com.escpr.usbprint.escpr.Maintenance
import com.escpr.usbprint.escpr.MaintenanceTask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte perintah perawatan dipatok persis.
 *
 * Tidak ada satu pun uji di sini yang membuktikan printer akan menurut -- itu
 * hanya bisa dibuktikan oleh printernya. Yang dijaga uji ini adalah bahwa byte
 * yang keluar tidak berubah diam-diam. Kalau nanti terbukti L3110 butuh bentuk
 * yang lain, uji ini yang harus ikut diubah dengan sadar, bukan angka yang
 * bergeser tanpa ada yang tahu.
 */
class MaintenanceTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `cek nozzle memakai urutan yang dipatok`() {
        val bytes = Maintenance.nozzleCheck()
        assertEquals(
            "00 00 00 1B 01 40 45 4A 4C 20 31 32 38 34 2E 34 0A 40 45 4A 4C 20 20 20 20 20 0A " +
                "1B 40 1B 40 1B 28 52 08 00 00 52 45 4D 4F 54 45 31 4E 43 01 00 00 1B 00 00 00",
            hex(bytes),
        )
    }

    @Test
    fun `pembersihan head hanya berbeda pada dua huruf perintahnya`() {
        val nozzle = hex(Maintenance.nozzleCheck())
        val clean = hex(Maintenance.headCleaning())
        // 4E 43 = "NC", 43 48 = "CH". Selebihnya harus identik: kalau ada beda
        // lain, salah satu urutannya sudah tergeser.
        assertEquals(nozzle.replace("4E 43", "43 48"), clean)
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
            assertTrue(task.name, hex.endsWith("1B 00 00 00"))
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
        assertTrue(MaintenanceTask.HEAD_CLEANING.confirmation.contains("tinta"))
    }

    @Test
    fun `perintah tetap pendek`() {
        // Puluhan byte, bukan ribuan: kalau tiba-tiba membengkak, ada data
        // halaman yang ikut masuk tanpa sengaja.
        MaintenanceTask.entries.forEach { task ->
            assertTrue(task.name, task.bytes().size < 100)
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
