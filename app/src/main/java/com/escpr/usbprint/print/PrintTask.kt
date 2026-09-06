package com.escpr.usbprint.print

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.escpr.usbprint.escpr.ColorMode
import com.escpr.usbprint.escpr.EscpRJob
import com.escpr.usbprint.escpr.PrintSettings
import com.escpr.usbprint.escpr.PrinterSink
import com.escpr.usbprint.layout.ContentPlacement
import com.escpr.usbprint.layout.computePageLayout
import com.escpr.usbprint.layout.contentRectInPrintablePx
import com.escpr.usbprint.render.PageSource
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class PrintProgress(
    val currentPage: Int,
    val totalPages: Int,
    val linesDone: Int,
    val linesTotal: Int
) {
    val fraction: Float
        get() {
            if (totalPages <= 0 || linesTotal <= 0) return 0f
            val perPage = 1f / totalPages
            return ((currentPage - 1) * perPage + perPage * linesDone / linesTotal)
                .coerceIn(0f, 1f)
        }
}

/**
 * Menjahit ketiga bagian: halaman digambar per pita, pita diubah jadi baris RGB,
 * baris dikirim sebagai perintah dsnd ESC/P-R ke [sink].
 */
object PrintTask {

    /** Anggaran memori untuk satu pita. 4 MB aman bahkan di HP kelas bawah. */
    private const val BAND_BUDGET_BYTES = 4 * 1024 * 1024
    private const val MIN_BAND_LINES = 8
    private const val MAX_BAND_LINES = 128

    suspend fun run(
        sink: PrinterSink,
        source: PageSource,
        settings: PrintSettings,
        placement: ContentPlacement = ContentPlacement.Fit,
        onProgress: (PrintProgress) -> Unit = {}
    ) {
        val job = EscpRJob(sink, settings)
        val geometry = job.start()
        val width = geometry.printableWidth
        val height = geometry.printableHeight

        val copies = settings.copies.coerceAtLeast(1)
        val totalPages = source.pageCount * copies

        val bandLines = (BAND_BUDGET_BYTES / (width * 4))
            .coerceIn(MIN_BAND_LINES, MAX_BAND_LINES)
        val band = Bitmap.createBitmap(width, bandLines, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * bandLines)
        val line = ByteArray(width * 3)
        val mono = settings.colorMode == ColorMode.MONO

        try {
            var pageCounter = 0
            for (copy in 0 until copies) {
                for (index in 0 until source.pageCount) {
                    coroutineContext.ensureActive()
                    pageCounter++

                    source.openPage(index)

                    // Tata letak dihitung per halaman karena satu PDF bisa
                    // memuat halaman dengan rasio berbeda-beda.
                    val layout = computePageLayout(
                        paperWidthMm = settings.paper.widthMm,
                        paperHeightMm = settings.paper.heightMm,
                        marginMm = settings.marginMm,
                        contentAspect = source.contentAspect(),
                        placement = placement,
                    )
                    val box = layout.contentRectInPrintablePx(settings.dpi.value)
                    source.setDestination(RectF(box[0], box[1], box[2], box[3]))

                    job.startPage(pageCounter)

                    var y = 0
                    while (y < height) {
                        coroutineContext.ensureActive()
                        val rows = minOf(bandLines, height - y)

                        band.eraseColor(Color.WHITE)
                        source.renderBand(band, y)
                        band.getPixels(pixels, 0, width, 0, 0, width, rows)

                        for (row in 0 until rows) {
                            writeLine(pixels, row * width, width, line, mono)
                            job.sendLine(y + row, line, width)
                        }

                        y += rows
                        onProgress(PrintProgress(pageCounter, totalPages, y, height))
                    }

                    source.closePage()
                    job.endPage(totalPages - pageCounter)
                }
            }
            job.finish()
        } finally {
            band.recycle()
        }
    }

    /** Mengubah satu baris piksel ARGB menjadi RGB rapat 3 byte per piksel. */
    private fun writeLine(
        pixels: IntArray,
        offset: Int,
        width: Int,
        out: ByteArray,
        mono: Boolean
    ) {
        var o = 0
        if (mono) {
            for (x in 0 until width) {
                val c = pixels[offset + x]
                // Luma BT.601 dengan bobot bilangan bulat: (77R + 150G + 29B) / 256
                val gray = (
                    ((c ushr 16) and 0xFF) * 77 +
                        ((c ushr 8) and 0xFF) * 150 +
                        (c and 0xFF) * 29
                    ) shr 8
                val g = gray.toByte()
                out[o++] = g
                out[o++] = g
                out[o++] = g
            }
        } else {
            for (x in 0 until width) {
                val c = pixels[offset + x]
                out[o++] = (c ushr 16).toByte()
                out[o++] = (c ushr 8).toByte()
                out[o++] = c.toByte()
            }
        }
    }
}
