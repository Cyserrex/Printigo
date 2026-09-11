package com.escpr.usbprint.escpr

import java.io.Closeable
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor

/** Ke mana byte ESC/P-R dialirkan: endpoint USB, atau file .prn untuk diagnosis. */
interface PrinterSink : Closeable {
    fun write(data: ByteArray, offset: Int, length: Int)
    fun flush()
}

fun PrinterSink.write(data: ByteArray) = write(data, 0, data.size)

data class PrintSettings(
    val paper: PaperSize = PaperSize.A4,
    val dpi: Dpi = Dpi.DPI360,
    val quality: Quality = Quality.NORMAL,
    val colorMode: ColorMode = ColorMode.COLOR,
    val mediaType: MediaType = MediaType.PLAIN,
    val marginMm: Float = 3f,
    /**
     * Dua arah mencetak saat kepala bergerak ke kiri maupun ke kanan, jadi
     * kira-kira dua kali lebih cepat. Harganya: kalau penyetelan kepala meleset
     * sedikit saja, jalur pergi dan jalur pulang tidak bertumpuk tepat dan
     * hasilnya tampak berbayang. Satu arah membuang separuh kecepatan untuk
     * menghilangkan sumber kesalahan itu sepenuhnya.
     */
    val direction: PrintDirection = PrintDirection.BIDIRECTIONAL,
    val compress: Boolean = true,
    val copies: Int = 1,
    val jobName: String = "AndroidUsbPrint"
)

/** Geometri halaman dalam piksel pada resolusi yang dipilih. */
data class PageGeometry(
    val paperWidth: Int,
    val paperHeight: Int,
    val marginLeft: Int,
    val marginTop: Int,
    val printableWidth: Int,
    val printableHeight: Int
)

/**
 * Merakit satu pekerjaan cetak ESC/P-R lengkap.
 *
 * Urutan pemakaian:
 *   start() -> [startPage(); sendLine()*; endPage()]* -> finish()
 */
class EscpRJob(
    private val sink: PrinterSink,
    private val settings: PrintSettings,
    /** Bisa diisi waktu tetap agar keluaran dapat dibandingkan byte per byte saat diuji. */
    private val clock: Calendar = Calendar.getInstance()
) {

    lateinit var geometry: PageGeometry
        private set

    /** Satu dsnd maksimal membawa 65535 byte, jadi baris lebar dipecah. */
    private val maxPixelsPerChunk = 16384
    private val headerSize = 17          // ESC d + len(4) + "dsnd" + x,y,comp,len(7)
    private var packet: ByteArray = ByteArray(0)

    fun start(): PageGeometry {
        val g = computeGeometry(settings)
        geometry = g

        packet = ByteArray(
            headerSize + Rle.maxEncodedSize(minOf(g.printableWidth, maxPixelsPerChunk) * 3)
        )

        sink.write(EscpR.EXIT_PACKET_MODE)
        sink.write(EscpR.INIT_PRINTER)

        sink.write(EscpR.ENTER_REMOTE_MODE)
        sink.write(EscpR.timeInit(clock))
        sink.write(EscpR.jobStart())
        sink.write(EscpR.jobHeader(settings.jobName))
        sink.write(EscpR.hardwareDevice())
        sink.write(EscpR.paperPath())
        sink.write(EscpR.EXIT_REMOTE_MODE)

        sink.write(EscpR.ENTER_ESCPR_MODE)
        sink.write(EscpR.setQuality(settings.mediaType, settings.quality, settings.colorMode))
        sink.write(
            EscpR.setJob(
                paperWidthPx = g.paperWidth,
                paperHeightPx = g.paperHeight,
                marginTopPx = g.marginTop,
                marginLeftPx = g.marginLeft,
                printableWidthPx = g.printableWidth,
                printableHeightPx = g.printableHeight,
                dpi = settings.dpi,
                direction = settings.direction,
            )
        )
        return g
    }

    fun startPage(pageNumber: Int) {
        sink.write(EscpR.startPage())
        sink.write(EscpR.pageNumber(pageNumber))
    }

    /**
     * Mengirim satu baris raster. [rgb] berisi [widthPx] piksel, 3 byte per piksel.
     */
    fun sendLine(y: Int, rgb: ByteArray, widthPx: Int) {
        var x = 0
        while (x < widthPx) {
            val pixels = minOf(maxPixelsPerChunk, widthPx - x)
            writeChunk(x, y, rgb, x * 3, pixels)
            x += pixels
        }
    }

    private fun writeChunk(x: Int, y: Int, rgb: ByteArray, srcOffset: Int, pixels: Int) {
        val rawLength = pixels * 3

        val payloadLength: Int
        val compressed: Boolean
        if (settings.compress) {
            // Encode langsung ke posisi payload di dalam paket: tanpa salinan tambahan.
            payloadLength = Rle.encode(
                sliceIfNeeded(rgb, srcOffset, rawLength), rawLength, packet, headerSize
            )
            compressed = true
        } else {
            System.arraycopy(rgb, srcOffset, packet, headerSize, rawLength)
            payloadLength = rawLength
            compressed = false
        }

        // ESC 'd' <panjang LE32> "dsnd"
        packet[0] = 0x1B
        packet[1] = 'd'.code.toByte()
        val bodyLength = 7 + payloadLength
        packet[2] = bodyLength.toByte()
        packet[3] = (bodyLength ushr 8).toByte()
        packet[4] = (bodyLength ushr 16).toByte()
        packet[5] = (bodyLength ushr 24).toByte()
        packet[6] = 'd'.code.toByte()
        packet[7] = 's'.code.toByte()
        packet[8] = 'n'.code.toByte()
        packet[9] = 'd'.code.toByte()

        // x(BE16) y(BE16) kompresi(u8) panjang payload(BE16)
        packet[10] = (x ushr 8).toByte()
        packet[11] = x.toByte()
        packet[12] = (y ushr 8).toByte()
        packet[13] = y.toByte()
        packet[14] = if (compressed) 1 else 0
        packet[15] = (payloadLength ushr 8).toByte()
        packet[16] = payloadLength.toByte()

        sink.write(packet, 0, headerSize + payloadLength)
    }

    /** Rle.encode selalu membaca dari indeks 0, jadi potongan tengah baris disalin dulu. */
    private var scratch: ByteArray = ByteArray(0)

    private fun sliceIfNeeded(src: ByteArray, offset: Int, length: Int): ByteArray {
        if (offset == 0) return src
        if (scratch.size < length) scratch = ByteArray(length)
        System.arraycopy(src, offset, scratch, 0, length)
        return scratch
    }

    fun endPage(pagesRemaining: Int) {
        sink.write(EscpR.endPage(pagesRemaining))
    }

    fun finish() {
        sink.write(EscpR.endJob())
        sink.write(EscpR.INIT_PRINTER)
        sink.write(EscpR.ENTER_REMOTE_MODE)
        sink.write(EscpR.loadDefaults())
        sink.write(EscpR.jobEnd())
        sink.write(EscpR.EXIT_REMOTE_MODE)
        sink.flush()
    }

    companion object {
        /**
         * Perkiraan besar data yang dikirim untuk satu halaman.
         *
         * Dihitung dari ukuran mentah tanpa memperhitungkan pemadatan, dan itu
         * disengaja. RLE memadatkan dokumen teks sampai seperseratus, tetapi
         * foto **hampir tidak terpadatkan sama sekali** -- diukur 100 persen
         * pada foto sungguhan. Perkiraan yang mengandalkan pemadatan akan
         * menyenangkan di layar lalu meleset jauh persis pada pekerjaan yang
         * paling lama: mencetak foto.
         */
        fun estimatedBytesPerPage(settings: PrintSettings): Long {
            val g = computeGeometry(settings)
            return g.printableWidth.toLong() * g.printableHeight * 3
        }

        fun computeGeometry(settings: PrintSettings): PageGeometry {
            val dpi = settings.dpi.value
            val mmToPx = dpi / 25.4f

            val paperW = ceil(settings.paper.widthMm * mmToPx).toInt()
            val paperH = ceil(settings.paper.heightMm * mmToPx).toInt()
            val margin = floor(settings.marginMm * mmToPx).toInt().coerceAtLeast(0)

            return PageGeometry(
                paperWidth = paperW,
                paperHeight = paperH,
                marginLeft = margin,
                marginTop = margin,
                printableWidth = paperW - 2 * margin,
                printableHeight = paperH - 2 * margin
            )
        }
    }
}
