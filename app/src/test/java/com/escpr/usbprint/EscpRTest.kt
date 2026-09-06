package com.escpr.usbprint

import com.escpr.usbprint.escpr.Dpi
import com.escpr.usbprint.escpr.EscpR
import com.escpr.usbprint.escpr.EscpRJob
import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.escpr.PrintSettings
import com.escpr.usbprint.escpr.PrinterSink
import com.escpr.usbprint.escpr.Rle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class EscpRTest {

    private class Recorder : PrinterSink {
        val out = ByteArrayOutputStream()
        override fun write(data: ByteArray, offset: Int, length: Int) =
            out.write(data, offset, length)

        override fun flush() = Unit
        override fun close() = Unit
        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun encode(input: ByteArray): ByteArray {
        val buffer = ByteArray(Rle.maxEncodedSize(input.size))
        val n = Rle.encode(input, input.size, buffer)
        return buffer.copyOf(n)
    }

    // ------------------------------------------------------------ RLE

    @Test
    fun `rle bolak-balik pada berbagai pola`() {
        val random = Random(7)
        val cases = listOf(
            ByteArray(2480 * 3) { -1 },                              // semua putih
            ByteArray(3000) { random.nextInt(256).toByte() },         // acak
            ByteArray(3000) { if ((it / 3) % 2 == 0) 0 else -1 },     // selang-seling
            ByteArray(15000) { byteArrayOf(0x12, 0x34, 0x56)[it % 3] },
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        )
        for (case in cases) {
            val encoded = encode(case)
            assertArrayEquals(case, Rle.decode(encoded, encoded.size))
        }
    }

    @Test
    fun `putih polos terkompresi sangat rapat`() {
        val white = ByteArray(2480 * 3) { -1 }
        assertTrue("RLE harus jauh mengecil", encode(white).size < white.size / 50)
    }

    @Test
    fun `batas penghitung rle`() {
        // 129 pengulangan adalah maksimum satu blok: penghitung 0x80 (= 257 - 129).
        val repeated = ByteArray(129 * 3) { byteArrayOf(1, 2, 3)[it % 3] }
        val encoded = encode(repeated)
        assertEquals(4, encoded.size)
        assertEquals(128, encoded[0].toInt() and 0xFF)

        // 130 pengulangan tidak muat, jadi harus pecah menjadi dua blok.
        val overflow = ByteArray(130 * 3) { byteArrayOf(1, 2, 3)[it % 3] }
        assertEquals(8, encode(overflow).size)

        // 128 piksel unik adalah maksimum satu blok literal: penghitung 0x7F.
        val unique = ByteArray(128 * 3) { i ->
            when (i % 3) {
                0 -> (i / 3).toByte()
                1 -> 0
                else -> (255 - i / 3).toByte()
            }
        }
        val uniqueEncoded = encode(unique)
        assertEquals(127, uniqueEncoded[0].toInt() and 0xFF)
        assertEquals(1 + 128 * 3, uniqueEncoded.size)
        assertArrayEquals(unique, Rle.decode(uniqueEncoded, uniqueEncoded.size))
    }

    // ------------------------------------------- panjang perintah ESC/P-R

    @Test
    fun `panjang perintah cocok dengan driver resmi epson`() {
        // epson-inkjet-printer-escpr: JobCmd = 1B 6A 16 00 00 00 "setj" + 22 byte
        val setj = EscpR.setJob(2977, 4210, 42, 42, 2893, 4126, Dpi.DPI360)
        assertEquals(32, setj.size)
        assertArrayEquals(byteArrayOf(0x16, 0, 0, 0), setj.copyOfRange(2, 6))

        // PrintQualityCmd = 1B 71 09 00 00 00 "setq" + 9 byte
        val setq = EscpR.setQuality(
            com.escpr.usbprint.escpr.MediaType.PLAIN,
            com.escpr.usbprint.escpr.Quality.NORMAL,
            com.escpr.usbprint.escpr.ColorMode.COLOR
        )
        assertEquals(19, setq.size)
        assertArrayEquals(byteArrayOf(0x09, 0, 0, 0), setq.copyOfRange(2, 6))

        // StartPage = 1B 70 00 00 00 00 's' 't' 't' 'p'
        assertArrayEquals(
            byteArrayOf(0x1B, 0x70, 0, 0, 0, 0, 0x73, 0x74, 0x74, 0x70),
            EscpR.startPage()
        )
        // EndPage = 1B 70 01 00 00 00 'e' 'n' 'd' 'p' <sisa halaman>
        assertArrayEquals(
            byteArrayOf(0x1B, 0x70, 0x01, 0, 0, 0, 0x65, 0x6E, 0x64, 0x70, 0),
            EscpR.endPage(0)
        )
        // EndJob = 1B 6A 00 00 00 00 'e' 'n' 'd' 'j'
        assertArrayEquals(
            byteArrayOf(0x1B, 0x6A, 0, 0, 0, 0, 0x65, 0x6E, 0x64, 0x6A),
            EscpR.endJob()
        )
        // RemoteJS = 'J' 'S' 04 00 00 00 00 00
        assertArrayEquals(
            byteArrayOf(0x4A, 0x53, 0x04, 0, 0, 0, 0, 0),
            EscpR.jobStart()
        )
        // Masuk dan keluar REMOTE1
        assertArrayEquals(
            byteArrayOf(0x1B, 0x28, 0x52, 0x08, 0, 0,
                        0x52, 0x45, 0x4D, 0x4F, 0x54, 0x45, 0x31),
            EscpR.ENTER_REMOTE_MODE
        )
        assertArrayEquals(byteArrayOf(0x1B, 0, 0, 0), EscpR.EXIT_REMOTE_MODE)
    }

    // ---------------------------------------------------------- geometri

    @Test
    fun `geometri a4 pada 360 dpi`() {
        val geometry = EscpRJob.computeGeometry(
            PrintSettings(paper = PaperSize.A4, dpi = Dpi.DPI360, marginMm = 3f)
        )
        // 210 mm -> ceil(2976,4) = 2977 px; margin 3 mm -> floor(42,5) = 42 px
        assertEquals(2977, geometry.paperWidth)
        assertEquals(4210, geometry.paperHeight)
        assertEquals(2977 - 84, geometry.printableWidth)
        assertEquals(4210 - 84, geometry.printableHeight)
    }

    // -------------------------------------------------------------- job

    @Test
    fun `urutan perintah satu job utuh`() {
        val recorder = Recorder()
        val settings = PrintSettings(paper = PaperSize.A6, dpi = Dpi.DPI300)
        val job = EscpRJob(recorder, settings)
        val geometry = job.start()

        job.startPage(1)
        val line = ByteArray(geometry.printableWidth * 3) { -1 }
        repeat(20) { y -> job.sendLine(y, line, geometry.printableWidth) }
        job.endPage(0)
        job.finish()

        val text = String(recorder.bytes(), Charsets.ISO_8859_1)
        var cursor = -1
        for (token in listOf("REMOTE1", "ESCPR", "setq", "setj", "sttp",
                             "dsnd", "endp", "endj", "JE")) {
            val position = text.indexOf(token, cursor + 1)
            assertTrue("perintah $token hilang atau urutannya salah", position > cursor)
            cursor = position
        }
        assertArrayEquals(
            "job harus ditutup dengan keluar dari REMOTE1",
            byteArrayOf(0x1B, 0, 0, 0),
            recorder.bytes().takeLast(4).toByteArray()
        )
    }

    @Test
    fun `header dsnd menyatakan panjang yang benar`() {
        val recorder = Recorder()
        val settings = PrintSettings(paper = PaperSize.A6, dpi = Dpi.DPI300)
        val job = EscpRJob(recorder, settings)
        val geometry = job.start()
        val before = recorder.bytes().size

        val width = geometry.printableWidth
        val line = ByteArray(width * 3) { -1 }
        job.sendLine(5, line, width)

        val packet = recorder.bytes().copyOfRange(before, recorder.bytes().size)
        assertEquals(0x1B.toByte(), packet[0])
        assertEquals('d'.code.toByte(), packet[1])

        val declared = (packet[2].toInt() and 0xFF) or
            ((packet[3].toInt() and 0xFF) shl 8) or
            ((packet[4].toInt() and 0xFF) shl 16) or
            ((packet[5].toInt() and 0xFF) shl 24)
        assertEquals("panjang di header harus sama dengan sisa paket",
            packet.size - 10, declared)

        assertEquals(5, ((packet[12].toInt() and 0xFF) shl 8) or (packet[13].toInt() and 0xFF))
        assertEquals(1, packet[14].toInt())   // penanda terkompresi

        val payloadLength = ((packet[15].toInt() and 0xFF) shl 8) or (packet[16].toInt() and 0xFF)
        assertEquals(packet.size - 17, payloadLength)
        assertArrayEquals(line, Rle.decode(packet.copyOfRange(17, packet.size), payloadLength))
    }
}
