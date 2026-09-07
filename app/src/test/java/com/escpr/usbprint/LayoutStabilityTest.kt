package com.escpr.usbprint

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.escpr.usbprint.ui.LayoutEditorContent
import com.escpr.usbprint.ui.PREVIEW_TEST_TAG
import com.escpr.usbprint.ui.PrintViewModel
import com.escpr.usbprint.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Ukuran kertas di editor tidak boleh berubah karena isi kontrol di bawahnya.
 *
 * Kertas mengambil sisa ruang setelah bilah kontrol. Kalau tinggi kontrol ikut
 * berubah -- peringatan terpotong muncul, atau baris margin melipat jadi dua --
 * kertas ikut menyusut. Akibatnya dua hal sekaligus: gambar melompat saat
 * digeser, dan skala kertas berubah di tengah gestur sehingga detektor gestur
 * direstart dan geserannya putus.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class LayoutStabilityTest {

    @get:Rule
    val compose = createComposeRule()

    private fun editorWithPhoto(): PrintViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = PrintViewModel(app, Dispatchers.Unconfined, Dispatchers.Unconfined)

        compose.setContent {
            AppTheme {
                val current = viewModel.state.collectAsState().value
                LayoutEditorContent(current, viewModel)
            }
        }

        val bitmap = Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(120, 190, 255))
        val file = File(app.cacheDir, "stabil.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        viewModel.openDocument(Uri.fromFile(file))
        compose.waitUntil(timeoutMillis = 10_000) {
            viewModel.state.value.previewItems.isNotEmpty()
        }
        compose.waitForIdle()
        return viewModel
    }

    private fun previewHeight(): Int =
        compose.onNodeWithTag(PREVIEW_TEST_TAG).fetchSemanticsNode().size.height

    @Test
    fun `ukuran kertas tetap saat peringatan terpotong muncul`() {
        val viewModel = editorWithPhoto()

        val sebelum = previewHeight()
        assertTrue("wadah kertas harus punya tinggi", sebelum > 0)
        assertEquals(false, viewModel.state.value.sheetLayout.hasOverflow)

        // Dorong foto sampai keluar area cetak: peringatan muncul di bawah.
        viewModel.nudgePlacement(-70f, -70f, 1.6f)
        compose.waitForIdle()
        assertEquals(true, viewModel.state.value.sheetLayout.hasOverflow)

        val sesudah = previewHeight()
        assertEquals(
            "kertas menyusut dari $sebelum jadi $sesudah ketika peringatan muncul",
            sebelum, sesudah
        )
    }

    @Test
    fun `ukuran kertas tetap saat kertas dan margin diganti`() {
        val viewModel = editorWithPhoto()
        val sebelum = previewHeight()

        // Margin lebar memperpanjang angka pada baris margin, dan pada mode
        // dokumen baris itulah yang paling mudah melipat jadi dua baris.
        viewModel.updateSettings { it.copy(marginMm = 18.5f) }
        compose.waitForIdle()

        assertEquals(
            "kertas berubah tinggi hanya karena teks margin memanjang",
            sebelum, previewHeight()
        )
    }

    @Test
    fun `ukuran kertas tetap sepanjang rangkaian geseran`() {
        val viewModel = editorWithPhoto()
        val tinggi = mutableSetOf<Int>()

        repeat(6) { step ->
            viewModel.nudgePlacement(-18f, -12f, if (step % 2 == 0) 1.15f else 1f)
            compose.waitForIdle()
            tinggi += previewHeight()
        }

        assertEquals(
            "tinggi kertas berubah-ubah selama digeser: $tinggi",
            1, tinggi.size
        )
    }
}
