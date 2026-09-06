package com.escpr.usbprint

import com.escpr.usbprint.ui.EscpRSupport
import com.escpr.usbprint.ui.OutcomeAction
import com.escpr.usbprint.ui.StepAction
import com.escpr.usbprint.ui.StepState
import com.escpr.usbprint.ui.adviceFor
import com.escpr.usbprint.ui.computeConnectionStatus
import com.escpr.usbprint.usb.PrinterErrorKind
import com.escpr.usbprint.usb.PrinterException
import com.escpr.usbprint.usb.classifyFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatusTest {

    private fun status(
        usbHost: Boolean = true,
        devices: Int = 1,
        permission: Boolean = true,
        denied: Boolean = false,
        support: EscpRSupport = EscpRSupport.UNKNOWN,
    ) = computeConnectionStatus(
        hasUsbHost = usbHost,
        deviceCount = devices,
        deviceLabel = if (devices > 0) "EPSON L3110 Series" else null,
        hasPermission = permission,
        permissionDenied = denied,
        support = support,
    )

    @Test
    fun `semua terpenuhi berarti siap`() {
        val s = status()
        assertTrue(s.ready)
        assertTrue(s.steps.all { it.state == StepState.OK })
        assertEquals("EPSON L3110 Series", s.headline)
    }

    @Test
    fun `hp tanpa usb host membuat langkah berikutnya menunggu, bukan menyalahkan pengguna`() {
        val s = status(usbHost = false, devices = 0, permission = false)
        assertFalse(s.ready)
        assertEquals(StepState.IMPOSSIBLE, s.steps[0].state)
        // Tidak ada tombol yang ditawarkan: tidak ada yang bisa dilakukan pengguna.
        assertNull(s.steps[0].action)
        assertTrue(s.steps.drop(1).all { it.state == StepState.WAITING })
        assertTrue(s.headline.contains("tidak mendukung"))
    }

    @Test
    fun `tanpa printer, hanya langkah deteksi yang menawarkan tombol`() {
        val s = status(devices = 0, permission = false)
        assertFalse(s.ready)
        assertEquals(StepState.OK, s.steps[0].state)
        assertEquals(StepState.ACTION_NEEDED, s.steps[1].state)
        assertEquals(StepAction.REFRESH, s.steps[1].action)
        // Izin belum relevan sebelum ada perangkat.
        assertEquals(StepState.WAITING, s.steps[2].state)
        assertNull(s.steps[2].action)
        assertEquals(1, s.steps.count { it.action != null })
    }

    @Test
    fun `izin belum diminta dan izin ditolak memberi kalimat berbeda`() {
        val belum = status(permission = false, denied = false).steps[2]
        val ditolak = status(permission = false, denied = true).steps[2]

        assertEquals(StepAction.REQUEST_PERMISSION, belum.action)
        assertEquals(StepAction.REQUEST_PERMISSION, ditolak.action)
        assertNotNull(belum.detail)
        assertNotNull(ditolak.detail)
        assertTrue("penolakan harus disebut eksplisit", ditolak.detail!!.contains("ditolak"))
        assertFalse(belum.detail!!.contains("ditolak"))
    }

    @Test
    fun `dukungan escpr yang belum diperiksa menawarkan tombol cek`() {
        val belum = status(support = EscpRSupport.UNKNOWN)
        assertEquals(StepAction.CHECK_SUPPORT, belum.steps[3].action)

        val sudah = status(support = EscpRSupport.SUPPORTED)
        assertNull(sudah.steps[3].action)
        assertTrue(sudah.steps[3].detail!!.contains("ESC/P-R"))
    }

    @Test
    fun `printer yang tidak menyebut escpr tetap dianggap siap dicoba`() {
        val s = status(support = EscpRSupport.NOT_LISTED)
        assertTrue("tidak boleh memblokir cetak", s.ready)
        assertEquals(StepState.OK, s.steps[3].state)
    }

    @Test
    fun `paling banyak satu langkah menawarkan tindakan sekaligus`() {
        val kombinasi = listOf(
            status(usbHost = false, devices = 0, permission = false),
            status(devices = 0, permission = false),
            status(permission = false),
            status(),
        )
        for (s in kombinasi) {
            assertTrue(
                "hanya satu tombol yang boleh muncul agar pengguna tidak ragu",
                s.steps.count { it.action != null } <= 1
            )
        }
    }
}

class FailureAdviceTest {

    @Test
    fun `transfer gagal saat printer sudah hilang dianggap terputus`() {
        val error = PrinterException(PrinterErrorKind.TRANSFER_FAILED, "gagal")
        assertEquals(
            PrinterErrorKind.DISCONNECTED,
            classifyFailure(error, deviceStillAttached = false)
        )
        assertEquals(
            PrinterErrorKind.TRANSFER_FAILED,
            classifyFailure(error, deviceStillAttached = true)
        )
    }

    @Test
    fun `sebab yang sudah bertipe tidak ditimpa`() {
        val error = PrinterException(PrinterErrorKind.BUSY, "dipakai")
        assertEquals(PrinterErrorKind.BUSY, classifyFailure(error, deviceStillAttached = true))
    }

    @Test
    fun `kehabisan memori dikenali`() {
        assertEquals(
            PrinterErrorKind.OUT_OF_MEMORY,
            classifyFailure(OutOfMemoryError("heap"), deviceStillAttached = true)
        )
    }

    @Test
    fun `pengecualian tak dikenal saat perangkat masih ada tetap unknown`() {
        assertEquals(
            PrinterErrorKind.UNKNOWN,
            classifyFailure(IllegalStateException("aneh"), deviceStillAttached = true)
        )
    }

    @Test
    fun `setiap sebab punya kalimat dan tindakan yang masuk akal`() {
        for (kind in PrinterErrorKind.entries) {
            val advice = adviceFor(kind)
            assertTrue("judul kosong untuk $kind", advice.title.isNotBlank())
            assertTrue("saran kosong untuk $kind", advice.hint.isNotBlank())
            // Tidak boleh ada istilah teknis di judul yang dibaca orang awam.
            for (istilah in listOf("Exception", "null", "USB_", "bulk", "endpoint")) {
                assertFalse(
                    "judul $kind mengandung istilah teknis '$istilah'",
                    advice.title.contains(istilah)
                )
            }
        }
    }

    @Test
    fun `hanya sebab yang benar-benar buntu yang tidak punya tombol`() {
        val tanpaTombol = PrinterErrorKind.entries.filter { adviceFor(it).action == null }
        assertEquals(
            setOf(PrinterErrorKind.NO_USB_HOST, PrinterErrorKind.NO_ENDPOINT),
            tanpaTombol.toSet()
        )
    }

    @Test
    fun `izin ditolak mengarahkan ke permintaan izin, bukan coba lagi`() {
        assertEquals(
            OutcomeAction.REQUEST_PERMISSION,
            adviceFor(PrinterErrorKind.PERMISSION_DENIED).action
        )
        assertEquals(
            OutcomeAction.REFRESH,
            adviceFor(PrinterErrorKind.DISCONNECTED).action
        )
        assertEquals(
            OutcomeAction.PICK_FILE,
            adviceFor(PrinterErrorKind.DOCUMENT_UNREADABLE).action
        )
    }
}
