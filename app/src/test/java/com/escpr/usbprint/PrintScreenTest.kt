package com.escpr.usbprint

import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.File
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.escpr.usbprint.escpr.ColorMode
import com.escpr.usbprint.escpr.Dpi
import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.escpr.PrintPreset
import com.escpr.usbprint.escpr.Quality
import com.escpr.usbprint.escpr.presetOf
import com.escpr.usbprint.ui.PrintOutcome
import com.escpr.usbprint.ui.StepAction
import com.escpr.usbprint.ui.adviceFor
import com.escpr.usbprint.usb.PrinterErrorKind
import com.escpr.usbprint.ui.PrintScreen
import com.escpr.usbprint.ui.PrintViewModel
import com.escpr.usbprint.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
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
        val viewModel = PrintViewModel(app, Dispatchers.Unconfined, Dispatchers.Unconfined)
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
     * Menambahkan satu foto ke lembar.
     *
     * Yang ditunggu adalah jumlah fotonya bertambah. Menunggu "ada isi" saja
     * tidak cukup, karena syarat itu sudah terpenuhi sejak foto sebelumnya.
     */
    private fun addSamplePhoto(viewModel: PrintViewModel, name: String = "uji.png") {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val bitmap = Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(120, 190, 255))
        val file = File(app.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val before = viewModel.state.value.photos.size
        viewModel.openDocument(Uri.fromFile(file))
        compose.waitUntil(timeoutMillis = 10_000) {
            viewModel.state.value.photos.size > before
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
        addSamplePhoto(viewModel)

        compose.onNodeWithText("Atur tata letak").performClick()
        compose.waitForIdle()
        assertEquals(true, viewModel.state.value.layoutEditorOpen)

        // Kontrol khas editor muncul.
        compose.onNodeWithText("Selesai").assertIsDisplayed()
        compose.onNodeWithText("Foto terpilih").assertIsDisplayed()
        compose.onNodeWithText("Batas cetak").assertIsDisplayed()

        compose.onNodeWithText("Selesai").performClick()
        compose.waitForIdle()
        assertEquals(false, viewModel.state.value.layoutEditorOpen)
    }

    @Test
    fun `tombol perbesar di editor mengubah ukuran isi`() {
        val viewModel = launch()
        addSamplePhoto(viewModel)
        viewModel.openLayoutEditor()
        compose.waitForIdle()

        val sebelum = viewModel.state.value.selectedPhoto!!.item.rect.width
        compose.onNodeWithContentDescription("Perbesar").performClick()
        compose.waitForIdle()
        val sesudah = viewModel.state.value.selectedPhoto!!.item.rect.width

        assertTrue("menekan perbesar harus melebarkan foto", sesudah > sebelum)
    }

    @Test
    fun `menambah foto kedua menyusun ulang keduanya tanpa bertumpuk`() {
        val viewModel = launch()
        addSamplePhoto(viewModel)
        val sendirian = viewModel.state.value.photos.single().item.rect.width

        addSamplePhoto(viewModel, name = "uji-kedua.png")
        val state = viewModel.state.value

        assertEquals(2, state.photos.size)
        // Disusun ulang, jadi masing-masing mengecil dan tidak saling menimpa.
        assertTrue("foto harus mengecil saat berbagi kertas",
            state.photos[0].item.rect.width < sendirian)
        val a = state.photos[0].item.rect
        val b = state.photos[1].item.rect
        val terpisah = a.right <= b.left + 0.01f || b.right <= a.left + 0.01f ||
            a.bottom <= b.top + 0.01f || b.bottom <= a.top + 0.01f
        assertTrue("dua foto bertumpuk setelah disusun", terpisah)
        assertEquals(false, state.sheetLayout.hasOverflow)
    }

    @Test
    fun `menyentuh foto memilihnya, dan yang terpilih bisa dihapus`() {
        val viewModel = launch()
        addSamplePhoto(viewModel)
        addSamplePhoto(viewModel, name = "uji-kedua.png")

        val target = viewModel.state.value.photos.first()
        viewModel.selectPhotoAt(target.item.rect.centerX, target.item.rect.centerY)
        assertEquals(target.id, viewModel.state.value.selectedPhotoId)

        viewModel.removeSelectedPhoto()
        assertEquals(1, viewModel.state.value.photos.size)
        assertEquals(false, viewModel.state.value.photos.any { it.id == target.id })
    }

    @Test
    fun `gestur hanya mengenai foto yang terpilih`() {
        val viewModel = launch()
        addSamplePhoto(viewModel)
        addSamplePhoto(viewModel, name = "uji-kedua.png")

        val photos = viewModel.state.value.photos
        val target = photos.first()
        val lainnya = photos.last()
        viewModel.selectPhotoAt(target.item.rect.centerX, target.item.rect.centerY)

        val sebelumLain = viewModel.state.value.photos.first { it.id == lainnya.id }.item.rect
        viewModel.nudgePlacement(12f, 0f, 1f)

        val sesudahTarget = viewModel.state.value.photos.first { it.id == target.id }.item.rect
        val sesudahLain = viewModel.state.value.photos.first { it.id == lainnya.id }.item.rect
        assertEquals(target.item.rect.left + 12f, sesudahTarget.left, 0.01f)
        assertEquals(sebelumLain.left, sesudahLain.left, 0.001f)
    }

    @Test
    fun `susun ulang mengembalikan semua foto ke dalam area cetak`() {
        val viewModel = launch()
        addSamplePhoto(viewModel)
        addSamplePhoto(viewModel, name = "uji-kedua.png")

        // Dorong satu foto jauh keluar kertas.
        viewModel.nudgePlacement(200f, 200f, 2f)
        assertEquals(true, viewModel.state.value.sheetLayout.hasOverflow)

        viewModel.arrangeGrid(2)
        assertEquals(false, viewModel.state.value.sheetLayout.hasOverflow)
    }

    @Test
    fun `tombol putar mengubah orientasi foto terpilih di editor`() {
        val viewModel = launch()
        addSamplePhoto(viewModel)
        viewModel.openLayoutEditor()
        compose.waitForIdle()

        val sebelum = viewModel.state.value.selectedPhoto!!.item
        assertEquals(0, sebelum.rotationDegrees)
        assertTrue("contoh harus mendatar", sebelum.rect.width > sebelum.rect.height)

        compose.onNodeWithText("Putar kanan").performClick()
        compose.waitForIdle()

        val sesudah = viewModel.state.value.selectedPhoto!!.item
        assertEquals(90, sesudah.rotationDegrees)
        assertTrue("setelah diputar harus tegak", sesudah.rect.height > sesudah.rect.width)
        // Berputar di tempat, bukan berpindah.
        assertEquals(sebelum.rect.centerX, sesudah.rect.centerX, 0.01f)
        assertEquals(sebelum.rect.centerY, sesudah.rect.centerY, 0.01f)

        compose.onNodeWithText("Putar kiri").performClick()
        compose.waitForIdle()
        assertEquals(0, viewModel.state.value.selectedPhoto!!.item.rotationDegrees)
    }

    @Test
    fun `membuka berkas word memberi penjelasan format, bukan menuduh berkas rusak`() {
        val viewModel = launch()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "surat.docx")
        file.writeText("bukan gambar")

        viewModel.openDocument(Uri.fromFile(file))
        compose.waitForIdle()

        val outcome = viewModel.state.value.outcome
        assertTrue("harus gagal dengan sebab bertipe", outcome is PrintOutcome.Failed)
        assertEquals(
            PrinterErrorKind.UNSUPPORTED_FORMAT,
            (outcome as PrintOutcome.Failed).kind
        )
        // Tidak ada foto yang terlanjur masuk ke lembar.
        assertEquals(0, viewModel.state.value.photos.size)

        val advice = adviceFor(PrinterErrorKind.UNSUPPORTED_FORMAT)
        compose.onNodeWithText(advice.title).assertIsDisplayed()
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

        // Keduanya sekarang di balik "Setelan lanjutan". Ujinya membuka dulu
        // alih-alih melonggarkan pemeriksaan: yang dijaga tetap bahwa chip itu
        // benar-benar bisa disentuh, bukan sekadar bahwa nilainya bisa diubah
        // lewat jalan lain.
        compose.onNodeWithText("Setelan lanjutan").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Draft").performClick()
        compose.onNodeWithText("720 dpi").performClick()
        compose.waitForIdle()

        assertEquals(Quality.DRAFT, viewModel.state.value.settings.quality)
        assertEquals(Dpi.DPI720, viewModel.state.value.settings.dpi)
    }

    @Test
    fun `preset mengubah empat setelan sekaligus dengan satu ketukan`() {
        val viewModel = launch()

        compose.onNodeWithText("Kualitas foto").performClick()
        compose.waitForIdle()

        val s = viewModel.state.value.settings
        assertEquals(Quality.HIGH, s.quality)
        assertEquals(Dpi.DPI600, s.dpi)
        assertEquals(PrintPreset.PHOTO, presetOf(s))
    }

    @Test
    fun `setelan lanjutan tertutup sampai dibuka`() {
        launch()

        // Inilah inti perubahannya: chip teknis tidak lagi menuntut perhatian
        // setiap kali layar dibuka.
        compose.onAllNodesWithText("720 dpi").assertCountEquals(0)

        compose.onNodeWithText("Setelan lanjutan").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("720 dpi").assertIsDisplayed()
    }
}
