package com.escpr.usbprint

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.escpr.usbprint.escpr.PrintSettings
import com.escpr.usbprint.escpr.Quality
import com.escpr.usbprint.ui.PrintScreen
import com.escpr.usbprint.ui.PrintViewModel
import com.escpr.usbprint.ui.SettingsContent
import com.escpr.usbprint.ui.theme.AppTheme
import com.escpr.usbprint.util.AppLanguage
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Layar setelan di balik ikon gear.
 *
 * Yang dijaga di sini bukan sekadar "layarnya muncul", melainkan pembagiannya:
 * hal yang berubah antar-pekerjaan tetap di halaman utama, hal yang tidak
 * pindah ke sini. Pembagian itu keputusan produk, dan keputusan produk yang
 * tidak diuji akan bergeser diam-diam pada perubahan berikutnya.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "in-w420dp-h2600dp")
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun viewModel(usbHost: Boolean = true): PrintViewModel {
        shadowOf(app().packageManager)
            .setSystemFeature(PackageManager.FEATURE_USB_HOST, usbHost)
        return PrintViewModel(app(), Dispatchers.Unconfined, Dispatchers.Unconfined)
    }

    private fun bukaSetelan(): PrintViewModel {
        val vm = viewModel()
        compose.setContent {
            AppTheme {
                val current = vm.state.collectAsState().value
                SettingsContent(current, vm) {}
            }
        }
        return vm
    }

    @Test
    fun `ikon gear membuka layar setelan`() {
        val vm = viewModel()
        compose.setContent { AppTheme { PrintScreen(vm) } }

        compose.onNodeWithContentDescription("Setelan").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Bahasa · Language").assertIsDisplayed()
        compose.onNodeWithText("Tentang").assertIsDisplayed()
    }

    @Test
    fun `yang berubah antar-pekerjaan tetap di halaman utama`() {
        val vm = viewModel()
        compose.setContent { AppTheme { PrintScreen(vm) } }

        // Preset, warna, dan salinan dipilih tiap kali mencetak.
        compose.onNodeWithText("Cetak biasa").assertIsDisplayed()
        compose.onNodeWithText("Berwarna").assertIsDisplayed()
        compose.onNodeWithText("Salinan").assertIsDisplayed()

        // Ukuran kertas dan margin ikut tinggal, walau terlipat: keduanya
        // mengubah gambar pratinjau, dan pratinjaunya hanya ada di sini.
        compose.onNodeWithText("Setelan lanjutan").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Margin").assertIsDisplayed()
        compose.onNodeWithText("A5").assertIsDisplayed()
    }

    @Test
    fun `yang tidak berubah antar-pekerjaan sudah tidak di halaman utama`() {
        val vm = viewModel()
        compose.setContent { AppTheme { PrintScreen(vm) } }
        compose.onNodeWithText("Setelan lanjutan").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("Bahasa · Language").assertCountEquals(0)
        compose.onAllNodesWithText("Kembalikan").assertCountEquals(0)
        compose.onAllNodesWithText("Perawatan printer").assertCountEquals(0)
    }

    @Test
    fun `mengganti bahasa tercatat di keadaan`() {
        val vm = bukaSetelan()
        assertEquals(AppLanguage.SYSTEM, vm.state.value.language)

        compose.onNodeWithText("English").performClick()
        compose.waitForIdle()

        assertEquals(AppLanguage.ENGLISH, vm.state.value.language)
    }

    @Test
    fun `kembalikan ke bawaan benar-benar mengembalikan setelan`() {
        val vm = bukaSetelan()
        vm.updateSettings { it.copy(quality = Quality.DRAFT, marginMm = 15f) }
        compose.waitForIdle()

        compose.onNodeWithText("Kembalikan").performClick()
        compose.waitForIdle()

        assertEquals(PrintSettings().quality, vm.state.value.settings.quality)
        assertEquals(PrintSettings().marginMm, vm.state.value.settings.marginMm, 0.01f)
    }

    @Test
    fun `versi aplikasi terbaca di bagian tentang`() {
        bukaSetelan()
        compose.onNodeWithText("Printigo v" + BuildConfig.VERSION_NAME).assertIsDisplayed()
        // Device ID belum pernah dibaca, dan itu dikatakan -- bukan dibiarkan kosong.
        compose.onNodeWithText("Device ID belum pernah dibaca.").assertIsDisplayed()
    }
}
