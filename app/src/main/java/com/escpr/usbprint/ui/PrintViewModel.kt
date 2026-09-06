package com.escpr.usbprint.ui

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.escpr.usbprint.escpr.EscpRJob
import com.escpr.usbprint.escpr.PrintSettings
import com.escpr.usbprint.print.PrintTask
import com.escpr.usbprint.render.ImagePageSource
import com.escpr.usbprint.render.PageSource
import com.escpr.usbprint.render.PdfPageSource
import com.escpr.usbprint.render.PreviewRenderer
import com.escpr.usbprint.usb.PrinterErrorKind
import com.escpr.usbprint.usb.UsbPrinter
import com.escpr.usbprint.usb.classifyFailure
import com.escpr.usbprint.util.StreamSink
import com.escpr.usbprint.util.copyToCache
import com.escpr.usbprint.util.displayName
import com.escpr.usbprint.util.formatBytes
import com.escpr.usbprint.util.mimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ACTION_USB_PERMISSION = "com.escpr.usbprint.USB_PERMISSION"

data class Document(
    val file: File,
    val name: String,
    val isPdf: Boolean,
    val pageCount: Int
)

data class UiState(
    val devices: List<UsbDevice> = emptyList(),
    val selectedDevice: UsbDevice? = null,
    val hasPermission: Boolean = false,
    val deviceId: String? = null,
    val document: Document? = null,
    val settings: PrintSettings = PrintSettings(),
    val busy: Boolean = false,
    val progress: Float = 0f,
    val log: List<String> = emptyList(),
    /** Isi halaman pada rasio aslinya; kertas dan margin dihitung saat menggambar. */
    val previewImage: ImageBitmap? = null,
    val previewPage: Int = 0,
    val previewLoading: Boolean = false,
    /** Apakah HP-nya sendiri sanggup menjadi host USB. */
    val hasUsbHost: Boolean = true,
    /** Pengguna sudah pernah menolak izin untuk perangkat ini. */
    val permissionDenied: Boolean = false,
    val escpRSupport: EscpRSupport = EscpRSupport.UNKNOWN,
    val outcome: PrintOutcome = PrintOutcome.None,
) {
    val printableSize: Pair<Int, Int>
        get() = EscpRJob.computeGeometry(settings).let { it.printableWidth to it.printableHeight }

    val connection: ConnectionStatus
        get() = computeConnectionStatus(
            hasUsbHost = hasUsbHost,
            deviceCount = devices.size,
            deviceLabel = selectedDevice?.let { device ->
                device.productName ?: "Perangkat %04X:%04X".format(device.vendorId, device.productId)
            },
            hasPermission = hasPermission,
            permissionDenied = permissionDenied,
            support = escpRSupport,
        )

    val canPrint: Boolean
        get() = !busy && document != null && connection.ready
}

class PrintViewModel(app: Application) : AndroidViewModel(app) {

    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var printJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        log("Izin USB diberikan.")
                        _state.update { it.copy(permissionDenied = false) }
                        refreshDevices()
                        readDeviceIdentity()
                    } else {
                        log("Izin USB ditolak.")
                        // Ditandai supaya daftar periksa tidak sekadar bilang
                        // "menunggu izin", dan supaya tidak ada permintaan ulang
                        // otomatis yang berubah jadi lingkaran dialog.
                        _state.update {
                            it.copy(hasPermission = false, permissionDenied = true)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    log("Perangkat USB terpasang.")
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log("Perangkat USB dicabut.")
                    refreshDevices()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val usbHost = app.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        _state.update { it.copy(hasUsbHost = usbHost) }
        if (!usbHost) log("HP ini tidak mendukung USB Host (OTG).")

        refreshDevices()
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(receiver) }
        super.onCleared()
    }

    // ------------------------------------------------------------- printer

    fun refreshDevices() {
        val devices = UsbPrinter.findPrinters(usbManager)
        val selected = _state.value.selectedDevice
            ?.let { previous -> devices.firstOrNull { it.deviceName == previous.deviceName } }
            ?: devices.firstOrNull()

        _state.update {
            it.copy(
                devices = devices,
                selectedDevice = selected,
                hasPermission = selected != null && usbManager.hasPermission(selected),
                deviceId = if (selected == null) null else it.deviceId
            )
        }
        if (devices.isEmpty()) log("Tidak ada printer USB terdeteksi.")
    }

    fun selectDevice(device: UsbDevice) {
        _state.update {
            it.copy(
                selectedDevice = device,
                hasPermission = usbManager.hasPermission(device),
                deviceId = null,
                permissionDenied = false,
                escpRSupport = EscpRSupport.UNKNOWN,
            )
        }
    }

    fun requestPermission() {
        val device = _state.value.selectedDevice ?: return
        if (usbManager.hasPermission(device)) {
            _state.update { it.copy(hasPermission = true) }
            readDeviceIdentity()
            return
        }

        val app = getApplication<Application>()
        // Sejak Android 14 intent untuk PendingIntent harus eksplisit, dan sejak
        // Android 12 harus mutable supaya sistem bisa menyisipkan hasilnya.
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        usbManager.requestPermission(
            device,
            PendingIntent.getBroadcast(app, 0, intent, flags)
        )
    }

    /** Membaca IEEE-1284 Device ID untuk memastikan printer memang paham ESC/P-R. */
    fun readDeviceIdentity() {
        val device = _state.value.selectedDevice ?: return
        if (!usbManager.hasPermission(device)) return

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                UsbPrinter.open(usbManager, device).use { it.readDeviceId() }
            }.onSuccess { id ->
                val support = when {
                    id == null -> EscpRSupport.NO_REPLY
                    id.uppercase().contains("ESCPR") -> EscpRSupport.SUPPORTED
                    else -> EscpRSupport.NOT_LISTED
                }
                _state.update { it.copy(deviceId = id, escpRSupport = support) }
                when (support) {
                    EscpRSupport.NO_REPLY ->
                        log("Printer tidak membalas Device ID (tidak selalu masalah).")
                    EscpRSupport.SUPPORTED ->
                        log("Device ID: $id -- mendukung ESC/P-R.")
                    else ->
                        log("Device ID: $id -- ESC/P-R tidak tercantum.")
                }
            }.onFailure { error ->
                _state.update { it.copy(escpRSupport = EscpRSupport.NO_REPLY) }
                log("Gagal membaca Device ID: ${error.message}")
            }
        }
    }

    // ------------------------------------------------------------ dokumen

    fun openDocument(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val name = displayName(app, uri)
                val mime = mimeType(app, uri)
                val file = copyToCache(app, uri, name)
                val isPdf = mime.contains("pdf") || name.endsWith(".pdf", ignoreCase = true)

                val pages = if (isPdf) {
                    PdfPageSource(file).use { it.pageCount }
                } else {
                    1
                }
                Document(file, name, isPdf, pages)
            }.onSuccess { document ->
                _state.update {
                    it.copy(document = document, previewPage = 0, previewImage = null)
                }
                log("Dipilih: ${document.name} (${document.pageCount} halaman)")
                loadPreview(0)
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        outcome = PrintOutcome.Failed(
                            PrinterErrorKind.DOCUMENT_UNREADABLE,
                            error.message ?: error.toString()
                        )
                    )
                }
                log("Gagal membuka berkas: ${error.message}")
            }
        }
    }

    fun showPage(index: Int) {
        val document = _state.value.document ?: return
        val page = index.coerceIn(0, document.pageCount - 1)
        if (page == _state.value.previewPage && _state.value.previewImage != null) return
        _state.update { it.copy(previewPage = page) }
        loadPreview(page)
    }

    /**
     * Merender isi halaman sekali pada rasio aslinya. Kertas dan margin tidak
     * ikut dihitung di sini supaya menggeser slider tidak memicu render ulang.
     */
    private fun loadPreview(page: Int) {
        val document = _state.value.document ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(previewLoading = true) }
            runCatching {
                PreviewRenderer.render(document.file, document.isPdf, page).asImageBitmap()
            }.onSuccess { image ->
                _state.update {
                    // Abaikan hasil yang keburu basi karena pengguna sudah pindah halaman.
                    if (it.previewPage == page) it.copy(previewImage = image, previewLoading = false)
                    else it.copy(previewLoading = false)
                }
            }.onFailure { error ->
                _state.update { it.copy(previewLoading = false) }
                log("Pratinjau gagal dibuat: ${error.message}")
            }
        }
    }

    fun updateSettings(transform: (PrintSettings) -> PrintSettings) {
        _state.update { it.copy(settings = transform(it.settings)) }
    }

    // -------------------------------------------------------------- cetak

    fun print() {
        val current = _state.value
        val document = current.document ?: return
        val device = current.selectedDevice ?: return

        val sheets = document.pageCount * current.settings.copies.coerceAtLeast(1)

        printJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(busy = true, progress = 0f, outcome = PrintOutcome.None)
            }
            val started = System.currentTimeMillis()
            try {
                UsbPrinter.open(usbManager, device).use { printer ->
                    val sink = printer.sink()
                    pageSource(document).use { source ->
                        val (width, height) = current.printableSize
                        log("Mencetak pada $width x $height piksel...")
                        PrintTask.run(sink, source, current.settings) { progress ->
                            _state.update { it.copy(progress = progress.fraction) }
                        }
                    }
                    sink.close()

                    val status = printer.readStatus(500)
                    if (status.isNotEmpty()) {
                        log("Status printer: ${String(status, Charsets.US_ASCII).trim()}")
                    }
                }
                val seconds = (System.currentTimeMillis() - started) / 1000.0
                log("Selesai dalam %.1f detik.".format(seconds))
                _state.update {
                    it.copy(progress = 1f, outcome = PrintOutcome.Success(sheets, seconds))
                }
            } catch (cancellation: CancellationException) {
                _state.update { it.copy(outcome = PrintOutcome.Cancelled) }
                throw cancellation
            } catch (error: Throwable) {
                // Sebabnya ditentukan dari jenis pengecualian dan dari apakah
                // printer masih terdaftar saat itu juga -- bukan dari isi pesan.
                val stillAttached = usbManager.deviceList.values
                    .any { it.deviceName == device.deviceName }
                val kind = classifyFailure(error, stillAttached)
                _state.update {
                    it.copy(
                        outcome = PrintOutcome.Failed(
                            kind, error.message ?: error.toString()
                        )
                    )
                }
                log("Cetak gagal [$kind]: ${error.message}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Menutup kartu hasil setelah pengguna membacanya. */
    fun dismissOutcome() {
        _state.update { it.copy(outcome = PrintOutcome.None) }
    }

    fun cancel() {
        printJob?.cancel()
        log("Pembatalan diminta. Printer mungkin masih mengeluarkan halaman yang sedang jalan.")
    }

    /**
     * Menulis job ke file .prn alih-alih ke printer. Berguna untuk memastikan
     * masalahnya ada di kabel/USB atau di data yang dihasilkan.
     */
    fun exportPrn(uri: Uri) {
        val current = _state.value
        val document = current.document ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, progress = 0f) }
            try {
                val app = getApplication<Application>()
                val stream = app.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Tidak bisa menulis ke lokasi itu")

                var written = 0L
                StreamSink(stream).use { sink ->
                    pageSource(document).use { source ->
                        PrintTask.run(sink, source, current.settings) { progress ->
                            _state.update { it.copy(progress = progress.fraction) }
                        }
                    }
                    written = sink.bytesWritten
                }
                log("Tersimpan sebagai .prn, ${formatBytes(written)}.")
                _state.update {
                    it.copy(progress = 1f, outcome = PrintOutcome.Saved(written))
                }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        outcome = PrintOutcome.Failed(
                            PrinterErrorKind.UNKNOWN, error.message ?: error.toString()
                        )
                    )
                }
                log("Gagal menyimpan: ${error.message}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "cetak-$stamp.prn"
    }

    private suspend fun pageSource(document: Document): PageSource =
        withContext(Dispatchers.IO) {
            if (document.isPdf) PdfPageSource(document.file) else ImagePageSource(document.file)
        }

    private fun log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { it.copy(log = (it.log + "$stamp  $message").takeLast(60)) }
    }
}
