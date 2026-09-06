import com.escpr.usbprint.escpr.Dpi
import com.escpr.usbprint.escpr.EscpRJob
import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.escpr.PrintSettings
import com.escpr.usbprint.escpr.PrinterSink
import com.escpr.usbprint.escpr.Quality
import com.escpr.usbprint.escpr.Rle
import java.io.File
import java.io.OutputStream
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Menghasilkan halaman uji yang persis sama dengan gen_reference.py.
 * Kalau kedua berkas identik, encoder Kotlin dan encoder Python yang sudah
 * lolos uji menghasilkan aliran ESC/P-R yang sama byte per byte.
 */

private class FileSink(private val stream: OutputStream) : PrinterSink {
    override fun write(data: ByteArray, offset: Int, length: Int) =
        stream.write(data, offset, length)

    override fun flush() = stream.flush()
    override fun close() = stream.close()
}

/** Salinan persis dari testpage_lines() di make_prn.py. */
private fun testPageLine(y: Int, width: Int, height: Int, out: ByteArray) {
    val bars = arrayOf(
        intArrayOf(0, 0, 0), intArrayOf(255, 0, 0), intArrayOf(0, 255, 0),
        intArrayOf(0, 0, 255), intArrayOf(0, 255, 255), intArrayOf(255, 0, 255),
        intArrayOf(255, 255, 0)
    )
    val border = 4
    val band = maxOf(1, height / 12)

    if (y < border || y >= height - border) {
        for (i in 0 until width * 3) out[i] = 0
        return
    }

    var o = 0
    for (x in 0 until width) {
        val px: IntArray = when {
            x < border || x >= width - border -> intArrayOf(0, 0, 0)
            y < band * 3 -> bars[(x * bars.size) / width]
            y < band * 5 -> {
                val g = (x * 255) / maxOf(1, width - 1)
                intArrayOf(g, g, g)
            }
            y < band * 7 -> {
                val g = 255 - (x * 255) / maxOf(1, width - 1)
                intArrayOf(255, g, g)
            }
            (x % 100) < 2 || (y % 100) < 2 -> intArrayOf(0, 0, 0)
            else -> intArrayOf(255, 255, 255)
        }
        out[o++] = px[0].toByte()
        out[o++] = px[1].toByte()
        out[o++] = px[2].toByte()
    }
}

fun main(args: Array<String>) {
    val output = File(args[0])

    // Waktu tetap supaya perintah TI identik di kedua sisi.
    val clock: Calendar = GregorianCalendar(2020, 0, 2, 3, 4, 5)

    val settings = PrintSettings(
        paper = PaperSize.A6,
        dpi = Dpi.DPI300,
        quality = Quality.NORMAL,
        marginMm = 3f
    )

    output.outputStream().buffered(1 shl 16).use { stream ->
        val sink = FileSink(stream)
        val job = EscpRJob(sink, settings, clock)
        val geometry = job.start()
        val width = geometry.printableWidth
        val height = geometry.printableHeight

        val line = ByteArray(width * 3)
        job.startPage(1)
        for (y in 0 until height) {
            testPageLine(y, width, height, line)
            job.sendLine(y, line, width)
        }
        job.endPage(0)
        job.finish()

        println("Kotlin: ${width}x$height px")
    }

    // Uji tambahan: RLE harus bolak-balik tanpa kehilangan data.
    val sample = ByteArray(3000) { ((it * 37) % 256).toByte() }
    val encoded = ByteArray(Rle.maxEncodedSize(sample.size))
    val n = Rle.encode(sample, sample.size, encoded)
    val decoded = Rle.decode(encoded, n)
    check(decoded.contentEquals(sample)) { "RLE Kotlin tidak simetris" }
    println("Kotlin: RLE round-trip OK (${sample.size} -> $n byte)")
}
