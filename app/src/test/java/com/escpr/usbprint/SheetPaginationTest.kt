package com.escpr.usbprint

import com.escpr.usbprint.layout.RectMm
import com.escpr.usbprint.layout.SheetItem
import com.escpr.usbprint.layout.arrangedInGridPaged
import com.escpr.usbprint.layout.broughtToFront
import com.escpr.usbprint.layout.groupedBySheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pembagian foto ke beberapa lembar.
 *
 * Yang paling mudah salah di sini adalah hubungan antara urutan daftar dan
 * nomor lembar. Urutan daftar menentukan siapa menutupi siapa; nomor lembar
 * menentukan foto keluar di kertas yang mana. Kalau keduanya dicampur, foto
 * berpindah kertas hanya karena disentuh.
 */
class SheetPaginationTest {

    private val printable = RectMm(10f, 10f, 190f, 277f)

    private fun items(count: Int): List<SheetItem> = List(count) { index ->
        SheetItem(id = index.toLong(), aspect = 1.5f, rect = RectMm(0f, 0f, 10f, 10f))
    }

    @Test
    fun `nol per lembar berarti semuanya pada satu lembar`() {
        val laid = items(9).arrangedInGridPaged(printable, perSheet = 0)
        assertTrue(laid.all { it.page == 0 })
        assertEquals(1, laid.groupedBySheet { it.page }.size)
    }

    @Test
    fun `empat per lembar membagi sembilan foto jadi tiga lembar`() {
        val laid = items(9).arrangedInGridPaged(printable, perSheet = 4)
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 2), laid.map { it.page })
        val sheets = laid.groupedBySheet { it.page }
        assertEquals(3, sheets.size)
        assertEquals(listOf(4, 4, 1), sheets.map { it.size })
    }

    @Test
    fun `tiap lembar disusun terhadap area cetak yang sama`() {
        val laid = items(4).arrangedInGridPaged(printable, perSheet = 2)
        val first = laid.filter { it.page == 0 }.map { it.rect }
        val second = laid.filter { it.page == 1 }.map { it.rect }
        // Lembar penuh dan lembar penuh berikutnya harus identik bentuknya,
        // kalau tidak, cetakan lembar kedua terlihat berbeda tanpa sebab.
        assertEquals(first.size, second.size)
        first.zip(second).forEach { (a, b) ->
            assertEquals(a.left, b.left, 0.01f)
            assertEquals(a.top, b.top, 0.01f)
            assertEquals(a.width, b.width, 0.01f)
            assertEquals(a.height, b.height, 0.01f)
        }
    }

    @Test
    fun `foto tidak pindah lembar saat dinaikkan ke tumpukan atas`() {
        val laid = items(6).arrangedInGridPaged(printable, perSheet = 3)
        val moved = laid.broughtToFront(0L)

        // Urutan daftar memang berubah -- itulah gunanya.
        assertEquals(0L, moved.last().id)
        // Tetapi nomor lembar tiap foto tetap sama persis.
        laid.forEach { before ->
            val after = moved.first { it.id == before.id }
            assertEquals("foto " + before.id, before.page, after.page)
        }
    }

    @Test
    fun `pengelompokan mengabaikan urutan daftar dan tetap terurut lembar`() {
        val laid = items(6).arrangedInGridPaged(printable, perSheet = 2)
        val sheets = laid.shuffled().groupedBySheet { it.page }
        assertEquals(3, sheets.size)
        assertEquals(listOf(0, 1, 2), sheets.map { it.first().page })
    }

    @Test
    fun `lembar yang kosong karena penghapusan ikut hilang`() {
        val laid = items(4).arrangedInGridPaged(printable, perSheet = 2)
        // Buang seluruh isi lembar pertama.
        val remaining = laid.filterNot { it.page == 0 }
        val sheets = remaining.groupedBySheet { it.page }
        assertEquals(1, sheets.size)
        assertEquals(2, sheets.first().size)
    }

    @Test
    fun `daftar kosong tidak menghasilkan lembar sama sekali`() {
        assertEquals(emptyList<SheetItem>(), items(0).arrangedInGridPaged(printable, 4))
        assertEquals(0, emptyList<SheetItem>().groupedBySheet { it.page }.size)
    }

    @Test
    fun `per lembar lebih besar dari jumlah foto tetap satu lembar`() {
        val laid = items(3).arrangedInGridPaged(printable, perSheet = 10)
        assertEquals(1, laid.groupedBySheet { it.page }.size)
    }

    @Test
    fun `satu per lembar memberi tiap foto kertasnya sendiri`() {
        val laid = items(5).arrangedInGridPaged(printable, perSheet = 1)
        assertEquals(5, laid.groupedBySheet { it.page }.size)
        // Sendirian di kertas, foto harus mengisi salah satu sisi area cetak.
        laid.forEach { item ->
            val fillsWidth = kotlin.math.abs(item.rect.width - printable.width) < 0.01f
            val fillsHeight = kotlin.math.abs(item.rect.height - printable.height) < 0.01f
            assertTrue("foto " + item.id + " tidak mengisi kertas", fillsWidth || fillsHeight)
        }
    }
}
