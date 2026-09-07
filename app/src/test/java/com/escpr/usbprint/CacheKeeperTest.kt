package com.escpr.usbprint

import com.escpr.usbprint.util.CachedFile
import com.escpr.usbprint.util.cacheFilesToDelete
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pemilihan berkas cache yang boleh dihapus.
 *
 * Ini kode yang menghapus berkas orang. Yang paling penting bukan bahwa ia
 * membersihkan, tetapi bahwa ia **tidak pernah** menghapus berkas yang sedang
 * dipakai -- kalau salah, dokumen bisa lenyap di tengah pekerjaan cetak.
 */
class CacheKeeperTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100L * day

    private fun file(name: String, sizeMb: Long = 1, ageDays: Long = 0) =
        CachedFile(name, sizeMb * 1024 * 1024, now - ageDays * day)

    private fun names(files: List<CachedFile>) = files.map { it.path }.toSet()

    @Test
    fun `cache yang masih muda dan kecil tidak disentuh`() {
        val files = listOf(file("a", ageDays = 1), file("b", ageDays = 3))
        assertEquals(emptyList<CachedFile>(), cacheFilesToDelete(files, emptySet(), now))
    }

    @Test
    fun `yang lebih tua dari tujuh hari dibuang`() {
        val files = listOf(file("baru", ageDays = 2), file("lama", ageDays = 30))
        assertEquals(setOf("lama"), names(cacheFilesToDelete(files, emptySet(), now)))
    }

    @Test
    fun `berkas yang sedang dipakai tidak pernah dihapus walau sangat tua`() {
        val files = listOf(file("dipakai", ageDays = 999), file("lama", ageDays = 999))
        val doomed = cacheFilesToDelete(files, keep = setOf("dipakai"), now = now)
        assertEquals(setOf("lama"), names(doomed))
    }

    @Test
    fun `berkas yang sedang dipakai bertahan walau cache jauh melewati batas`() {
        // Sepuluh berkas 100 MB, semuanya muda: hanya aturan ukuran yang berlaku.
        val files = List(10) { file("f$it", sizeMb = 100, ageDays = it.toLong()) }
        val doomed = cacheFilesToDelete(files, keep = setOf("f9", "f8"), now = now)
        assertTrue("f9" !in names(doomed))
        assertTrue("f8" !in names(doomed))
    }

    @Test
    fun `saat melewati batas ukuran, yang paling lama tidak disentuh dibuang lebih dulu`() {
        val files = List(5) { file("f$it", sizeMb = 100, ageDays = it.toLong()) }
        val doomed = cacheFilesToDelete(files, emptySet(), now)
        // 500 MB dengan anggaran 256 MB: harus turun sampai muat, mulai dari
        // f4 yang paling tua.
        assertTrue("f4" in names(doomed))
        val sisa = files.filterNot { it in doomed }.sumOf { it.sizeBytes }
        assertTrue("sisa masih " + sisa, sisa <= 256L * 1024 * 1024)
    }

    @Test
    fun `yang paling baru selamat saat pemangkasan karena ukuran`() {
        val files = List(5) { file("f$it", sizeMb = 100, ageDays = it.toLong()) }
        val doomed = cacheFilesToDelete(files, emptySet(), now)
        assertTrue("f0 yang paling baru ikut terbuang", "f0" !in names(doomed))
    }

    @Test
    fun `tidak menghapus lebih dari yang dibutuhkan`() {
        val files = List(5) { file("f$it", sizeMb = 100, ageDays = it.toLong()) }
        val doomed = cacheFilesToDelete(files, emptySet(), now)
        // 500 MB, anggaran 256 MB: membuang 3 sudah cukup (sisa 200 MB).
        assertEquals(3, doomed.size)
    }

    @Test
    fun `berkas yang baru saja ditulis tidak dihapus walau cache penuh`() {
        // Berkas yang sedang disalin masuk belum tercatat sebagai dipakai oleh
        // siapa pun. Tanpa jeda ini, sapuan latar bisa menghapus berkas yang
        // baru separuh tersalin -- tepat berkas yang sedang dibuka pengguna.
        val sedangDisalin = CachedFile("baru", 400L * 1024 * 1024, now)
        val lain = List(4) { file("f$it", sizeMb = 100, ageDays = 1) }
        val doomed = cacheFilesToDelete(lain + sedangDisalin, emptySet(), now)
        assertTrue("berkas yang baru ditulis ikut dihapus", "baru" !in names(doomed))
    }

    @Test
    fun `cache kosong tidak menghasilkan apa-apa`() {
        assertEquals(emptyList<CachedFile>(), cacheFilesToDelete(emptyList(), emptySet(), now))
    }

    @Test
    fun `berkas yang dipertahankan tetap dihitung terhadap anggaran`() {
        // Satu berkas 300 MB yang sedang dipakai sudah melewati anggaran
        // sendirian. Yang lain harus tetap dibuang, dan yang dipakai bertahan.
        val files = listOf(
            file("dipakai", sizeMb = 300, ageDays = 1),
            file("lain", sizeMb = 50, ageDays = 1),
        )
        val doomed = cacheFilesToDelete(files, keep = setOf("dipakai"), now = now)
        assertEquals(setOf("lain"), names(doomed))
    }

    @Test
    fun `aturan umur dan ukuran tidak menghapus berkas yang sama dua kali`() {
        val files = List(6) { file("f$it", sizeMb = 100, ageDays = (it * 10).toLong()) }
        val doomed = cacheFilesToDelete(files, emptySet(), now)
        assertEquals(doomed.size, doomed.distinctBy { it.path }.size)
    }
}
