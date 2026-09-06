package com.escpr.usbprint

import com.escpr.usbprint.escpr.Dpi
import com.escpr.usbprint.escpr.EscpRJob
import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.escpr.PrintSettings
import com.escpr.usbprint.layout.ContentPlacement
import com.escpr.usbprint.layout.clampedTo
import com.escpr.usbprint.layout.computePageLayout
import com.escpr.usbprint.layout.contentRectInPrintablePx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

/**
 * Tata letak halaman adalah satu-satunya sumber kebenaran yang dipakai
 * bersama oleh pratinjau di layar dan raster yang dikirim ke printer. Uji di
 * sini menjaga agar keduanya tidak pernah berbeda.
 */
class PageLayoutTest {

    private fun layout(
        paper: PaperSize = PaperSize.A4,
        marginMm: Float = 3f,
        aspect: Float = 1000f / 1500f,
        placement: ContentPlacement = ContentPlacement.Fit,
    ) = computePageLayout(paper.widthMm, paper.heightMm, marginMm, aspect, placement)

    // ------------------------------------------------- penempatan bawaan

    @Test
    fun `tanpa pengaturan pengguna isi pas di dalam area cetak`() {
        val contents = listOf(1000f / 1500f, 4000f / 3000f, 1f, 1200f / 400f)

        for (paper in PaperSize.entries) {
            for (margin in listOf(0f, 3f, 7.5f, 15f)) {
                for (aspect in contents) {
                    val l = layout(paper, margin, aspect)
                    val label = "${paper.shortLabel} margin=$margin rasio=$aspect"

                    assertFalse("$label seharusnya tidak terpotong", l.hasOverflow)
                    assertTrue(
                        "$label lebar isi melebihi area cetak",
                        l.content.width <= l.printable.width + 0.01f
                    )
                    assertTrue(
                        "$label tinggi isi melebihi area cetak",
                        l.content.height <= l.printable.height + 0.01f
                    )
                    // Salah satu sisi harus menyentuh batas: itulah arti "muat".
                    val touchesWidth = l.content.width >= l.printable.width - 0.01f
                    val touchesHeight = l.content.height >= l.printable.height - 0.01f
                    assertTrue("$label tidak menyentuh batas mana pun", touchesWidth || touchesHeight)
                }
            }
        }
    }

    @Test
    fun `isi diletakkan di tengah kertas`() {
        val l = layout(aspect = 1200f / 400f)
        assertEquals(l.paperWidthMm / 2f, l.content.centerX, 0.01f)
        assertEquals(l.paperHeightMm / 2f, l.content.centerY, 0.01f)
        assertEquals(l.marginLeftMm, l.marginRightMm, 0.01f)
        assertEquals(l.marginTopMm, l.marginBottomMm, 0.01f)
    }

    @Test
    fun `rasio isi tetap terjaga berapa pun skalanya`() {
        val aspect = 1600f / 900f
        for (scale in listOf(0.3f, 1f, 2.5f, 6f)) {
            val l = layout(aspect = aspect, placement = ContentPlacement(scale = scale))
            assertEquals("skala $scale merusak rasio", aspect, l.content.width / l.content.height, 0.001f)
        }
    }

    // ----------------------------------------------------- pengaturan manual

    @Test
    fun `memperbesar melewati area cetak memicu peringatan terpotong`() {
        val aman = layout(placement = ContentPlacement(scale = 1f))
        assertFalse(aman.hasOverflow)

        val terpotong = layout(placement = ContentPlacement(scale = 1.5f, manual = true))
        assertTrue("diperbesar 1,5x harus terpotong", terpotong.hasOverflow)
        // Kertas potret dengan isi potret: sisi kiri dan kanan ikut keluar.
        assertTrue(terpotong.overflowTopMm > 0f)
        assertTrue(terpotong.overflowBottomMm > 0f)
    }

    @Test
    fun `menggeser ke kiri hanya memotong sisi kiri`() {
        val l = layout(
            marginMm = 3f,
            aspect = 1f,
            placement = ContentPlacement(offsetXmm = -60f, manual = true),
        )
        assertTrue("harus keluar di kiri", l.overflowLeftMm > 0f)
        assertEquals("kanan tidak boleh ikut keluar", 0f, l.overflowRightMm, 0.001f)
    }

    @Test
    fun `jumlah yang terpotong dihitung dari selisih ke area cetak`() {
        // Isi persegi pada A4 potret: lebar muat = 210 - 6 = 204 mm.
        val l = layout(
            marginMm = 3f,
            aspect = 1f,
            placement = ContentPlacement(offsetXmm = -10f, manual = true),
        )
        // Digeser 10 mm ke kiri, jadi 10 mm keluar di kiri dan 0 di kanan.
        assertEquals(10f, l.overflowLeftMm, 0.05f)
        assertEquals(0f, l.overflowRightMm, 0.05f)
    }

    @Test
    fun `selisih sangat kecil tidak dianggap terpotong`() {
        // Tanpa toleransi, pembulatan float membuat penempatan pas muat pun
        // dilaporkan terpotong dan peringatannya jadi gangguan terus-menerus.
        val l = layout(placement = ContentPlacement(scale = 1.0001f))
        assertFalse(l.hasOverflow)
    }

    // ------------------------------------------------------------ pembatas

    @Test
    fun `pembatas menjaga skala dan pusat isi tetap di kertas`() {
        val paper = PaperSize.A4
        val liar = ContentPlacement(scale = 99f, offsetXmm = 9999f, offsetYmm = -9999f)
        val aman = liar.clampedTo(paper.widthMm, paper.heightMm)

        assertEquals(ContentPlacement.MAX_SCALE, aman.scale, 0.001f)
        assertEquals(paper.widthMm / 2f, aman.offsetXmm, 0.001f)
        assertEquals(-paper.heightMm / 2f, aman.offsetYmm, 0.001f)

        // Pusat isi masih berada di atas kertas, jadi selalu bisa digeser balik.
        val l = computePageLayout(paper.widthMm, paper.heightMm, 3f, 1f, aman)
        assertTrue(l.content.centerX in 0f..paper.widthMm)
        assertTrue(l.content.centerY in 0f..paper.heightMm)
    }

    // -------------------------------- kesetaraan dengan geometri cetak

    @Test
    fun `area cetak dalam milimeter cocok dengan geometri raster`() {
        for (paper in PaperSize.entries) {
            for (dpi in Dpi.entries) {
                for (margin in listOf(0f, 3f, 10f)) {
                    val l = layout(paper, margin)
                    val geometry = EscpRJob.computeGeometry(
                        PrintSettings(paper = paper, dpi = dpi, marginMm = margin)
                    )
                    val pxPerMm = dpi.value / 25.4f
                    val label = "${paper.shortLabel} ${dpi.value}dpi margin=$margin"

                    // Geometri raster membulatkan ukuran kertas ke ATAS dan
                    // margin ke BAWAH, sedangkan model milimeter tidak membulat
                    // sama sekali. Selisih terburuknya 1 + 2x1 = 3 piksel, yaitu
                    // 0,2 mm pada 360 dpi -- di bawah ketelitian mekanik printer.
                    assertEquals(
                        "$label lebar",
                        l.printable.width * pxPerMm, geometry.printableWidth.toFloat(), 3.5f
                    )
                    assertEquals(
                        "$label tinggi",
                        l.printable.height * pxPerMm, geometry.printableHeight.toFloat(), 3.5f
                    )
                }
            }
        }
    }

    @Test
    fun `kotak tujuan piksel menempatkan isi sama seperti model milimeter`() {
        val placements = listOf(
            ContentPlacement.Fit,
            ContentPlacement(scale = 1.4f, manual = true),
            ContentPlacement(scale = 0.6f, offsetXmm = 20f, offsetYmm = -35f, manual = true),
        )

        for (placement in placements) {
            for (dpi in Dpi.entries) {
                val l = layout(placement = placement)
                val box = l.contentRectInPrintablePx(dpi.value)
                val pxPerMm = dpi.value / 25.4f

                // Kotak diukur dari sudut area cetak, bukan sudut kertas.
                assertEquals(
                    (l.content.left - l.printable.left) * pxPerMm, box[0], 0.01f
                )
                assertEquals(
                    (l.content.top - l.printable.top) * pxPerMm, box[1], 0.01f
                )
                assertEquals(l.content.width * pxPerMm, box[2] - box[0], 0.01f)
                assertEquals(l.content.height * pxPerMm, box[3] - box[1], 0.01f)
            }
        }
    }

    @Test
    fun `isi yang muat selalu berada di dalam raster`() {
        val l = layout()
        val box = l.contentRectInPrintablePx(Dpi.DPI360.value)
        val geometry = EscpRJob.computeGeometry(
            PrintSettings(paper = PaperSize.A4, dpi = Dpi.DPI360, marginMm = 3f)
        )
        assertTrue("kiri negatif berarti terpotong", box[0] >= -1f)
        assertTrue("atas negatif berarti terpotong", box[1] >= -1f)
        assertTrue(box[2] <= geometry.printableWidth + 1f)
        assertTrue(box[3] <= geometry.printableHeight + 1f)
    }

    @Test
    fun `isi yang digeser keluar menghasilkan kotak tujuan negatif`() {
        // Inilah yang membuat pemotongan terjadi sendiri: bagian kotak yang
        // berada di luar bitmap pita tidak pernah tergambar.
        val l = layout(placement = ContentPlacement(offsetXmm = -40f, manual = true))
        val box = l.contentRectInPrintablePx(Dpi.DPI360.value)
        assertTrue("kotak harus mulai di luar area cetak", box[0] < 0f)
    }

    // ------------------------------------------------------ tanpa dokumen

    @Test
    fun `tanpa dokumen geometri kertas tetap dihitung`() {
        val l = computePageLayout(
            PaperSize.PHOTO_4R.widthMm, PaperSize.PHOTO_4R.heightMm, 3f, contentAspect = 0f
        )
        assertFalse(l.hasContent)
        assertFalse(l.hasOverflow)
        assertTrue(l.printable.width > 0f)
        assertEquals(3f, l.printable.left, 0.001f)
    }

    @Test
    fun `margin lebih besar mempersempit area cetak`() {
        var previous = Float.MAX_VALUE
        for (margin in listOf(0f, 2f, 5f, 10f, 20f)) {
            val l = layout(marginMm = margin)
            assertTrue("margin $margin harus mempersempit", l.printable.width < previous)
            previous = l.printable.width
        }
    }

    @Test
    fun `margin tidak bisa menelan seluruh kertas`() {
        val kecil = PaperSize.PHOTO_4R
        val l = computePageLayout(kecil.widthMm, kecil.heightMm, marginMm = 999f, contentAspect = 1f)
        assertTrue("area cetak harus tetap ada", l.printable.width > 0f)
        assertTrue(l.printable.width <= min(kecil.widthMm, kecil.heightMm))
    }
}
