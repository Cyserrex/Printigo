package com.escpr.usbprint

import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.layout.DEFAULT_GAP_MM
import com.escpr.usbprint.layout.MIN_ITEM_MM
import com.escpr.usbprint.layout.RectMm
import com.escpr.usbprint.layout.SheetItem
import com.escpr.usbprint.layout.arrangeInGrid
import com.escpr.usbprint.layout.arrangedInGrid
import com.escpr.usbprint.layout.broughtToFront
import com.escpr.usbprint.layout.computeSheetLayout
import com.escpr.usbprint.layout.movedBy
import com.escpr.usbprint.layout.rotatedBy
import com.escpr.usbprint.layout.scaledBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model lembar: banyak foto di atas satu kertas, masing-masing dengan posisi
 * sendiri dalam milimeter. Sama seperti tata letak halaman, angka-angka ini
 * dipakai bersama oleh pratinjau dan raster yang dikirim ke printer.
 */
class SheetTest {

    private val a4 = PaperSize.A4

    private fun sheet(items: List<SheetItem>, marginMm: Float = 3f) =
        computeSheetLayout(a4.widthMm, a4.heightMm, marginMm, items)

    private fun items(vararg aspects: Float): List<SheetItem> =
        aspects.mapIndexed { index, aspect ->
            SheetItem(id = index.toLong(), aspect = aspect, rect = RectMm(0f, 0f, 10f, 10f))
        }

    // ------------------------------------------------------- penyusunan kisi

    @Test
    fun `kisi otomatis menaruh semua foto di dalam area cetak`() {
        for (count in 1..9) {
            val list = items(*FloatArray(count) { 4f / 3f })
            val layout = sheet(list.arrangedInGrid(sheet(list).printable))

            assertEquals(count, layout.items.size)
            assertFalse(
                "$count foto seharusnya muat tanpa terpotong",
                layout.hasOverflow
            )
        }
    }

    @Test
    fun `rasio tiap foto dijaga saat disusun`() {
        val aspects = listOf(4f / 3f, 3f / 4f, 1f, 16f / 9f)
        val list = items(*aspects.toFloatArray())
        val arranged = list.arrangedInGrid(sheet(list).printable)

        arranged.forEachIndexed { index, item ->
            assertEquals(
                "foto ke-$index berubah rasio",
                aspects[index], item.rect.width / item.rect.height, 0.001f
            )
        }
    }

    @Test
    fun `jumlah kolom menentukan bentuk susunan`() {
        val list = items(1f, 1f, 1f, 1f)
        val printable = sheet(list).printable

        val satuKolom = list.arrangedInGrid(printable, columns = 1)
        val empatKolom = list.arrangedInGrid(printable, columns = 4)

        // Satu kolom berarti bertumpuk ke bawah: semua kiri sama.
        assertEquals(1, satuKolom.map { it.rect.left }.distinct().size)
        // Empat kolom berarti berjajar: semua atas sama.
        assertEquals(1, empatKolom.map { it.rect.top }.distinct().size)
        // Berjajar empat pasti lebih kecil daripada bertumpuk satu kolom.
        assertTrue(empatKolom[0].rect.width < satuKolom[0].rect.width)
    }

    @Test
    fun `foto tidak saling menimpa saat disusun`() {
        val list = items(1f, 1f, 1f, 1f, 1f, 1f)
        val arranged = list.arrangedInGrid(sheet(list).printable, columns = 3)

        for (i in arranged.indices) {
            for (j in i + 1 until arranged.size) {
                val a = arranged[i].rect
                val b = arranged[j].rect
                val terpisah = a.right <= b.left + 0.01f || b.right <= a.left + 0.01f ||
                    a.bottom <= b.top + 0.01f || b.bottom <= a.top + 0.01f
                assertTrue("foto $i dan $j bertumpuk", terpisah)
            }
        }
    }

    @Test
    fun `jarak antar foto dihormati`() {
        val list = items(1f, 1f)
        val arranged = list.arrangedInGrid(sheet(list).printable, columns = 2)
        // Sel bersebelahan; jarak antar tepi sel minimal sebesar gap.
        val jarak = arranged[1].rect.left - arranged[0].rect.right
        assertTrue("jarak $jarak kurang dari $DEFAULT_GAP_MM mm", jarak >= DEFAULT_GAP_MM - 0.01f)
    }

    @Test
    fun `daftar kosong menghasilkan susunan kosong`() {
        assertTrue(arrangeInGrid(emptyList(), sheet(emptyList()).printable).isEmpty())
    }

    // ----------------------------------------------------- menggeser foto

    @Test
    fun `menggeser memindahkan foto sejauh yang diminta`() {
        val item = SheetItem(1, 1f, RectMm(50f, 50f, 40f, 40f))
        val moved = item.movedBy(10f, -5f, a4.widthMm, a4.heightMm)

        assertEquals(60f, moved.rect.left, 0.001f)
        assertEquals(45f, moved.rect.top, 0.001f)
        assertEquals(item.rect.width, moved.rect.width, 0.001f)
    }

    @Test
    fun `pusat foto tidak bisa digeser keluar kertas`() {
        val item = SheetItem(1, 1f, RectMm(50f, 50f, 40f, 40f))
        val jauh = item.movedBy(9999f, 9999f, a4.widthMm, a4.heightMm)

        assertEquals(a4.widthMm, jauh.rect.centerX, 0.01f)
        assertEquals(a4.heightMm, jauh.rect.centerY, 0.01f)
    }

    // ------------------------------------------------- mengubah ukuran foto

    @Test
    fun `mengubah ukuran menjaga pusat dan rasio`() {
        val item = SheetItem(1, 2f, RectMm(50f, 50f, 80f, 40f))
        val besar = item.scaledBy(1.5f, a4.widthMm, a4.heightMm)

        assertEquals(item.rect.centerX, besar.rect.centerX, 0.001f)
        assertEquals(item.rect.centerY, besar.rect.centerY, 0.001f)
        assertEquals(120f, besar.rect.width, 0.01f)
        assertEquals(60f, besar.rect.height, 0.01f)
    }

    @Test
    fun `foto tidak bisa dikecilkan sampai hilang`() {
        var item = SheetItem(1, 1f, RectMm(50f, 50f, 40f, 40f))
        repeat(30) { item = item.scaledBy(0.5f, a4.widthMm, a4.heightMm) }

        assertTrue(
            "sisi terpendek ${item.rect.width} di bawah batas $MIN_ITEM_MM mm",
            item.rect.width >= MIN_ITEM_MM - 0.01f
        )
    }

    @Test
    fun `foto tidak bisa membengkak tanpa batas`() {
        var item = SheetItem(1, 1f, RectMm(50f, 50f, 40f, 40f))
        repeat(30) { item = item.scaledBy(2f, a4.widthMm, a4.heightMm) }

        assertTrue(
            "sisi terpanjang ${item.rect.width} jauh melewati kertas",
            item.rect.width <= a4.heightMm * 3f + 0.01f
        )
    }

    // ------------------------------------------------------------ putaran

    @Test
    fun `putaran seperempat menukar sisi dan mempertahankan pusat`() {
        val item = SheetItem(1, 3f / 2f, RectMm(50f, 60f, 90f, 60f))
        val diputar = item.rotatedBy(1)

        assertEquals(90, diputar.rotationDegrees)
        assertEquals("lebar harus jadi tinggi lama", 60f, diputar.rect.width, 0.001f)
        assertEquals("tinggi harus jadi lebar lama", 90f, diputar.rect.height, 0.001f)
        // Foto berputar di tempat, tidak melompat.
        assertEquals(item.rect.centerX, diputar.rect.centerX, 0.001f)
        assertEquals(item.rect.centerY, diputar.rect.centerY, 0.001f)
    }

    @Test
    fun `rasio tampil ikut terbalik saat diputar seperempat`() {
        val item = SheetItem(1, 3f / 2f, RectMm(0f, 0f, 90f, 60f))
        assertEquals(3f / 2f, item.displayAspect, 0.001f)
        assertEquals(2f / 3f, item.rotatedBy(1).displayAspect, 0.001f)
        // Setengah putaran tidak menukar sisi.
        assertEquals(3f / 2f, item.rotatedBy(2).displayAspect, 0.001f)
    }

    @Test
    fun `setengah putaran tidak mengubah bentuk kotak`() {
        val item = SheetItem(1, 2f, RectMm(30f, 40f, 80f, 40f))
        val diputar = item.rotatedBy(2)

        assertEquals(180, diputar.rotationDegrees)
        assertEquals(item.rect, diputar.rect)
    }

    @Test
    fun `empat kali putaran kembali seperti semula`() {
        val item = SheetItem(1, 4f / 3f, RectMm(25f, 35f, 120f, 90f))
        var berputar = item
        repeat(4) { berputar = berputar.rotatedBy(1) }

        assertEquals(0, berputar.rotationDegrees)
        assertEquals(item.rect.width, berputar.rect.width, 0.001f)
        assertEquals(item.rect.height, berputar.rect.height, 0.001f)
        assertEquals(item.rect.left, berputar.rect.left, 0.001f)
        assertEquals(item.rect.top, berputar.rect.top, 0.001f)
    }

    @Test
    fun `putar kiri adalah kebalikan putar kanan`() {
        val item = SheetItem(1, 16f / 9f, RectMm(20f, 20f, 160f, 90f))
        val bolakBalik = item.rotatedBy(1).rotatedBy(-1)

        assertEquals(item.rotationDegrees, bolakBalik.rotationDegrees)
        assertEquals(item.rect.width, bolakBalik.rect.width, 0.001f)
        assertEquals(item.rect.height, bolakBalik.rect.height, 0.001f)
    }

    @Test
    fun `putar kiri dari nol menghasilkan 270 derajat`() {
        val item = SheetItem(1, 1.5f, RectMm(0f, 0f, 60f, 40f))
        assertEquals(270, item.rotatedBy(-1).rotationDegrees)
    }

    @Test
    fun `penyusunan memakai rasio setelah diputar`() {
        // Foto mendatar yang diputar tegak harus disusun sebagai foto tegak.
        val mendatar = SheetItem(1, 2f, RectMm(0f, 0f, 10f, 5f))
        val tegak = mendatar.rotatedBy(1)
        val printable = sheet(emptyList()).printable

        val hasil = listOf(tegak).arrangedInGrid(printable).single()
        assertTrue(
            "hasil susunan harus lebih tinggi daripada lebar, dapat " +
                "${hasil.rect.width}x${hasil.rect.height}",
            hasil.rect.height > hasil.rect.width
        )
        assertEquals(0.5f, hasil.rect.width / hasil.rect.height, 0.01f)
    }

    @Test
    fun `pemotongan dihitung dari kotak setelah diputar`() {
        // Foto mendatar ini muat; setelah diputar jadi tinggi dan keluar bawah.
        val item = SheetItem(1, 3f, RectMm(20f, 260f, 150f, 50f))
        assertTrue("sebelum diputar sudah keluar", sheet(listOf(item)).hasOverflow)

        val muat = SheetItem(2, 3f, RectMm(20f, 100f, 150f, 50f))
        assertFalse(sheet(listOf(muat)).hasOverflow)
        // Setelah diputar tingginya jadi 150 mm, pusatnya tetap di y = 125 mm,
        // jadi tepi atasnya naik ke 50 mm dan masih di dalam area cetak.
        val diputar = muat.rotatedBy(1)
        assertEquals(50f, diputar.rect.top, 0.01f)
        assertEquals(200f, diputar.rect.bottom, 0.01f)
        assertFalse(sheet(listOf(diputar)).hasOverflow)
    }

    // ---------------------------------------------------------- pemotongan

    @Test
    fun `foto yang keluar area cetak terdeteksi beserta jaraknya`() {
        val didalam = SheetItem(1, 1f, RectMm(50f, 50f, 40f, 40f))
        val diluar = SheetItem(2, 1f, RectMm(-10f, 50f, 40f, 40f))
        val layout = sheet(listOf(didalam, diluar))

        assertTrue(layout.hasOverflow)
        assertEquals(listOf(2L), layout.clipped.map { it.id })
        assertEquals(13f, layout.overflowOf(diluar).leftMm, 0.01f)
        assertEquals(0f, layout.overflowOf(diluar).rightMm, 0.01f)
        assertFalse(layout.overflowOf(didalam).any)
    }

    // ----------------------------------------------------------- pemilihan

    @Test
    fun `menyentuh titik memilih foto teratas di situ`() {
        val bawah = SheetItem(1, 1f, RectMm(20f, 20f, 60f, 60f))
        val atas = SheetItem(2, 1f, RectMm(40f, 40f, 60f, 60f))
        val layout = sheet(listOf(bawah, atas))

        // Di area tumpang tindih, yang digambar terakhir yang terpilih.
        assertEquals(2L, layout.itemAt(50f, 50f)?.id)
        assertEquals(1L, layout.itemAt(25f, 25f)?.id)
        assertNull("di luar semua foto tidak boleh memilih apa pun", layout.itemAt(5f, 5f))
    }

    @Test
    fun `foto yang dipilih naik ke tumpukan paling atas`() {
        val list = items(1f, 1f, 1f)
        val naik = list.broughtToFront(0L)

        assertEquals(3, naik.size)
        assertEquals(0L, naik.last().id)
        assertEquals(listOf(1L, 2L, 0L), naik.map { it.id })
    }

    @Test
    fun `menaikkan id yang tidak ada tidak mengubah apa pun`() {
        val list = items(1f, 1f)
        assertSame(list, list.broughtToFront(99L))
    }

    // --------------------------------------------------------- area cetak

    @Test
    fun `margin mempersempit area cetak lembar`() {
        val sempit = sheet(emptyList(), marginMm = 20f).printable
        val lebar = sheet(emptyList(), marginMm = 3f).printable

        assertTrue(sempit.width < lebar.width)
        assertEquals(20f, sempit.left, 0.001f)
        assertEquals(a4.widthMm - 40f, sempit.width, 0.01f)
    }
}
