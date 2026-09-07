package com.escpr.usbprint

import com.escpr.usbprint.usb.PrinterFault
import com.escpr.usbprint.usb.PrinterState
import com.escpr.usbprint.usb.parsePrinterStatus
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
        assertTrue(status.message!!.contains("Kertas habis"))
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
        assertTrue(status.message!!.contains("126"))
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

    @Test
    fun `catatan mentah dibersihkan dari byte kendali`() {
        val status = parsePrinterStatus(reply(0x01 to byteArrayOf(0x04)))
        assertFalse(status.raw.contains('\r'))
        assertTrue(status.raw.startsWith("@BDC ST2"))
    }
}
