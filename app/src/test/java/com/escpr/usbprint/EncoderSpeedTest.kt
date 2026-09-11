package com.escpr.usbprint

import com.escpr.usbprint.escpr.Rle
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Mengukur berapa lama satu halaman dihabiskan di encoder, bukan di USB.
 *
 * Cetakan pertama dari HP sungguhan memakan 120 detik untuk satu lembar A4.
 * Tanpa angka, tidak ada cara tahu apakah yang lambat itu kabelnya, printernya,
 * atau kode ini sendiri -- dan menebaknya berarti menghabiskan kertas orang
 * untuk setiap tebakan.
 *
 * Angka di sini diukur pada JVM desktop, jadi HP akan lebih lambat beberapa
 * kali lipat. Yang dicari bukan angka mutlaknya melainkan perbandingannya:
 * bagian mana yang memakan waktu.
 */
class EncoderSpeedTest {

    /** A4 pada 600 dpi dengan margin 3 mm: lebar area cetak dalam piksel. */
    private val width600 = ((210f - 6f) / 25.4f * 600).toInt()
    private val lines600 = ((297f - 6f) / 25.4f * 600).toInt()

    /** Satu baris foto: tetangga mirip tapi jarang persis sama. */
    private fun photoLine(width: Int): ByteArray {
        val rnd = Random(7)
        val row = ByteArray(width * 3)
        var r = 120; var g = 140; var b = 160
        for (x in 0 until width) {
            r = (r + rnd.nextInt(-3, 4)).coerceIn(0, 255)
            g = (g + rnd.nextInt(-3, 4)).coerceIn(0, 255)
            b = (b + rnd.nextInt(-3, 4)).coerceIn(0, 255)
            row[x * 3] = r.toByte()
            row[x * 3 + 1] = g.toByte()
            row[x * 3 + 2] = b.toByte()
        }
        return row
    }

    /** Satu baris dokumen: sebagian besar putih polos. */
    private fun textLine(width: Int): ByteArray {
        val row = ByteArray(width * 3) { -1 }
        for (x in width / 4 until width / 4 + width / 20) {
            row[x * 3] = 20; row[x * 3 + 1] = 20; row[x * 3 + 2] = 20
        }
        return row
    }

    private fun ukur(nama: String, row: ByteArray, lines: Int): Pair<Double, Double> {
        val dst = ByteArray(Rle.maxEncodedSize(row.size))
        // Pemanasan: JIT butuh beberapa putaran sebelum angkanya berarti.
        repeat(200) { Rle.encode(row, row.size, dst) }

        val mulai = System.nanoTime()
        var total = 0L
        repeat(lines) { total += Rle.encode(row, row.size, dst) }
        val detik = (System.nanoTime() - mulai) / 1e9

        val mentah = row.size.toLong() * lines
        println(
            "%-8s %5d baris x %5d px  ->  %.2f detik, %.1f MB mentah, %.1f MB terkirim (%.0f%%)"
                .format(nama, lines, row.size / 3, detik, mentah / 1e6, total / 1e6, 100.0 * total / mentah)
        )
        return detik to total.toDouble() / mentah
    }

    @Test
    fun `foto hampir tidak bisa dipadatkan, teks bisa`() {
        val (_, rasioFoto) = ukur("foto", photoLine(width600), lines600)
        val (_, rasioTeks) = ukur("teks", textLine(width600), lines600)

        // Inilah yang membenarkan perkiraan besar data dihitung dari ukuran
        // mentah tanpa memperhitungkan pemadatan. Kalau suatu saat ada yang
        // mengubah perkiraan itu menjadi mengandalkan RLE, uji ini yang
        // menjelaskan kenapa tidak boleh: pada foto, RLE tidak menolong.
        assertTrue("foto ternyata terpadatkan jadi %.0f%%".format(rasioFoto * 100),
            rasioFoto > 0.9)
        assertTrue("teks seharusnya padat, nyatanya %.0f%%".format(rasioTeks * 100),
            rasioTeks < 0.1)
    }

    @Test
    fun `berapa hemat menurunkan resolusi`() {
        listOf(300, 360, 600, 720).forEach { dpi ->
            val w = ((210f - 6f) / 25.4f * dpi).toInt()
            val h = ((297f - 6f) / 25.4f * dpi).toInt()
            ukur("$dpi dpi", photoLine(w), h)
        }
    }
}
