package com.escpr.usbprint

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.escpr.usbprint.escpr.ColorMode
import com.escpr.usbprint.escpr.PaperSize
import androidx.compose.runtime.collectAsState
import com.escpr.usbprint.ui.LayoutEditorContent
import com.escpr.usbprint.ui.PrintScreen
import com.escpr.usbprint.ui.SettingsContent
import com.escpr.usbprint.ui.PrintViewModel
import com.escpr.usbprint.ui.theme.AppTheme
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Menangkap tampilan layar sungguhan dari komposisi Compose di JVM.
 *
 * Berguna untuk dua hal: memeriksa hasil rancangan tanpa perangkat, dan
 * membuktikan bahwa pratinjau halaman benar-benar menggambar isi dokumen,
 * bukan sekadar tidak gagal.
 *
 * Berkas PNG-nya ditulis ke app/build/screenshots/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Dikunci ke bahasa Indonesia supaya tangkapan layarnya bisa dibandingkan
// dengan yang sebelumnya. Versi bahasa Inggrisnya ditangkap terpisah di
// bawah, karena panjang kalimat yang berbeda adalah justru yang perlu
// dilihat setelah aplikasi punya dua bahasa.
@Config(sdk = [34], qualifiers = "in-w411dp-h2200dp")
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File("build/screenshots").apply { mkdirs() }

    /**
     * captureToImage() memakai PixelCopy yang menuntut window sungguhan, dan itu
     * tidak tersedia di Robolectric. Menggambar decorView ke kanvas sendiri
     * memberi piksel yang sama tanpa perlu perangkat.
     */
    private fun capture(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        assertTrue("view belum punya ukuran", view.width > 0 && view.height > 0)

        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        val file = File(outputDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name.png kosong", file.length() > 1000)
        println("SCREENSHOT ${file.absolutePath} ${bitmap.width}x${bitmap.height}")
    }

    /** Beberapa foto contoh dengan warna dan rasio berbeda. */
    private fun samplePhotos(app: Application, count: Int): List<Uri> =
        (0 until count).map { index ->
            val landscape = index % 2 == 0
            val width = if (landscape) 1200 else 800
            val height = if (landscape) 800 else 1200
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val hue = floatArrayOf((index * 67f) % 360f, 0.55f, 0.85f)
            canvas.drawColor(Color.HSVToColor(hue))
            val paint = Paint().apply {
                color = Color.rgb(60, 70, 90)
                style = Paint.Style.STROKE
                strokeWidth = 18f
            }
            canvas.drawRect(9f, 9f, width - 9f, height - 9f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(width * 0.7f, height * 0.3f, width * 0.12f, paint)

            val file = File(app.cacheDir, "foto-$index.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Uri.fromFile(file)
        }

    @Test
    fun `tangkap layar setelan di balik gear`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrintViewModel(app, Dispatchers.Unconfined, Dispatchers.Unconfined)
        compose.setContent {
            AppTheme {
                val current = viewModel.state.collectAsState().value
                SettingsContent(current, viewModel) {}
            }
        }
        capture("22-setelan")
    }

    @Test
    @Config(qualifiers = "in-w411dp-h891dp")
    fun `tangkap layar lembar berisi banyak foto`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrintViewModel(app, Dispatchers.Unconfined, Dispatchers.Unconfined)
        compose.setContent {
            AppTheme {
                val current = viewModel.state.collectAsState().value
                LayoutEditorContent(current, viewModel)
            }
        }

        viewModel.addPhotos(samplePhotos(app, 6))
        compose.waitUntil(timeoutMillis = 10_000) {
            viewModel.state.value.photos.size == 6
        }
        compose.waitForIdle()
        capture("10-lembar-enam-foto")

        viewModel.arrangeGrid(2)
        compose.waitForIdle()
        capture("11-lembar-dua-kolom")

        // Dua foto mendatar diputar tegak: kisi menyusunnya sebagai foto tegak.
        viewModel.state.value.photos.take(2).forEach { photo ->
            viewModel.selectPhotoAt(photo.item.rect.centerX, photo.item.rect.centerY)
            viewModel.rotateSelectedPhoto(1)
        }
        viewModel.arrangeGrid(3)
        compose.waitForIdle()
        capture("13-lembar-diputar")

        // Satu foto digeser keluar area cetak.
        val target = viewModel.state.value.photos.first()
        viewModel.selectPhotoAt(target.item.rect.centerX, target.item.rect.centerY)
        viewModel.nudgePlacement(-60f, -60f, 1.6f)
        compose.waitForIdle()
        capture("12-lembar-terpotong")
    }

    /** Dokumen contoh: gambar lanskap sederhana dengan bingkai tegas. */
    private fun sampleDocument(app: Application): Uri {
        val bitmap = Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = true }

        canvas.drawColor(Color.rgb(232, 240, 250))
        paint.color = Color.rgb(120, 190, 255)
        canvas.drawRect(0f, 0f, 1400f, 430f, paint)          // langit
        paint.color = Color.rgb(90, 170, 110)
        canvas.drawRect(0f, 430f, 1400f, 900f, paint)        // tanah
        paint.color = Color.rgb(255, 214, 90)
        canvas.drawCircle(1120f, 160f, 90f, paint)           // matahari
        paint.color = Color.rgb(70, 90, 120)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 14f
        canvas.drawRect(7f, 7f, 1393f, 893f, paint)          // bingkai

        val file = File(app.cacheDir, "contoh.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    @Test
    fun `tangkap layar daftar periksa sambungan dan kartu kegagalan`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // HP yang mendukung OTG tapi belum ada printer: keadaan yang paling
        // sering ditemui pengguna saat pertama kali membuka aplikasi.
        shadowOf(app.packageManager)
            .setSystemFeature(PackageManager.FEATURE_USB_HOST, true)

        val viewModel = PrintViewModel(app, Dispatchers.Unconfined, Dispatchers.Unconfined)
        compose.setContent { AppTheme { PrintScreen(viewModel) } }
        compose.waitForIdle()
        capture("5-belum-tersambung")

        // Kartu kegagalan tidak ditangkap di sini. Memicunya lewat jalur
        // sungguhan berarti state berubah dari utas latar, dan harness ini
        // memakai Activity sungguhan yang menolak sentuhan view lintas utas.
        // Perilakunya sudah diuji di PrintScreenTest, yang memakai harness
        // tanpa Activity, jadi yang hilang di sini hanya gambarnya.
    }

    @Test
    fun `tangkap layar keadaan kosong dan dengan dokumen`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrintViewModel(app, Dispatchers.Unconfined, Dispatchers.Unconfined)
        compose.setContent { AppTheme { PrintScreen(viewModel) } }

        compose.waitForIdle()
        capture("1-kosong")

        viewModel.openDocument(sampleDocument(app))
        // Dokumen dibaca dan dirender di utas lain, jadi tunggu sampai siap.
        compose.waitUntil(timeoutMillis = 10_000) {
            viewModel.state.value.previewItems.isNotEmpty()
        }
        compose.waitForIdle()
        capture("2-dokumen-a4-margin3")

        // Margin lebar: bidang cetak menyempit dan isinya ikut mengecil.
        viewModel.updateSettings { it.copy(marginMm = 18f) }
        compose.waitForIdle()
        capture("3-margin18")

        // Kertas 4R yang jauh lebih persegi, sekalian mode hitam putih.
        viewModel.updateSettings {
            it.copy(paper = PaperSize.PHOTO_4R, marginMm = 3f, colorMode = ColorMode.MONO)
        }
        compose.waitForIdle()
        capture("4-kertas-4R-mono")

        // Diperbesar dan digeser dengan tangan sampai keluar area cetak:
        // bagian yang terpotong ditandai merah dan peringatannya muncul.
        viewModel.updateSettings {
            it.copy(paper = PaperSize.A4, colorMode = ColorMode.COLOR)
        }
        viewModel.nudgePlacement(panXmm = 14f, panYmm = -10f, zoom = 1.45f)
        compose.waitForIdle()
        capture("7-atur-tangan-terpotong")
    }

    // Editor mengisi satu layar penuh, jadi ditangkap pada ukuran layar HP
    // sungguhan. Viewport uji yang sengaja dibuat tinggi untuk halaman utama
    // akan membuat kertasnya tampak kecil di tengah ruang kosong.
    @Test
    @Config(qualifiers = "in-w411dp-h891dp")
    fun `tangkap layar editor tata letak`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrintViewModel(app, Dispatchers.Unconfined, Dispatchers.Unconfined)

        // Isi editor dirender langsung, tanpa pembungkus Dialog: jendela dialog
        // punya decorView sendiri yang tidak ikut tergambar dari activity.
        // setContent hanya boleh sekali per uji, jadi state dibaca sebagai flow
        // supaya perubahan berikutnya ikut tergambar.
        compose.setContent {
            AppTheme {
                val current = viewModel.state.collectAsState().value
                LayoutEditorContent(current, viewModel)
            }
        }

        viewModel.openDocument(sampleDocument(app))
        compose.waitUntil(timeoutMillis = 10_000) {
            viewModel.state.value.previewItems.isNotEmpty()
        }
        compose.waitForIdle()
        capture("8-editor-tata-letak")

        viewModel.nudgePlacement(panXmm = 10f, panYmm = -8f, zoom = 1.4f)
        compose.waitForIdle()
        capture("9-editor-terpotong")
    }
}
