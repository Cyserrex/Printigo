package com.escpr.usbprint

import com.escpr.usbprint.escpr.Maintenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Membandingkan urutan cek nozzle dengan rekaman driver Epson sungguhan.
 *
 * Ini uji terkuat di seluruh proyek, karena acuannya bukan bacaan, bukan
 * dokumentasi, dan bukan penalaran -- melainkan byte yang benar-benar mengalir
 * di kabel USB ketika tombol Nozzle Check ditekan di driver resmi.
 *
 * Rekamannya diambil dengan USBPcap pada Epson L3110, satu paket bulk OUT
 * sepanjang 132 byte. Empat susunan yang disusun sendiri sebelumnya semuanya
 * meninggalkan kertas tertahan; rekaman ini menunjukkan kenapa, dan yang
 * dijaga uji ini adalah agar kode tidak menyimpang lagi darinya.
 */
class DriverCaptureTest {

    /**
     * Urutan yang terekam, apa adanya.
     *
     * Muatan `TI` -- delapan byte tanggal dan jam -- ikut apa adanya juga dan
     * dinolkan saat membandingkan, karena jamnya memang berbeda tiap kali.
     */
    private val rekaman = ("00 00 00 1B 01 40 45 4A 4C 20 31 32 38 34 2E 34 0A " +
        "40 45 4A 4C 20 20 20 20 20 0A " +
        "1B 40 1B 40 " +
        "1B 28 52 08 00 00 52 45 4D 4F 54 45 31 " +
        "54 49 08 00 00 07 EA 09 0B 17 15 36 " +
        "4A 53 04 00 00 00 00 00 " +
        "4E 43 02 00 00 00 " +
        "1B 00 00 00 " +
        "0D 0A 0D 0A " +
        "1B 28 52 08 00 00 52 45 4D 4F 54 45 31 " +
        "56 49 02 00 00 00 " +
        "4C 44 00 00 " +
        "1B 00 00 00 " +
        "0C " +
        "1B 40 1B 40 " +
        "1B 28 52 08 00 00 52 45 4D 4F 54 45 31 " +
        "4A 45 01 00 00 " +
        "1B 00 00 00").split(" ").map { it.toInt(16).toByte() }.toByteArray()

    /** Panjang muatan TI; isinya jam saat perintah dibuat, jadi selalu berbeda. */
    private fun tanpaJam(bytes: ByteArray): ByteArray {
        val salinan = bytes.copyOf()
        val ti = "54 49 08 00 00".split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val at = salinan.indices.firstOrNull { i ->
            i + ti.size <= salinan.size &&
                (0 until ti.size).all { salinan[i + it] == ti[it] }
        } ?: return salinan
        for (i in at + ti.size until at + ti.size + 7) salinan[i] = 0
        return salinan
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `cek nozzle sama persis dengan yang dikirim driver`() {
        assertEquals(hex(tanpaJam(rekaman)), hex(tanpaJam(Maintenance.nozzleCheck())))
    }

    @Test
    fun `panjangnya sama dengan rekaman`() {
        assertEquals(132, rekaman.size)
        assertEquals(rekaman.size, Maintenance.nozzleCheck().size)
    }

    @Test
    fun `form feed datang sebelum penutup pekerjaan, bukan sesudah`() {
        // Inilah yang selama ini terlewat. Empat susunan buatan sendiri
        // menutup pekerjaan lebih dulu, dan kertas tidak pernah keluar.
        val h = hex(Maintenance.nozzleCheck())
        val ff = h.indexOf("0C 1B 40")
        val je = h.indexOf("4A 45")
        assertTrue("form feed tidak ada di tempatnya", ff >= 0)
        assertTrue("JE harus sesudah form feed", je > ff)
    }

    @Test
    fun `perintahnya dipecah jadi tiga blok REMOTE1, bukan satu`() {
        val h = hex(Maintenance.nozzleCheck())
        val blok = Regex("1B 28 52 08 00 00").findAll(h).count()
        assertEquals(3, blok)
    }

    @Test
    fun `pembersihan head memakai susunan yang sama tanpa form feed`() {
        // Ini kesimpulan, bukan rekaman: yang direkam baru cek nozzle. Kalau
        // suatu saat pembersihan head ikut direkam dan ternyata berbeda, uji
        // inilah yang harus berubah -- dengan sadar.
        val bersih = hex(Maintenance.headCleaning())
        val nozzle = hex(Maintenance.nozzleCheck())
        assertEquals(nozzle.replace("4E 43", "43 48").replace(" 0C 1B 40 1B 40", " 1B 40 1B 40"), bersih)
    }

    @Test
    fun `membaca status tidak ikut membuka pekerjaan`() {
        val h = hex(Maintenance.statusRequest())
        assertTrue(h, !h.contains("4A 53"))
        assertTrue(h, !h.contains("4A 45"))
        assertTrue(h, !h.contains(" 0C"))
    }
}
