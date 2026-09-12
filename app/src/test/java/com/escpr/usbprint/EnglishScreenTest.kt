package com.escpr.usbprint

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.escpr.usbprint.escpr.MediaType
import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.ui.PrintScreen
import com.escpr.usbprint.ui.PrintViewModel
import com.escpr.usbprint.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Layar yang sama, dalam bahasa Inggris.
 *
 * Bukan salinan PrintScreenTest. Yang diuji di sini hanya hal yang tidak bisa
 * dilihat dari sisi Indonesia: bahwa layar benar-benar berganti bahasa, bahwa
 * baris ringkasan tetap terbentuk dengan kalimat bahasa Inggris, dan bahwa
 * menyentuh chip tetap mengubah setelan -- karena kalimat yang lebih panjang
 * bisa membuat chip melipat dan berpindah tempat.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w420dp-h2600dp")
class EnglishScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun launch(): PrintViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_USB_HOST, true)
        val viewModel = PrintViewModel(app, Dispatchers.Unconfined, Dispatchers.Unconfined)
        compose.setContent { AppTheme { PrintScreen(viewModel) } }
        return viewModel
    }

    @Test
    fun `layar utama tampil dalam bahasa inggris`() {
        launch()
        compose.onNodeWithText("Print settings").assertIsDisplayed()
        compose.onNodeWithText("Advanced settings").assertIsDisplayed()
        compose.onNodeWithText("Pick file").assertIsDisplayed()
        compose.onNodeWithText("Print").assertIsDisplayed()
    }

    @Test
    fun `daftar periksa sambungan menuntun dalam bahasa inggris`() {
        launch()
        compose.onNodeWithText("Phone supports USB OTG").assertIsDisplayed()
        compose.onNodeWithText("Printer detected").assertIsDisplayed()
        compose.onNodeWithText("Plug the printer", substring = true).assertIsDisplayed()
    }

    @Test
    fun `baris ringkasan tetap terbentuk dengan kalimat bahasa inggris`() {
        launch()
        compose.onNodeWithText("A4  ·  300 dpi  ·  plain paper  ·  margin 3 mm")
            .assertIsDisplayed()
    }

    @Test
    fun `chip masih bisa disentuh walau labelnya lebih panjang`() {
        val viewModel = launch()
        compose.onNodeWithText("Advanced settings").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Postcard").performClick()
        compose.waitForIdle()
        assertEquals(PaperSize.POSTCARD, viewModel.state.value.settings.paper)

        compose.onNodeWithText("Photo paper").performClick()
        compose.waitForIdle()
        assertEquals(MediaType.PHOTO, viewModel.state.value.settings.mediaType)
    }
}
