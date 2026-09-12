package com.escpr.usbprint

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.escpr.usbprint.ui.PrintScreen
import com.escpr.usbprint.ui.PrintViewModel
import com.escpr.usbprint.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Layar utama dalam bahasa Inggris, sebagai gambar.
 *
 * Uji tata letak membuktikan tombolnya masih bisa disentuh; ia tidak
 * membuktikan tampilannya masih rapi. Kalimat bahasa Inggris di sini rata-rata
 * lebih panjang -- "Advanced settings" lawan "Setelan lanjutan", "Plain paper"
 * lawan "Kertas biasa" -- dan yang paling mungkin rusak karenanya adalah baris
 * ringkasan dan barisan chip. Itu hanya bisa dilihat.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w411dp-h2200dp")
class EnglishScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outputDir = File("build/screenshots").apply { mkdirs() }

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

    @Test
    fun `tangkap layar utama berbahasa inggris`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrintViewModel(app, Dispatchers.Unconfined, Dispatchers.Unconfined)
        compose.setContent { AppTheme { PrintScreen(viewModel) } }
        capture("20-english-utama")

        // Setelan lanjutan dibuka: di sinilah chip paling banyak dan paling
        // mungkin melipat tidak rapi, termasuk pemilih bahasanya sendiri.
        compose.onNodeWithText("Advanced settings").performClick()
        capture("21-english-lanjutan")
    }
}
