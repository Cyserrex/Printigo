package com.escpr.usbprint

import com.escpr.usbprint.escpr.Dpi
import com.escpr.usbprint.escpr.EscpRJob
import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.escpr.PrintSettings
import com.escpr.usbprint.ui.computePreviewLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

/**
 * Pratinjau hanya berguna kalau yang ditampilkan benar-benar sama dengan yang
 * keluar dari printer. Uji di bawah membandingkan tata letak pratinjau dengan
 * geometri yang dipakai jalur cetak sungguhan.
 */
class PreviewLayoutTest {

    private fun previewFor(
        paper: PaperSize,
        marginMm: Float,
        contentWidth: Int,
        contentHeight: Int,
        canvasWidth: Float = 600f
    ) = computePreviewLayout(
        paperWidthMm = paper.widthMm,
        paperHeightMm = paper.heightMm,
        marginMm = marginMm,
        canvasWidth = canvasWidth,
        canvasHeight = canvasWidth * paper.heightMm / paper.widthMm,
        contentWidth = contentWidth,
        contentHeight = contentHeight
    )

    @Test
    fun `isi menempati porsi area cetak yang sama seperti saat mencetak`() {
        val contents = listOf(1000 to 1500, 4000 to 3000, 800 to 800, 1200 to 400)

        for (paper in PaperSize.entries) {
            for (margin in listOf(0f, 3f, 7.5f, 15f)) {
                for ((contentWidth, contentHeight) in contents) {
                    // Geometri sungguhan, dalam piksel printer.
                    val geometry = EscpRJob.computeGeometry(
                        PrintSettings(paper = paper, dpi = Dpi.DPI360, marginMm = margin)
                    )
                    val printScale = min(
                        geometry.printableWidth.toFloat() / contentWidth,
                        geometry.printableHeight.toFloat() / contentHeight
                    )
                    val printFractionX =
                        contentWidth * printScale / geometry.printableWidth
                    val printFractionY =
                        contentHeight * printScale / geometry.printableHeight

                    val preview = previewFor(paper, margin, contentWidth, contentHeight)
                    val previewFractionX = preview.contentWidth / preview.printableWidth
                    val previewFractionY = preview.contentHeight / preview.printableHeight

                    val label = "${paper.shortLabel} margin=$margin isi=${contentWidth}x$contentHeight"
                    // Toleransi kecil untuk pembulatan piksel printer ke bilangan bulat.
                    assertEquals("$label lebar", printFractionX, previewFractionX, 0.01f)
                    assertEquals("$label tinggi", printFractionY, previewFractionY, 0.01f)
                }
            }
        }
    }

    @Test
    fun `isi selalu diletakkan di tengah area cetak`() {
        val preview = previewFor(PaperSize.A4, 5f, 1200, 400)
        val leftGap = preview.contentLeft - preview.insetX
        val rightGap = (preview.insetX + preview.printableWidth) -
            (preview.contentLeft + preview.contentWidth)
        assertEquals(leftGap, rightGap, 0.01f)

        val topGap = preview.contentTop - preview.insetY
        val bottomGap = (preview.insetY + preview.printableHeight) -
            (preview.contentTop + preview.contentHeight)
        assertEquals(topGap, bottomGap, 0.01f)
    }

    @Test
    fun `margin nol memakai seluruh kertas`() {
        val preview = previewFor(PaperSize.A4, 0f, 1000, 1000)
        assertEquals(0f, preview.insetX, 0.001f)
        assertEquals(0f, preview.insetY, 0.001f)
        assertEquals(600f, preview.printableWidth, 0.001f)
    }

    @Test
    fun `margin milimeter memberi lebar tepi yang sama di semua sisi`() {
        // Kertas A4 tidak persegi, jadi 10 mm adalah porsi berbeda dari lebar
        // dan dari tinggi. Yang harus sama adalah ukuran fisiknya, bukan porsinya.
        val canvasWidth = 600f
        val preview = previewFor(PaperSize.A4, 10f, 1000, 1000, canvasWidth)
        val mmPerPixel = PaperSize.A4.widthMm / canvasWidth

        assertEquals(10f, preview.insetX * mmPerPixel, 0.01f)
        assertEquals(10f, preview.insetY * mmPerPixel, 0.01f)
    }

    @Test
    fun `margin lebih besar mengecilkan area cetak`() {
        var previousWidth = Float.MAX_VALUE
        for (margin in listOf(0f, 2f, 5f, 10f, 20f)) {
            val preview = previewFor(PaperSize.A4, margin, 1000, 1000)
            assertTrue("margin $margin harus mempersempit", preview.printableWidth < previousWidth)
            previousWidth = preview.printableWidth
        }
    }

    @Test
    fun `tanpa dokumen area cetak tetap dihitung`() {
        val preview = previewFor(PaperSize.PHOTO_4R, 3f, 0, 0)
        assertTrue(preview.printableWidth > 0f)
        assertTrue(preview.printableHeight > 0f)
        assertEquals(0f, preview.contentWidth, 0.001f)
    }
}
