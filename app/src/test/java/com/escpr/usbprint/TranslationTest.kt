package com.escpr.usbprint

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.escpr.usbprint.escpr.MaintenanceTask
import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.escpr.PrintPreset
import com.escpr.usbprint.escpr.Quality
import com.escpr.usbprint.ui.adviceFor
import com.escpr.usbprint.usb.PrinterErrorKind
import com.escpr.usbprint.util.AppLanguage
import com.escpr.usbprint.util.localizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.regex.Pattern

/**
 * Menjaga kedua bahasa tetap sepadan.
 *
 * Terjemahan rusak dengan cara yang tidak terlihat: satu kunci lupa disalin dan
 * aplikasi diam-diam menampilkan bahasa Inggris di tengah layar Indonesia, atau
 * penanda `%1$s` hilang sebelah dan aplikasi mati dengan
 * IllegalFormatException tepat pada kalimat kesalahan -- yaitu pada saat
 * pengguna paling butuh membacanya.
 *
 * Karena itu kesepadanannya diperiksa mesin, bukan mata. Menambah bahasa
 * ketiga suatu saat cukup menambah satu baris di [bahasa].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TranslationTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val bahasa = listOf(
        AppLanguage.ENGLISH to localizedContext(app, AppLanguage.ENGLISH),
        AppLanguage.INDONESIAN to localizedContext(app, AppLanguage.INDONESIAN),
    )

    /** Semua kunci string aplikasi, dibaca dari R.string lewat refleksi. */
    private val kunci: List<Pair<String, Int>> = R.string::class.java.fields
        .filter { it.type == Int::class.java }
        .map { it.name to it.getInt(null) }
        .sortedBy { it.first }

    @Test
    fun `setiap kalimat punya terjemahan di kedua bahasa`() {
        assertTrue("R.string kosong -- refleksinya salah", kunci.size > 100)

        val hilang = mutableListOf<String>()
        for ((nama, id) in kunci) {
            for ((label, ctx) in bahasa) {
                if (ctx.getString(id).isBlank()) hilang += "$nama ($label)"
            }
        }
        assertEquals("kalimat kosong", emptyList<String>(), hilang)
    }

    /**
     * Kalimat yang sama persis di kedua bahasa hampir selalu berarti lupa
     * diterjemahkan -- kecuali memang tidak ada yang bisa diterjemahkan.
     */
    @Test
    fun `kalimat yang tidak diterjemahkan hanyalah yang memang sama di kedua bahasa`() {
        val sama = kunci.filter { (_, id) ->
            bahasa[0].second.getString(id) == bahasa[1].second.getString(id)
        }.map { it.first }.toSet()

        // Daftar putih. Nama satuan, ukuran kertas, merek, dan angka memang
        // ditulis sama; sisanya harus berbeda.
        val boleh = setOf(
            "app_name", "app_title", "settings_language", "language_indonesia",
            "language_indonesian", "language_english", "ink_percent", "ink_cyan",
            "ink_magenta", "media_matte", "quality_normal", "quality_draft_short",
            "settings_summary", "editor_size_value", "editor_none",
            "settings_margin_mm", "log_status", "log_printer",
            "paper_a4", "paper_letter", "paper_legal", "paper_a5", "paper_a6",
            "paper_b5", "paper_4r", "paper_a4_short", "paper_letter_short",
            "paper_legal_short", "paper_a5_short", "paper_a6_short",
            "paper_b5_short", "paper_4r_short",
            // Betul-betul sama kalimatnya di kedua bahasa.
            "settings_margin", "link_high_speed",
        )
        assertEquals("belum diterjemahkan", emptySet<String>(), sama - boleh)
    }

    /**
     * Penanda format harus sama persis di kedua bahasa.
     *
     * Ini satu-satunya kesalahan terjemahan yang membuat aplikasi mati, bukan
     * sekadar terbaca aneh: getString akan melempar kalau kalimatnya meminta
     * argumen yang tidak diberikan.
     */
    @Test
    fun `penanda format cocok antara kedua bahasa`() {
        // Penanda berhenti di huruf konversinya. Pola yang lebih longgar ikut
        // menelan titik di akhir kalimat dan melaporkan beda yang tidak ada.
        val pola = Pattern.compile("%\\d+\\$[-#+ 0,(]*[0-9.]*[a-zA-Z]|%%")
        val beda = mutableListOf<String>()
        for ((nama, id) in kunci) {
            val set = bahasa.map { (_, ctx) ->
                val m = pola.matcher(ctx.getString(id))
                buildList { while (m.find()) add(m.group()) }.sorted()
            }
            if (set[0] != set[1]) beda += "$nama: ${set[0]} vs ${set[1]}"
        }
        assertEquals("penanda format tidak cocok", emptyList<String>(), beda)
    }

    /**
     * Kalimat kesalahan tidak boleh membocorkan istilah teknis.
     *
     * Dulu diperiksa hanya pada bahasa Indonesia, karena memang hanya ada satu.
     */
    @Test
    fun `judul kegagalan bebas istilah teknis di kedua bahasa`() {
        for ((label, ctx) in bahasa) {
            for (kind in PrinterErrorKind.entries) {
                val judul = ctx.getString(adviceFor(kind).title)
                assertTrue("$kind kosong di $label", judul.isNotBlank())
                for (istilah in listOf("Exception", "null", "USB_", "bulk", "endpoint")) {
                    assertFalse(
                        "judul $kind ($label) mengandung istilah teknis '$istilah'",
                        judul.contains(istilah),
                    )
                }
            }
        }
    }

    /**
     * Isi kalimat yang benar-benar penting, dijaga di kedua bahasa.
     *
     * Ini pemeriksaan yang dulu ada di uji masing-masing, dipindah ke sini
     * begitu kalimatnya tidak lagi tersimpan di dalam kode.
     */
    @Test
    fun `peringatan pembersihan head menyebut tinta di kedua bahasa`() {
        val kata = mapOf(AppLanguage.ENGLISH to "ink", AppLanguage.INDONESIAN to "tinta")
        for ((label, ctx) in bahasa) {
            val kalimat = ctx.getString(MaintenanceTask.HEAD_CLEANING.confirmation).lowercase()
            assertTrue(
                "peringatan pembersihan head ($label) tidak menyebut tinta",
                kalimat.contains(kata.getValue(label)),
            )
        }
    }

    @Test
    fun `saran format tidak didukung menyebut PDF di kedua bahasa`() {
        val hint = adviceFor(PrinterErrorKind.UNSUPPORTED_FORMAT).hint
        for ((label, ctx) in bahasa) {
            assertTrue("saran ($label) tidak menyebut PDF", ctx.getString(hint).contains("PDF"))
        }
    }

    @Test
    fun `kesiapan escpr menyebut nama bahasanya di kedua bahasa`() {
        for ((label, ctx) in bahasa) {
            assertTrue(
                "kalimat dukungan ($label) tidak menyebut ESC slash P-R",
                ctx.getString(R.string.conn_support_yes).contains("ESC/P-R"),
            )
        }
    }

    /**
     * Chip harus tetap pendek.
     *
     * Terjemahan yang lebih panjang adalah cara paling sering sebuah tata letak
     * rusak setelah dipindah bahasa: chip yang muat satu baris dalam bahasa
     * Indonesia melipat jadi tiga baris dalam bahasa Inggris, dan tidak ada
     * yang menyadarinya sampai ada yang mengirim tangkapan layar.
     */
    @Test
    fun `label chip tidak menjadi jauh lebih panjang di bahasa lain`() {
        val pendek = buildList {
            PaperSize.entries.forEach { add(it.shortLabel) }
            Quality.entries.forEach { add(it.shortLabel) }
            PrintPreset.entries.forEach { add(it.label) }
            AppLanguage.entries.forEach { add(it.label) }
        }
        val terlalu = pendek.flatMap { id ->
            bahasa.mapNotNull { (label, ctx) ->
                val teks = ctx.getString(id)
                if (teks.length > 18) "$teks ($label, ${teks.length} huruf)" else null
            }
        }
        assertEquals("label chip terlalu panjang", emptyList<String>(), terlalu)
    }

    /**
     * Spasi yang disengaja tidak boleh dipangkas pengompil resource.
     *
     * Android memangkas spasi ganda dan spasi tepi kecuali isinya dikutip.
     * Baris ringkasan memakai spasi ganda sebagai pemisah dan potongan kalimat
     * lembar memakai spasi awal; keduanya diam-diam rusak saat kalimatnya
     * dipindah ke resource, dan hanya ketahuan karena satu uji tata letak ikut
     * gagal. Sejak itu dijaga langsung.
     */
    @Test
    fun `spasi yang disengaja tetap utuh di kedua bahasa`() {
        for ((label, ctx) in bahasa) {
            assertTrue(
                "pemisah ringkasan ($label) terpangkas",
                ctx.getString(R.string.settings_summary).contains("  ·  "),
            )
            assertTrue(
                "potongan lembar ($label) kehilangan spasi awal",
                ctx.getString(R.string.editor_sheet_of, 1, 2).startsWith(" "),
            )
        }
    }

    /** Bahasa Indonesia dipetakan ke "in", bukan "id". */
    @Test
    fun `penanda bahasa indonesia memakai kode lama yang dikenali android`() {
        assertEquals("in", AppLanguage.INDONESIAN.tag)
        val ctx: Context = localizedContext(app, AppLanguage.INDONESIAN)
        // Kalau kodenya salah, ini justru mengembalikan kalimat bahasa Inggris.
        assertEquals("Cetak", ctx.getString(R.string.bar_print))
    }
}
