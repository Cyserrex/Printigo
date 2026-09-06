package com.escpr.usbprint

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.escpr.usbprint.ui.PrintViewModel
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Menjaga agar ViewModel tetap bisa dibuat lewat jalur yang dipakai aplikasi.
 *
 * Uji lain membuat PrintViewModel dengan memanggil konstruktornya langsung,
 * sehingga tidak pernah menyentuh cara Activity membuatnya. `by viewModels()`
 * memakai factory bawaan yang mencari konstruktor `(Application)` lewat
 * refleksi. Ketika parameter dispatcher ditambahkan, konstruktor itu hilang --
 * parameter default Kotlin hanya menghasilkan versi lengkap dan satu versi
 * sintetis -- dan aplikasi mati saat dibuka dengan "Cannot create an instance
 * of class PrintViewModel". Seluruh uji lain tetap hijau.
 *
 * Uji ini menutup celah itu.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ViewModelFactoryTest {

    @Test
    fun `view model bisa dibuat lewat factory bawaan seperti by viewModels`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val provider = ViewModelProvider(
            ViewModelStore(),
            ViewModelProvider.AndroidViewModelFactory.getInstance(app),
        )

        val viewModel = provider[PrintViewModel::class.java]

        assertNotNull("factory bawaan harus bisa membuat PrintViewModel", viewModel)
        assertNotNull(viewModel.state.value)
    }

    @Test
    fun `konstruktor satu argumen tetap ada untuk refleksi`() {
        // Inilah tanda tangan persis yang dicari AndroidViewModelFactory.
        val constructor = PrintViewModel::class.java.getConstructor(Application::class.java)
        assertNotNull(constructor)
    }
}
