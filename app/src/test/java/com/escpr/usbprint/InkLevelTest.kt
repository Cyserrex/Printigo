package com.escpr.usbprint

import com.escpr.usbprint.escpr.Maintenance
import com.escpr.usbprint.usb.parsePrinterStatus
import com.escpr.usbprint.R
import com.escpr.usbprint.util.uiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pembacaan sisa tinta.
 *
 * Nomor blok dan bentuk entrinya diambil dari driver terbuka. **L3110 tidak
 * melaporkan sisa tinta sama sekali** -- printer tangki tidak punya sensor di
 * dalam tangkinya, dan Status Monitor bawaan Epson pun hanya menyuruh melihat
 * tangkinya langsung. Jadi penguraian di sini untuk model berkartrid yang
 * memang melaporkannya, dan belum pernah diverifikasi pada perangkat mana pun.
 *
 * Uji di sini menjaga dua hal yang tetap benar apa pun hasil verifikasi nanti:
 * penguraiannya tidak pernah melempar pada data yang aneh, dan kode warna yang
 * tidak dikenali tidak pernah diberi nama tebakan.
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

    /**
     * Blok tinta dengan susunan yang terbukti: `<ukuran entri>` lalu entri
     * `<slot><warna><nilai>`.
     *
     * Susunannya diambil dari rekaman USB L3110, bukan dikarang -- lihat
     * [StatusCaptureTest]. Nomor slot di sini sekadar pengisi; yang menentukan
     * warna adalah byte kedua.
     */
    private fun inkBlock(vararg entries: Pair<Int, Int>): ByteArray =
        byteArrayOf(3) + entries.flatMapIndexed { index, (code, percent) ->
            listOf((index + 1).toByte(), code.toByte(), percent.toByte())
        }.toByteArray()

    @Test
    fun `empat tangki terbaca dengan namanya`() {
        val status = parsePrinterStatus(
            reply(0x0F to inkBlock(0x00 to 80, 0x01 to 60, 0x02 to 40, 0x03 to 20))
        )
        assertEquals(listOf(80, 60, 40, 20), status.inks.map { it.percent })
        assertEquals(
            listOf(R.string.ink_black, R.string.ink_cyan, R.string.ink_magenta, R.string.ink_yellow),
            status.inks.map { it.name },
        )
        assertTrue(status.confident)
    }

    @Test
    fun `kode warna asing tidak pernah diberi nama tebakan`() {
        // Menyebut cyan sebagai magenta membuat orang mengisi tangki yang salah.
        val status = parsePrinterStatus(reply(0x0F to inkBlock(0x2A to 50)))
        assertNull(status.inks.single().name)
        assertEquals(uiText(R.string.ink_unknown_color, 42), status.inks.single().label)
        assertEquals(50, status.inks.single().percent)
    }

    @Test
    fun `tinta habis terbaca sebagai nol, bukan sebagai tidak terbaca`() {
        val status = parsePrinterStatus(reply(0x0F to inkBlock(0x00 to 0)))
        assertEquals(1, status.inks.size)
        assertEquals(0, status.inks.single().percent)
    }

    @Test
    fun `nilai di luar rentang persen tetap dilaporkan, ditandai tidak terukur`() {
        // Dulu dibuang karena dikira data rusak. Rekaman USB menunjukkan L3110
        // memakai 105 untuk keempat tangkinya sebagai penanda "tidak punya
        // sensor" -- membuangnya membuat aplikasi melapor printer tidak
        // melaporkan tinta, padahal printer melaporkannya dengan jelas.
        val status = parsePrinterStatus(
            reply(0x0F to inkBlock(0x00 to 90, 0x01 to 105, 0x02 to 30))
        )
        assertEquals(listOf(90, 105, 30), status.inks.map { it.percent })
        assertEquals(listOf(true, false, true), status.inks.map { it.measured })
        assertEquals(uiText(R.string.ink_not_measurable), status.inks[1].reading)
        assertEquals(uiText(R.string.ink_percent, 90), status.inks[0].reading)
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
        // Disalin dari rekaman USB driver Epson: OT lalu ST, dan ST berbentuk
        // 01 00 01 -- satu byte isi tanpa byte respons terpisah. Bentuk lama
        // (ST 02 00 00 01) dijawab teks pendek tanpa tinta; bentuk ini dijawab
        // balasan biner 204 byte yang memuat blok tinta.
        assertTrue(hex, hex.contains(
            "52 45 4D 4F 54 45 31 4F 54 02 00 01 01 53 54 01 00 01 1B 00 00 00"))
    }
}
