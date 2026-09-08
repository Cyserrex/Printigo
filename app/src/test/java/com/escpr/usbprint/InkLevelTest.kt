package com.escpr.usbprint

import com.escpr.usbprint.escpr.Maintenance
import com.escpr.usbprint.usb.parsePrinterStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pembacaan sisa tinta.
 *
 * Nomor blok dan bentuk entrinya diambil dari driver terbuka dan belum
 * diverifikasi pada L3110. Uji di sini menjaga dua hal yang tetap benar apa pun
 * hasil verifikasi nanti: penguraiannya tidak pernah melempar pada data yang
 * aneh, dan kode warna yang tidak dikenali tidak pernah diberi nama tebakan.
 */
class InkLevelTest {

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

    /** Blok tinta: <ukuran entri> lalu entri <kode><jenis><persen>. */
    private fun inkBlock(vararg entries: Pair<Int, Int>): ByteArray =
        byteArrayOf(3) + entries.flatMap { (code, percent) ->
            listOf(code.toByte(), 0x01, percent.toByte())
        }.toByteArray()

    @Test
    fun `empat tangki terbaca dengan namanya`() {
        val status = parsePrinterStatus(
            reply(0x0F to inkBlock(0x00 to 80, 0x01 to 60, 0x02 to 40, 0x03 to 20))
        )
        assertEquals(listOf(80, 60, 40, 20), status.inks.map { it.percent })
        assertEquals(
            listOf("Hitam", "Cyan", "Magenta", "Kuning"),
            status.inks.map { it.label },
        )
        assertTrue(status.confident)
    }

    @Test
    fun `kode warna asing tidak pernah diberi nama tebakan`() {
        // Menyebut cyan sebagai magenta membuat orang mengisi tangki yang salah.
        val status = parsePrinterStatus(reply(0x0F to inkBlock(0x2A to 50)))
        assertNull(status.inks.single().name)
        assertEquals("Warna 42", status.inks.single().label)
        assertEquals(50, status.inks.single().percent)
    }

    @Test
    fun `tinta habis terbaca sebagai nol, bukan sebagai tidak terbaca`() {
        val status = parsePrinterStatus(reply(0x0F to inkBlock(0x00 to 0)))
        assertEquals(1, status.inks.size)
        assertEquals(0, status.inks.single().percent)
    }

    @Test
    fun `entri dengan persen di luar nalar dibuang sendirian`() {
        // Satu entri aneh tidak boleh menjatuhkan tiga entri lain yang waras.
        val status = parsePrinterStatus(
            reply(0x0F to inkBlock(0x00 to 90, 0x01 to 200, 0x02 to 30))
        )
        assertEquals(listOf(90, 30), status.inks.map { it.percent })
    }

    @Test
    fun `blok tinta dengan ukuran entri tidak masuk akal diabaikan seluruhnya`() {
        listOf(
            byteArrayOf(0),          // ukuran entri nol
            byteArrayOf(1, 5),       // terlalu kecil untuk kode + persen
            byteArrayOf(99, 1, 2),   // ukuran entri lebih besar dari isinya
        ).forEach { payload ->
            val status = parsePrinterStatus(reply(0x0F to payload))
            assertTrue(payload.joinToString(), status.inks.isEmpty())
        }
    }

    @Test
    fun `tinta dan status hidup berdampingan dalam satu balasan`() {
        val status = parsePrinterStatus(
            reply(
                0x01 to byteArrayOf(0x04),
                0x0F to inkBlock(0x00 to 75),
            )
        )
        assertEquals(75, status.inks.single().percent)
        assertTrue(status.confident)
    }

    @Test
    fun `balasan tanpa blok tinta menghasilkan daftar kosong, bukan nol persen`() {
        // Bedanya penting: kosong berarti tidak dilaporkan, bukan tinta habis.
        val status = parsePrinterStatus(reply(0x01 to byteArrayOf(0x04)))
        assertTrue(status.inks.isEmpty())
    }

    @Test
    fun `blok tinta terpotong di segala panjang tidak melempar`() {
        val whole = reply(0x0F to inkBlock(0x00 to 80, 0x01 to 60))
        for (cut in 0..whole.size) {
            parsePrinterStatus(whole.copyOf(cut))
        }
    }

    @Test
    fun `permintaan status tidak memakai kertas maupun menggerakkan apa pun`() {
        val text = String(Maintenance.statusRequest(), Charsets.ISO_8859_1)
        // Tidak boleh ada perintah cetak maupun perawatan yang ikut terbawa.
        listOf("dsnd", "sttp", "NC", "CH").forEach { code ->
            assertTrue("permintaan status memuat " + code, !text.contains(code))
        }
        assertTrue(text.contains("ST"))
    }

    @Test
    fun `permintaan status memakai urutan yang dipatok`() {
        val hex = Maintenance.statusRequest().joinToString(" ") { "%02X".format(it) }
        assertTrue(hex, hex.contains("52 45 4D 4F 54 45 31 53 54 02 00 00 01 1B 00 00 00"))
    }
}
