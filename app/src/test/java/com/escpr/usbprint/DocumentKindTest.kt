package com.escpr.usbprint

import com.escpr.usbprint.ui.OutcomeAction
import com.escpr.usbprint.ui.adviceFor
import com.escpr.usbprint.usb.PrinterErrorKind
import com.escpr.usbprint.util.DocumentKind
import com.escpr.usbprint.util.classifyDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Menentukan berkas mana yang bisa dicetak dan mana yang harus ditolak dengan
 * penjelasan.
 *
 * Tipe MIME dan nama berkas sama-sama dipakai karena keduanya bisa kosong:
 * sebagian penyedia dokumen tidak melaporkan MIME, dan sebagian berkas tidak
 * berekstensi.
 */
class DocumentKindTest {

    @Test
    fun `pdf dikenali dari mime maupun nama`() {
        assertEquals(DocumentKind.PDF, classifyDocument("application/pdf", ""))
        assertEquals(DocumentKind.PDF, classifyDocument("", "laporan.pdf"))
        assertEquals(DocumentKind.PDF, classifyDocument("", "LAPORAN.PDF"))
    }

    @Test
    fun `gambar dikenali dari mime maupun nama`() {
        assertEquals(DocumentKind.IMAGE, classifyDocument("image/jpeg", ""))
        assertEquals(DocumentKind.IMAGE, classifyDocument("image/heic", ""))
        for (extension in listOf("jpg", "jpeg", "png", "webp", "heic", "bmp")) {
            assertEquals(
                "ekstensi .$extension seharusnya gambar",
                DocumentKind.IMAGE, classifyDocument("", "foto.$extension")
            )
        }
    }

    @Test
    fun `berkas office dikenali, bukan disangka gambar`() {
        val nama = listOf(
            "surat.doc", "surat.docx", "data.xls", "data.xlsx",
            "materi.ppt", "materi.pptx", "naskah.odt", "tabel.ods", "catatan.rtf",
        )
        for (berkas in nama) {
            assertEquals(
                "$berkas seharusnya dikenali sebagai berkas Office",
                DocumentKind.OFFICE, classifyDocument("", berkas)
            )
        }
    }

    @Test
    fun `berkas office dikenali dari mime yang panjang`() {
        val mime = listOf(
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.oasis.opendocument.text",
            "application/rtf",
        )
        for (jenis in mime) {
            assertEquals(
                "$jenis seharusnya dikenali sebagai berkas Office",
                DocumentKind.OFFICE, classifyDocument(jenis, "tanpa-ekstensi")
            )
        }
    }

    @Test
    fun `tanpa mime dan tanpa ekstensi tetap dicoba sebagai gambar`() {
        // Sebagian penyedia dokumen tidak melaporkan apa pun. Menolak berkas
        // seperti itu akan memblokir gambar yang sebenarnya bisa dicetak, jadi
        // yang benar adalah mencoba dulu.
        assertEquals(DocumentKind.UNKNOWN, classifyDocument("", "IMG_0001"))
        assertEquals(DocumentKind.UNKNOWN, classifyDocument("application/octet-stream", "berkas"))
    }

    @Test
    fun `pdf menang atas dugaan lain`() {
        assertEquals(DocumentKind.PDF, classifyDocument("application/pdf", "surat.docx"))
    }

    @Test
    fun `saran untuk format tak didukung mengarahkan ke ekspor pdf`() {
        val advice = adviceFor(PrinterErrorKind.UNSUPPORTED_FORMAT)

        assertEquals(OutcomeAction.PICK_FILE, advice.action)
        assertTrue(
            "saran harus menyebut PDF sebagai jalan keluarnya",
            advice.hint.contains("PDF")
        )
        // Tidak boleh menuduh berkasnya rusak, karena memang tidak.
        assertTrue(!advice.hint.contains("rusak"))
    }
}
