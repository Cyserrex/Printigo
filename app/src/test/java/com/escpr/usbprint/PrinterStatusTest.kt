package com.escpr.usbprint

import com.escpr.usbprint.usb.PrinterFault
import com.escpr.usbprint.usb.PrinterState
import com.escpr.usbprint.usb.parsePrinterStatus
import com.escpr.usbprint.R
import com.escpr.usbprint.util.uiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Status printer memutuskan apakah pencetakan dihentikan sebelum dimulai.
 *
 * Karena itu yang diuji bukan hanya penguraian yang berhasil, tetapi juga
 * bahwa balasan yang tidak dipahami tidak pernah menghalangi: memblokir
 * pencetakan yang sebenarnya baik-baik saja lebih merugikan daripada
 * meneruskan lalu gagal seperti sebelumnya.
 */
class PrinterStatusTest {

    /** Menyusun balasan @BDC ST2 dari sejumlah blok <id><panjang><isi>. */
    private fun reply(vararg blocks: Pair<Int, ByteArray>): ByteArray {
        val body = blocks.flatMap { (id, payload) ->
            listOf(id.toByte(), payload.size.toByte()) + payload.toList()
        }
        val header = "@BDC ST2\r\n".toByteArray(Charsets.ISO_8859_1)
        val length = byteArrayOf(
            (body.size and 0xFF).toByte(),
            ((body.size shr 8) and 0xFF).toByte(),
        )
        return header + length + body.toByteArray()
    }

    @Test
    fun `printer siap tidak menghalangi`() {
        val status = parsePrinterStatus(reply(0x01 to byteArrayOf(0x04)))
        assertEquals(PrinterState.IDLE, status.state)
        assertEquals(PrinterFault.NONE, status.fault)
        assertTrue(status.confident)
        assertFalse(status.blocksPrinting)
        assertNull(status.message)
    }

    @Test
    fun `kertas habis dikenali dan menghalangi`() {
        val status = parsePrinterStatus(
            reply(0x01 to byteArrayOf(0x00), 0x02 to byteArrayOf(0x04))
        )
        assertEquals(PrinterState.ERROR, status.state)
        assertEquals(PrinterFault.PAPER_OUT, status.fault)
        assertTrue(status.blocksPrinting)
        assertEquals(uiText(R.string.printer_msg_paper_out), status.message)
    }

    @Test
    fun `tutup terbuka dikenali`() {
        val status = parsePrinterStatus(
            reply(0x01 to byteArrayOf(0x00), 0x02 to byteArrayOf(0x0A))
        )
        assertEquals(PrinterFault.COVER_OPEN, status.fault)
        assertTrue(status.blocksPrinting)
    }

    @Test
    fun `kode kesalahan asing dilaporkan apa adanya, bukan ditebak`() {
        val status = parsePrinterStatus(
            reply(0x01 to byteArrayOf(0x00), 0x02 to byteArrayOf(0x7E))
        )
        assertEquals(PrinterFault.UNRECOGNIZED, status.fault)
        assertEquals(0x7E, status.faultCode)
        assertEquals(uiText(R.string.printer_msg_fault_code, 126), status.message)
    }

    @Test
    fun `blok yang tidak dikenal dilewati, bukan menggagalkan sisanya`() {
        val status = parsePrinterStatus(
            reply(
                0x0F to byteArrayOf(1, 2, 3, 4),
                0x01 to byteArrayOf(0x05),
            )
        )
        assertEquals(PrinterState.PAUSED, status.state)
        assertTrue(status.confident)
        assertNotNull(status.message)
    }

    @Test
    fun `dijeda bukan kesalahan, jadi tidak menghalangi`() {
        val status = parsePrinterStatus(reply(0x01 to byteArrayOf(0x05)))
        assertFalse(status.blocksPrinting)
    }

    @Test
    fun `balasan kosong tidak pernah menghalangi`() {
        val status = parsePrinterStatus(ByteArray(0))
        assertFalse(status.confident)
        assertFalse(status.blocksPrinting)
        assertEquals(PrinterState.UNKNOWN, status.state)
    }

    @Test
    fun `balasan asing tidak dianggap sehat maupun rusak`() {
        val status = parsePrinterStatus("halo dari printer".toByteArray())
        assertFalse(status.confident)
        assertFalse(status.blocksPrinting)
    }

    @Test
    fun `blok terpotong di tengah tidak melempar`() {
        val whole = reply(0x01 to byteArrayOf(0x04), 0x02 to byteArrayOf(0x04))
        for (cut in 0..whole.size) {
            val status = parsePrinterStatus(whole.copyOf(cut))
            // Yang diperiksa hanya bahwa tidak ada yang meledak; hasilnya boleh
            // apa saja selama panjangnya tidak utuh.
            assertNotNull(status)
        }
    }

    // ------------------------------------------------- balasan bentuk teks

    /** Balasan sungguhan dari Epson L3110, disalin apa adanya dari perangkat. */
    private val l3110 = byteArrayOf(
        0x40, 0x42, 0x44, 0x43, 0x20, 0x53, 0x54, 0x0D,
        0x0A, 0x53, 0x54, 0x3A, 0x30, 0x34, 0x3B, 0x0C,
    )

    @Test
    fun `balasan teks dari L3110 sungguhan terbaca siap`() {
        val status = parsePrinterStatus(l3110)
        assertEquals(PrinterState.IDLE, status.state)
        assertTrue(status.confident)
        assertFalse(status.blocksPrinting)
    }

    @Test
    fun `ER nol pada bentuk teks berarti tidak ada galat`() {
        // Kode yang sama pada blok biner berarti galat fatal. Kalau keduanya
        // disamakan, printer sehat yang rajin melaporkan ER:00 akan membuat
        // aplikasi menolak mencetak sama sekali.
        val status = parsePrinterStatus("@BDC ST\r\nST:04;ER:00;".toByteArray())
        assertEquals(PrinterFault.NONE, status.fault)
        assertFalse(status.blocksPrinting)
    }

    @Test
    fun `galat sungguhan pada bentuk teks tetap menghalangi`() {
        val status = parsePrinterStatus("@BDC ST\r\nST:00;ER:04;".toByteArray())
        assertEquals(PrinterFault.PAPER_OUT, status.fault)
        assertTrue(status.blocksPrinting)
    }

    @Test
    fun `bentuk teks yang dijeda dikenali tanpa menghalangi`() {
        val status = parsePrinterStatus("@BDC ST\r\nST:05;".toByteArray())
        assertEquals(PrinterState.PAUSED, status.state)
        assertFalse(status.blocksPrinting)
    }

    @Test
    fun `bentuk biner tetap menang kalau judulnya ST2`() {
        // "@BDC ST2" memuat "@BDC ST" sebagai awalan, jadi urutan pemeriksaan
        // menentukan. Kalau terbalik, semua balasan biner akan diuraikan
        // sebagai teks dan kehilangan seluruh isinya.
        val status = parsePrinterStatus(reply(0x01 to byteArrayOf(0x04)))
        assertEquals(PrinterState.IDLE, status.state)
        assertTrue(status.confident)
    }

    @Test
    fun `catatan mentah dibersihkan dari byte kendali`() {
        val status = parsePrinterStatus(reply(0x01 to byteArrayOf(0x04)))
        assertFalse(status.raw.contains('\r'))
        assertTrue(status.raw.startsWith("@BDC ST2"))
    }
}
