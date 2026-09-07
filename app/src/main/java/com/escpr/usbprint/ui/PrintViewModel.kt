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
import com.escpr.usbprint.layout.ContentPlacement
import com.escpr.usbprint.layout.RectMm
import com.escpr.usbprint.layout.SheetItem
import com.escpr.usbprint.layout.SheetLayout
import com.escpr.usbprint.layout.arrangedInGrid
import com.escpr.usbprint.layout.broughtToFront
import com.escpr.usbprint.layout.computeSheetLayout
import com.escpr.usbprint.layout.movedBy
import com.escpr.usbprint.layout.rotatedBy
import com.escpr.usbprint.layout.scaledBy
import com.escpr.usbprint.layout.clampedTo
import com.escpr.usbprint.layout.computePageLayout
import com.escpr.usbprint.print.PrintTask
import com.escpr.usbprint.render.ImagePageSource
import com.escpr.usbprint.render.PageSource
import com.escpr.usbprint.render.PdfPageSource
import com.escpr.usbprint.render.PreviewRenderer
import com.escpr.usbprint.render.SheetPageSource
import com.escpr.usbprint.render.SheetPhoto
import com.escpr.usbprint.usb.PrinterErrorKind
import com.escpr.usbprint.usb.UsbPrinter
import com.escpr.usbprint.usb.classifyFailure
import com.escpr.usbprint.util.StreamSink
import com.escpr.usbprint.util.copyToCache
import com.escpr.usbprint.util.displayName
import com.escpr.usbprint.util.formatBytes
import com.escpr.usbprint.util.mimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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

/** Satu foto di atas lembar, lengkap dengan pratinjau dan posisinya. */
data class PhotoOnSheet(
    val id: Long,
    val file: File,
    val name: String,
    val preview: ImageBitmap,
    val item: SheetItem,
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
    /** Penempatan isi di atas kertas, diatur pengguna lewat pratinjau. */
    val placement: ContentPlacement = ContentPlacement.Fit,
    /** Editor tata letak layar penuh sedang terbuka. */
    val layoutEditorOpen: Boolean = false,
    /** Foto-foto yang disusun dalam satu lembar. Kosong berarti mode dokumen. */
    val photos: List<PhotoOnSheet> = emptyList(),
    val selectedPhotoId: Long? = null,
) {
    /**
     * Gambar disusun sebagai lembar; PDF tetap lewat jalur dokumen karena
     * halamannya punya ukuran sendiri dan tidak bisa ditumpuk sembarangan.
     */
    val sheetMode: Boolean get() = photos.isNotEmpty()

    val sheetLayout: SheetLayout
        get() = computeSheetLayout(
            paperWidthMm = settings.paper.widthMm,
            paperHeightMm = settings.paper.heightMm,
            marginMm = settings.marginMm,
            items = photos.map { it.item },
        )

    /** Area cetak yang berlaku, dari model mana pun yang sedang dipakai. */
    val printableRect: RectMm
        get() = if (sheetMode) sheetLayout.printable else pageLayout.printable

    /** Isi yang digambar pratinjau, seragam untuk kedua mode. */
    val previewItems: List<PreviewItem>
        get() = if (sheetMode) {
            photos.map { photo ->
                PreviewItem(
                    id = photo.id,
                    rect = photo.item.rect,
                    image = photo.preview,
                    selected = photo.id == selectedPhotoId,
                    rotationDegrees = photo.item.rotationDegrees,
                )
            }
        } else {
            val image = previewImage
            if (image == null) emptyList()
            else listOf(PreviewItem(id = 0L, rect = pageLayout.content, image = image))
        }

    val selectedPhoto: PhotoOnSheet?
        get() = photos.firstOrNull { it.id == selectedPhotoId }

    val hasContent: Boolean get() = sheetMode || document != null

    /** Rasio isi halaman yang sedang dipratinjau; 0 kalau belum ada dokumen. */
    val contentAspect: Float
        get() = previewImage?.let { it.width.toFloat() / it.height.toFloat() } ?: 0f

    val pageLayout
        get() = computePageLayout(
            paperWidthMm = settings.paper.widthMm,
            paperHeightMm = settings.paper.heightMm,
            marginMm = settings.marginMm,
            contentAspect = contentAspect,
            placement = placement,
        )

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
        get() = !busy && hasContent && connection.ready
}

/**
 * @param ioDispatcher tempat kerja berat berjalan: menguraikan gambar, membaca
 *   PDF, dan mengirim data ke printer.
 * @param uiDispatcher tempat perubahan state diterbitkan. Menulis state yang
 *   diamati Compose dari utas latar bisa memicu tata letak ulang di utas yang
 *   salah, jadi penerbitannya selalu di utas utama.
 *
 * Keduanya bisa disuntik supaya pengujian menjalankan semuanya secara langsung
 * tanpa bergantung pada penjadwalan utas.
 *
 * `@JvmOverloads` wajib ada. Factory bawaan ViewModel mencari konstruktor
 * `(Application)` lewat refleksi, sedangkan parameter default Kotlin tidak
 * menghasilkan konstruktor itu -- hanya versi lengkap plus satu versi sintetis.
 * Tanpa anotasi ini, `by viewModels()` gagal dengan "Cannot create an instance
 * of class PrintViewModel" dan aplikasi mati saat dibuka.
 */
class PrintViewModel @JvmOverloads constructor(
    app: Application,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AndroidViewModel(app) {

    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var printJob: Job? = null

    /** Id foto naik terus; dipakai untuk mencocokkan pratinjau dengan posisinya. */
    private var nextPhotoId: Long = 1L

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

        viewModelScope.launch(uiDispatcher) {
            runCatching {
                withContext(ioDispatcher) {
                    UsbPrinter.open(usbManager, device).use { it.readDeviceId() }
                }
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

    /**
     * Membuka satu berkas. Gambar masuk ke lembar; PDF menggantikan lembar.
     */
    fun openDocument(uri: Uri) {
        val app = getApplication<Application>()
        val name = runCatching { displayName(app, uri) }.getOrDefault("")
        val mime = runCatching { mimeType(app, uri) }.getOrDefault("")
        val isPdf = mime.contains("pdf") || name.endsWith(".pdf", ignoreCase = true)
        if (!isPdf) {
            addPhotos(listOf(uri))
            return
        }
        openPdf(uri)
    }

    private fun openPdf(uri: Uri) {
        viewModelScope.launch(uiDispatcher) {
            runCatching {
                withContext(ioDispatcher) {
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
                }
            }.onSuccess { document ->
                _state.update {
                    it.copy(
                        document = document,
                        previewPage = 0,
                        previewImage = null,
                        placement = ContentPlacement.Fit,
                        layoutEditorOpen = false,
                        photos = emptyList(),
                        selectedPhotoId = null,
                    )
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
        viewModelScope.launch(uiDispatcher) {
            _state.update { it.copy(previewLoading = true) }
            runCatching {
                withContext(ioDispatcher) {
                    PreviewRenderer.render(document.file, document.isPdf, page).asImageBitmap()
                }
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

    /**
     * Menerapkan satu gestur dari pratinjau.
     *
     * Geseran datang dalam milimeter kertas, bukan piksel layar, supaya
     * hasilnya sama berapa pun ukuran pratinjau di layar.
     */
    fun nudgePlacement(panXmm: Float, panYmm: Float, zoom: Float) {
        if (_state.value.sheetMode) {
            nudgeSelectedPhoto(panXmm, panYmm, zoom)
            return
        }
        _state.update { state ->
            val paper = state.settings.paper
            val next = state.placement.copy(
                scale = state.placement.scale * zoom,
                offsetXmm = state.placement.offsetXmm + panXmm,
                offsetYmm = state.placement.offsetYmm + panYmm,
                manual = true,
            ).clampedTo(paper.widthMm, paper.heightMm)
            state.copy(placement = next)
        }
    }

    /** Menggeser dan mengubah ukuran foto yang sedang dipilih. */
    private fun nudgeSelectedPhoto(panXmm: Float, panYmm: Float, zoom: Float) {
        _state.update { state ->
            val id = state.selectedPhotoId ?: state.photos.lastOrNull()?.id ?: return@update state
            val paper = state.settings.paper
            state.copy(
                selectedPhotoId = id,
                photos = state.photos.map { photo ->
                    if (photo.id != id) photo
                    else photo.copy(
                        item = photo.item
                            .scaledBy(zoom, paper.widthMm, paper.heightMm)
                            .movedBy(panXmm, panYmm, paper.widthMm, paper.heightMm)
                    )
                }
            )
        }
    }

    /** Mengembalikan isi ke ukuran muat di tengah kertas. */
    fun resetPlacement() {
        if (_state.value.sheetMode) {
            arrangeGrid()
            return
        }
        _state.update { it.copy(placement = ContentPlacement.Fit) }
    }

    // ------------------------------------------------------------- lembar

    /**
     * Menambahkan foto ke lembar, lalu menyusun ulang seluruhnya ke kisi.
     *
     * Penyusunan ulang otomatis dipilih supaya foto baru tidak menumpuk persis
     * di atas yang lama dan tampak seolah tidak masuk. Setelah itu pengguna
     * bebas menggeser sendiri.
     */
    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // Menguraikan gambar berlangsung di IO, tetapi perubahan state
        // diterbitkan di utas utama: menulis state yang diamati Compose dari
        // utas latar bisa memicu tata letak ulang di utas yang salah.
        viewModelScope.launch(uiDispatcher) {
            val app = getApplication<Application>()
            val added = mutableListOf<PhotoOnSheet>()
            val failures = mutableListOf<String>()

            withContext(ioDispatcher) {
                for (uri in uris) {
                    runCatching {
                        val name = displayName(app, uri)
                        val file = copyToCache(app, uri, name + "-" + nextPhotoId)
                        val preview = PreviewRenderer.render(file, isPdf = false, pageIndex = 0)
                        val aspect = preview.width.toFloat() / preview.height.toFloat()
                        PhotoOnSheet(
                            id = nextPhotoId++,
                            file = file,
                            name = name,
                            preview = preview.asImageBitmap(),
                            // Posisi sementara; langsung ditimpa penyusunan di bawah.
                            item = SheetItem(id = nextPhotoId, aspect = aspect, rect = RectMm(0f, 0f, 1f, 1f)),
                        ).let { photo -> photo.copy(item = photo.item.copy(id = photo.id)) }
                    }.onSuccess { added += it }
                        .onFailure { error -> failures += (error.message ?: error.toString()) }
                }
            }

            failures.forEach { message ->
                _state.update {
                    it.copy(
                        outcome = PrintOutcome.Failed(
                            PrinterErrorKind.DOCUMENT_UNREADABLE, message
                        )
                    )
                }
                log("Gagal membuka gambar: " + message)
            }

            if (added.isEmpty()) return@launch
            _state.update { state ->
                val combined = state.photos + added
                val printable = computeSheetLayout(
                    state.settings.paper.widthMm,
                    state.settings.paper.heightMm,
                    state.settings.marginMm,
                    combined.map { it.item },
                ).printable
                val arranged = combined.map { it.item }.arrangedInGrid(printable)
                state.copy(
                    document = null,
                    previewImage = null,
                    photos = combined.mapIndexed { index, photo ->
                        photo.copy(item = arranged[index])
                    },
                    selectedPhotoId = added.last().id,
                    layoutEditorOpen = false,
                )
            }
            log("Ditambahkan " + added.size + " foto, total " + _state.value.photos.size + ".")
        }
    }

    /** Menyusun ulang ke kisi. Nol kolom berarti biarkan aplikasi memilih. */
    fun arrangeGrid(columns: Int = 0) {
        _state.update { state ->
            if (state.photos.isEmpty()) return@update state
            val arranged = state.photos.map { it.item }
                .arrangedInGrid(state.sheetLayout.printable, columns)
            state.copy(
                photos = state.photos.mapIndexed { index, photo ->
                    photo.copy(item = arranged[index])
                }
            )
        }
    }

    /** Memilih foto yang ada di titik itu, dan menaikkannya ke tumpukan atas. */
    fun selectPhotoAt(xMm: Float, yMm: Float) {
        _state.update { state ->
            if (!state.sheetMode) return@update state
            val hit = state.sheetLayout.itemAt(xMm, yMm)
            if (hit == null) {
                state.copy(selectedPhotoId = null)
            } else {
                val reordered = state.photos.map { it.item }.broughtToFront(hit.id)
                state.copy(
                    photos = reordered.mapNotNull { item ->
                        state.photos.firstOrNull { it.id == item.id }?.copy(item = item)
                    },
                    selectedPhotoId = hit.id,
                )
            }
        }
    }

    /**
     * Memutar foto terpilih seperempat putaran.
     *
     * Kalau belum ada yang dipilih, yang diputar adalah foto paling atas --
     * sama seperti perlakuan gestur, supaya tombolnya tidak terasa mati.
     */
    fun rotateSelectedPhoto(quarterTurns: Int) {
        _state.update { state ->
            val id = state.selectedPhotoId ?: state.photos.lastOrNull()?.id ?: return@update state
            state.copy(
                selectedPhotoId = id,
                photos = state.photos.map { photo ->
                    if (photo.id != id) photo else photo.copy(item = photo.item.rotatedBy(quarterTurns))
                },
            )
        }
    }

    fun removeSelectedPhoto() {
        _state.update { state ->
            val id = state.selectedPhotoId ?: return@update state
            val remaining = state.photos.filterNot { it.id == id }
            state.copy(photos = remaining, selectedPhotoId = remaining.lastOrNull()?.id)
        }
    }

    fun clearPhotos() {
        _state.update {
            it.copy(photos = emptyList(), selectedPhotoId = null, layoutEditorOpen = false)
        }
    }

    /** Tanpa isi tidak ada yang bisa diatur, jadi editor tidak dibuka. */
    fun openLayoutEditor() {
        if (!_state.value.hasContent) return
        _state.update { it.copy(layoutEditorOpen = true) }
    }

    fun closeLayoutEditor() {
        _state.update { it.copy(layoutEditorOpen = false) }
    }

    // -------------------------------------------------------------- cetak

    fun print() {
        val current = _state.value
        if (!current.hasContent) return
        val device = current.selectedDevice ?: return

        val pages = if (current.sheetMode) 1 else (current.document?.pageCount ?: 1)
        val sheets = pages * current.settings.copies.coerceAtLeast(1)

        printJob = viewModelScope.launch(ioDispatcher) {
            _state.update {
                it.copy(busy = true, progress = 0f, outcome = PrintOutcome.None)
            }
            val started = System.currentTimeMillis()
            try {
                UsbPrinter.open(usbManager, device).use { printer ->
                    val sink = printer.sink()
                    pageSource(current).use { source ->
                        val (width, height) = current.printableSize
                        log("Mencetak pada $width x $height piksel...")
                        PrintTask.run(
                            sink, source, current.settings,
                            if (current.sheetMode) ContentPlacement.Fit else current.placement
                        ) { progress ->
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
        if (!current.hasContent) return

        viewModelScope.launch(ioDispatcher) {
            _state.update { it.copy(busy = true, progress = 0f) }
            try {
                val app = getApplication<Application>()
                val stream = app.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Tidak bisa menulis ke lokasi itu")

                var written = 0L
                StreamSink(stream).use { sink ->
                    pageSource(current).use { source ->
                        PrintTask.run(
                            sink, source, current.settings,
                            if (current.sheetMode) ContentPlacement.Fit else current.placement
                        ) { progress ->
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

    private suspend fun pageSource(state: UiState): PageSource =
        withContext(ioDispatcher) {
            if (state.sheetMode) {
                SheetPageSource(
                    photos = state.photos.map { SheetPhoto(it.item, it.file) },
                    printableMm = state.sheetLayout.printable,
                )
            } else {
                val document = state.document ?: throw java.io.IOException("Tidak ada dokumen")
                if (document.isPdf) PdfPageSource(document.file) else ImagePageSource(document.file)
            }
        }

    private fun log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { it.copy(log = (it.log + "$stamp  $message").takeLast(60)) }
    }
}
