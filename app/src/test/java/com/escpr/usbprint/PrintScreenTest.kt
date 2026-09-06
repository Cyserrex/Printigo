package com.escpr.usbprint

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.File
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.escpr.usbprint.escpr.ColorMode
import com.escpr.usbprint.escpr.Dpi
import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.escpr.Quality
import com.escpr.usbprint.layout.ContentPlacement
import com.escpr.usbprint.ui.PrintOutcome
import com.escpr.usbprint.ui.StepAction
import com.escpr.usbprint.ui.adviceFor
import com.escpr.usbprint.usb.PrinterErrorKind
import com.escpr.usbprint.ui.PrintScreen
import com.escpr.usbprint.ui.PrintViewModel
import com.escpr.usbprint.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Merender layar utama di JVM lewat Robolectric.
 *
 * Ini tidak menggantikan pengujian di perangkat sungguhan, tapi menangkap
 * kelas kesalahan yang paling mungkin muncul sesudah tampilan ditulis ulang:
 * gagal saat komposisi pertama.
 *
 * Layar dibuat tinggi lewat qualifier supaya seluruh isi yang bisa digulir
 * ikut terlihat, sehingga assertIsDisplayed tetap bermakna.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w420dp-h2600dp")
class PrintScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun launch(usbHost: Boolean = true): PrintViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Robolectric tidak mengaku mendukung fitur apa pun kecuali diberi tahu.
        shadowOf(app.packageManager)
            .setSystemFeature(PackageManager.FEATURE_USB_HOST, usbHost)
        val viewModel = PrintViewModel(app)
        compose.setContent { AppTheme { PrintScreen(viewModel) } }
        return viewModel
    }

    @Test
    fun `layar utama tersusun tanpa gagal`() {
        launch()
        compose.onNodeWithText("USB Printer OTG").assertIsDisplayed()
        compose.onNodeWithText("Kertas").assertIsDisplayed()
        compose.onNodeWithText("Hasil cetak").assertIsDisplayed()
    }

    @Test
    fun `tanpa dokumen pratinjau menampilkan keadaan kosong`() {
        launch()
        compose.onNodeWithText("Belum ada dokumen").assertIsDisplayed()
        compose.onNodeWithText("Pilih berkas").assertIsDisplayed()
    }

    @Test
    fun `tanpa printer terpasang daftar periksa menuntun dan menawarkan satu tombol`() {
        launch()
        // Robolectric tidak menyediakan perangkat USB apa pun.
        compose.onNodeWithText("HP mendukung USB OTG").assertIsDisplayed()
        compose.onNodeWithText("Printer terdeteksi").assertIsDisplayed()
        compose.onNodeWithText("Izin akses USB").assertIsDisplayed()
        compose.onNodeWithText("Colok printer ke HP", substring = true).assertIsDisplayed()
        compose.onNodeWithText(StepAction.REFRESH.label).assertIsDisplayed()
    }

    @Test
    fun `hp tanpa dukungan otg dikatakan terus terang`() {
        launch(usbHost = false)
        compose.onNodeWithText("tidak mendukung", substring = true).assertIsDisplayed()
    }

    @Test
    fun `tombol cetak mati selama sambungan belum siap`() {
        val viewModel = launch()
        assertEquals(false, viewModel.state.value.canPrint)
        assertEquals(false, viewModel.state.value.connection.ready)
    }

    @Test
    fun `kartu hasil kegagalan menampilkan sebab dan tindakan, lalu bisa ditutup`() {
        val viewModel = launch()
        // Jalur sungguhan: berkas yang tidak bisa dibaca -> DOCUMENT_UNREADABLE.
        viewModel.openDocument(Uri.parse("content://com.escpr.tidakada/999"))
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.outcome is PrintOutcome.Failed
        }
        compose.waitForIdle()

        val advice = adviceFor(PrinterErrorKind.DOCUMENT_UNREADABLE)
        compose.onNodeWithText(advice.title).assertIsDisplayed()
        compose.onNodeWithText(advice.action!!.label).assertIsDisplayed()

        compose.onNodeWithText("Tutup").performClick()
        compose.waitForIdle()
        assertEquals(PrintOutcome.None, viewModel.state.value.outcome)
    }


    /**
     * Gambar sederhana di cache, supaya alur dengan dokumen bisa diuji.
     *
     * Yang ditunggu adalah objek dokumennya berganti, bukan namanya: di
     * Robolectric pada Windows, Uri.fromFile menghasilkan URI tanpa path
     * segment sehingga nama dokumen berisi path penuh, bukan nama berkas.
     * Menunggu previewImage != null saja juga tidak cukup, karena syarat itu
     * sudah terpenuhi sejak muatan sebelumnya.
     */
    private fun loadSampleDocument(viewModel: PrintViewModel, name: String = "uji.png") {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val bitmap = Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(120, 190, 255))
        val file = File(app.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val before = viewModel.state.value.document
        viewModel.openDocument(Uri.fromFile(file))
        compose.waitUntil(timeoutMillis = 10_000) {
            val now = viewModel.state.value
            now.document != null && now.document !== before && now.previewImage != null
        }
        compose.waitForIdle()
    }

    @Test
    fun `editor tata letak hanya bisa dibuka kalau ada dokumen`() {
        val viewModel = launch()
        viewModel.openLayoutEditor()
        compose.waitForIdle()
        assertEquals(false, viewModel.state.value.layoutEditorOpen)
    }

    @Test
    fun `menekan atur tata letak membuka editor layar penuh, lalu bisa ditutup`() {
        val viewModel = launch()
        loadSampleDocument(viewModel)

        compose.onNodeWithText("Atur tata letak").performClick()
        compose.waitForIdle()
        assertEquals(true, viewModel.state.value.layoutEditorOpen)

        // Kontrol khas editor muncul.
        compose.onNodeWithText("Selesai").assertIsDisplayed()
        compose.onNodeWithText("Ukuran").assertIsDisplayed()
        compose.onNodeWithText("Batas cetak").assertIsDisplayed()

        compose.onNodeWithText("Selesai").performClick()
        compose.waitForIdle()
        assertEquals(false, viewModel.state.value.layoutEditorOpen)
    }

    @Test
    fun `tombol perbesar di editor mengubah ukuran isi`() {
        val viewModel = launch()
        loadSampleDocument(viewModel)
        viewModel.openLayoutEditor()
        compose.waitForIdle()

        val sebelum = viewModel.state.value.pageLayout.content.width
        compose.onNodeWithContentDescription("Perbesar").performClick()
        compose.waitForIdle()
        val sesudah = viewModel.state.value.pageLayout.content.width

        assertTrue("menekan perbesar harus melebarkan isi", sesudah > sebelum)
        assertEquals(true, viewModel.state.value.placement.manual)
    }

    @Test
    fun `mengganti dokumen menutup editor dan mengembalikan penempatan`() {
        val viewModel = launch()
        loadSampleDocument(viewModel)
        viewModel.nudgePlacement(10f, 10f, 1.3f)
        viewModel.openLayoutEditor()
        compose.waitForIdle()
        assertEquals(true, viewModel.state.value.layoutEditorOpen)

        loadSampleDocument(viewModel, name = "uji-kedua.png")
        assertEquals(false, viewModel.state.value.layoutEditorOpen)
        assertEquals(ContentPlacement.Fit, viewModel.state.value.placement)
    }

    @Test
    fun `menekan chip ukuran kertas mengubah pengaturan`() {
        val viewModel = launch()

        compose.onNodeWithText("A6").performClick()
        compose.waitForIdle()

        assertEquals(PaperSize.A6, viewModel.state.value.settings.paper)
    }

    @Test
    fun `nilai margin tampil dan ikut berubah`() {
        val viewModel = launch()
        compose.onNodeWithText("3 mm").assertIsDisplayed()

        viewModel.updateSettings { it.copy(marginMm = 12f) }
        compose.waitForIdle()

        compose.onNodeWithText("12 mm").assertIsDisplayed()
    }

    /**
     * Sengaja memakai sentuhan sungguhan, bukan pemanggilan aksi semantik.
     * Versi sebelumnya memakai SegmentedButton dan uji ini menangkap bahwa
     * sentuhannya tidak pernah sampai ke onClick, padahal batas node-nya benar.
     */
    @Test
    fun `menyentuh pilihan warna mengubah mode ke hitam putih`() {
        val viewModel = launch()
        val node = compose.onNodeWithText("Hitam putih")
        node.assertIsDisplayed()
        node.assertHasClickAction()

        node.performClick()
        compose.waitForIdle()

        assertEquals(ColorMode.MONO, viewModel.state.value.settings.colorMode)
    }

    @Test
    fun `menyentuh pilihan kualitas dan resolusi ikut bekerja`() {
        val viewModel = launch()

        compose.onNodeWithText("Draft").performClick()
        compose.onNodeWithText("720 dpi").performClick()
        compose.waitForIdle()

        assertEquals(Quality.DRAFT, viewModel.state.value.settings.quality)
        assertEquals(Dpi.DPI720, viewModel.state.value.settings.dpi)
    }
}
