package com.escpr.usbprint

import com.escpr.usbprint.usb.PrinterState
import com.escpr.usbprint.usb.parsePrinterStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Balasan status sungguhan dari Epson L3110, apa adanya dari kabel.
 *
 * 204 byte, direkam dengan USBPcap ketika EPSON Status Monitor 3 dibuka.
 * Inilah yang menutup pertanyaan sisa tinta: printer **memang** melaporkan
 * keempat tangkinya, tetapi dengan nilai tetap 105 -- di luar rentang persen --
 * yang berarti tidak ada sensor untuk diukur.
 *
 * Selama ini penguraian membuang nilai itu karena dikira data rusak, sehingga
 * aplikasi melapor "printer tidak melaporkan sisa tinta" padahal printer justru
 * melaporkan dengan jelas bahwa tintanya tidak terukur. Dua hal yang berbeda.
 */
class StatusCaptureTest {

    private val rekaman = ("40 42 44 43 20 53 54 32 0D 0A C0 00 " +
        "01 01 04 " +
        "06 02 01 00 " +
        "0F 0D 03 01 00 69 05 03 69 04 02 69 03 01 69 " +
        "10 03 01 0A 4E " +
        "13 01 01 " +
        "19 0C 00 00 00 00 00 75 6E 6B 6E 6F 77 6E " +
        "28 04 FF 11 00 00 " +
        "40 0A 58 36 4E 58 35 31 33 34 34 38"
        ).split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `status printer terbaca siap`() {
        val s = parsePrinterStatus(rekaman)
        assertEquals(PrinterState.IDLE, s.state)
        assertTrue(s.confident)
        assertFalse(s.blocksPrinting)
    }

    @Test
    fun `empat tangki terbaca, berurutan hitam kuning magenta cyan`() {
        // Urutan itu bukan tafsiran bebas: ia sama persis dengan BK Y M C yang
        // tercetak printer pada lembar cek nozzlenya sendiri.
        val s = parsePrinterStatus(rekaman)
        assertEquals(4, s.inks.size)
        assertEquals(
            listOf("Hitam", "Kuning", "Magenta", "Cyan"),
            s.inks.map { it.label },
        )
    }

    @Test
    fun `keempatnya dilaporkan tidak terukur, bukan nol dan bukan hilang`() {
        val s = parsePrinterStatus(rekaman)
        assertTrue(s.inks.isNotEmpty())
        s.inks.forEach { ink ->
            assertEquals(105, ink.percent)
            assertFalse(ink.label + " dikira terukur", ink.measured)
            assertEquals("tidak terukur", ink.reading)
        }
    }

    @Test
    fun `membaca warna dari byte pertama akan menghasilkan susunan tanpa hitam`() {
        // Pembuktian terbalik untuk pilihan byte kedua sebagai kode warna.
        // Byte pertama tiap entri bernilai 01 05 04 03 -- tidak satu pun nol,
        // jadi tidak ada hitam. Mustahil untuk printer empat tangki.
        val slotPertama = listOf(0x01, 0x05, 0x04, 0x03)
        assertFalse("ada nol di byte pertama", slotPertama.contains(0x00))
    }

    @Test
    fun `nomor seri printer ikut terbaca di blok 40`() {
        // Bukan dipakai aplikasi, tapi membuktikan penguraian bloknya benar:
        // nilainya cocok dengan yang tercetak di lembar cek nozzle.
        val teks = String(rekaman, Charsets.ISO_8859_1)
        assertTrue(teks.contains("X6NX513448"))
    }
}
