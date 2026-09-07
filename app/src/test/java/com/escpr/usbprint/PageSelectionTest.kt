package com.escpr.usbprint

import com.escpr.usbprint.print.PageSelection
import com.escpr.usbprint.print.PageSelectionMode
import com.escpr.usbprint.print.resolvePages
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pilihan halaman berurusan dengan dua penomoran sekaligus: pengguna melihat
 * halaman 1, PdfRenderer melihat indeks 0. Pergeseran satu di situ berarti
 * salah cetak yang tidak kelihatan sampai kertasnya keluar.
 */
class PageSelectionTest {

    @Test
    fun `semua halaman berarti seluruh indeks`() {
        val pages = resolvePages(PageSelection(PageSelectionMode.ALL), 2, 5)
        assertEquals(listOf(0, 1, 2, 3, 4), pages)
    }

    @Test
    fun `halaman ini memakai indeks pratinjau apa adanya`() {
        val pages = resolvePages(PageSelection(PageSelectionMode.CURRENT), 3, 10)
        assertEquals(listOf(3), pages)
    }

    @Test
    fun `rentang pengguna dimulai dari satu, hasilnya dimulai dari nol`() {
        val selection = PageSelection(PageSelectionMode.RANGE, fromPage = 2, toPage = 4)
        assertEquals(listOf(1, 2, 3), resolvePages(selection, 0, 10))
    }

    @Test
    fun `rentang satu halaman menghasilkan satu halaman`() {
        val selection = PageSelection(PageSelectionMode.RANGE, fromPage = 7, toPage = 7)
        assertEquals(listOf(6), resolvePages(selection, 0, 10))
    }

    @Test
    fun `rentang terbalik dibetulkan, bukan dikosongkan`() {
        val selection = PageSelection(PageSelectionMode.RANGE, fromPage = 5, toPage = 2)
        assertEquals(listOf(1, 2, 3, 4), resolvePages(selection, 0, 10))
    }

    @Test
    fun `rentang melewati jumlah halaman dipangkas`() {
        val selection = PageSelection(PageSelectionMode.RANGE, fromPage = 1, toPage = 99)
        assertEquals(listOf(0, 1, 2), resolvePages(selection, 0, 3))
    }

    @Test
    fun `halaman ini di luar batas ditarik ke dalam batas`() {
        val pages = resolvePages(PageSelection(PageSelectionMode.CURRENT), 42, 3)
        assertEquals(listOf(2), pages)
    }

    @Test
    fun `dokumen kosong tidak menghasilkan halaman apa pun`() {
        PageSelectionMode.entries.forEach { mode ->
            assertEquals(
                "mode " + mode,
                emptyList<Int>(),
                resolvePages(PageSelection(mode), 0, 0),
            )
        }
    }
}
