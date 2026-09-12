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
import com.escpr.usbprint.escpr.Maintenance
import com.escpr.usbprint.escpr.MaintenanceTask
import com.escpr.usbprint.escpr.PrintSettings
import com.escpr.usbprint.layout.ContentPlacement
import com.escpr.usbprint.layout.RectMm
import com.escpr.usbprint.layout.SheetItem
import com.escpr.usbprint.layout.SheetLayout
import com.escpr.usbprint.layout.arrangedInGrid
import com.escpr.usbprint.layout.arrangedInGridPaged
import com.escpr.usbprint.layout.groupedBySheet
import com.escpr.usbprint.layout.broughtToFront
import com.escpr.usbprint.layout.computeSheetLayout
import com.escpr.usbprint.layout.movedBy
import com.escpr.usbprint.layout.rotatedBy
import com.escpr.usbprint.layout.scaledBy
import com.escpr.usbprint.layout.clampedTo
import com.escpr.usbprint.layout.computePageLayout
import com.escpr.usbprint.print.PageSelection
import com.escpr.usbprint.print.PageSelectionMode
import com.escpr.usbprint.print.PrintTask
import com.escpr.usbprint.print.resolvePages
import com.escpr.usbprint.render.ImagePageSource
import com.escpr.usbprint.render.PageSource
import com.escpr.usbprint.render.PdfPageSource
import com.escpr.usbprint.render.PreviewRenderer
import com.escpr.usbprint.render.SheetPageSource
import com.escpr.usbprint.render.SheetPhoto
import com.escpr.usbprint.usb.InkLevel
import com.escpr.usbprint.usb.PrinterErrorKind
import com.escpr.usbprint.usb.PrinterState
import com.escpr.usbprint.usb.PrinterStatus
import com.escpr.usbprint.usb.parsePrinterStatus
import com.escpr.usbprint.usb.printerStateLabel
import com.escpr.usbprint.usb.UsbPrinter
import com.escpr.usbprint.usb.classifyFailure
import androidx.annotation.StringRes
import com.escpr.usbprint.R
import com.escpr.usbprint.util.AppLanguage
import com.escpr.usbprint.util.SettingsStore
import com.escpr.usbprint.util.localizedContext
import com.escpr.usbprint.util.StreamSink
import com.escpr.usbprint.util.copyToCache
import com.escpr.usbprint.util.sweepCache
import com.escpr.usbprint.util.DocumentKind
import com.escpr.usbprint.util.classifyDocument
import com.escpr.usbprint.util.displayName
import com.escpr.usbprint.util.formatBytes
import com.escpr.usbprint.util.mimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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

/**
 * Kenapa aplikasi sedang sibuk.
 *
 * Bukan hiasan: bilah bawah menampilkan kemajuan cetak dan tombol Batalkan
 * selama sibuk. Untuk percakapan singkat dengan printer -- membaca sisa tinta,
 * mengirim perintah perawatan -- keduanya menyesatkan: tidak ada kemajuan untuk
 * ditampilkan, dan tidak ada pekerjaan cetak yang bisa dibatalkan.
 */
enum class BusyReason { PRINTING, PRINTER_TALK }

/** Sekitar tiga detik total: printer yang sibuk butuh waktu menyusun laporan. */
private const val STATUS_READ_ATTEMPTS = 8
private const val STATUS_READ_TIMEOUT_MS = 400

/**
 * Batas menunggu printer selesai mengerjakan perintah perawatan.
 *
 * Pembersihan head pada sebagian model berlangsung sampai dua menit, jadi
 * batasnya dibuat longgar. Menunggu terlalu sebentar berarti sambungan ditutup
 * di tengah pekerjaan, yang justru masalah yang ingin diperbaiki.
 */
private const val MAINTENANCE_WAIT_MS = 150_000L

/**
 * Jeda terendah sebelum jawaban "siap" dari printer boleh dipercaya.
 *
 * Printer menjawab idle pada detik yang sama perintah perawatan dikirim --
 * ia memang belum mulai bergerak. Tanpa jeda ini, menunggu sampai siap sama
 * saja dengan tidak menunggu sama sekali.
 */
private const val MAINTENANCE_MIN_HOLD_MS = 12_000L

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
    val busyReason: BusyReason = BusyReason.PRINTING,
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
    /** Halaman PDF mana yang dicetak. Tidak berlaku untuk lembar foto. */
    val pageSelection: PageSelection = PageSelection(),
    /** Banyaknya foto per lembar. Nol berarti semuanya pada satu lembar. */
    val photosPerSheet: Int = 0,
    /** Lembar foto yang sedang ditampilkan di pratinjau. */
    val sheetPage: Int = 0,
    /** Perawatan yang sedang menunggu persetujuan pengguna. */
    val maintenanceAsked: MaintenanceTask? = null,
    /**
     * Bentuk perintah perawatan yang dipakai.
     *
     * Ada di layar karena bentuk yang benar berbeda antar-model dan tidak ada
     * cara mengetahuinya selain mencoba. Tanpa pilihan ini, tiap percobaan
     * berarti membangun ulang aplikasi.
     */
    val maintenanceVariant: Maintenance.Variant = Maintenance.DEFAULT_VARIANT,
    /** Sisa tinta hasil pembacaan terakhir. Kosong pada printer tangki. */
    val inks: List<InkLevel> = emptyList(),
    /** Keadaan printer hasil pembacaan terakhir; null berarti belum diperiksa. */
    val printerState: PrinterState? = null,
    /**
     * Sudah pernah mencoba membaca sisa tinta sejak aplikasi dibuka.
     *
     * Dibedakan dari daftar kosong supaya "belum pernah diperiksa" dan
     * "sudah diperiksa, printer tidak melaporkan apa-apa" tidak tampak sama.
     */
    val inkChecked: Boolean = false,
    /** Bahasa yang dipilih pengguna; SYSTEM berarti ikut bahasa HP. */
    val language: AppLanguage = AppLanguage.SYSTEM,
) {
    /** Indeks halaman yang akan dicetak, sudah diselesaikan dari pilihan. */
    val pagesToPrint: List<Int>
        get() = if (sheetMode) List(sheetPages.size) { it }
        else resolvePages(pageSelection, previewPage, document?.pageCount ?: 0)

    /**
     * Gambar disusun sebagai lembar; PDF tetap lewat jalur dokumen karena
     * halamannya punya ukuran sendiri dan tidak bisa ditumpuk sembarangan.
     */
    val sheetMode: Boolean get() = photos.isNotEmpty()

    /** Foto dikelompokkan per lembar, terurut, tanpa lembar kosong. */
    val sheetPages: List<List<PhotoOnSheet>>
        get() = photos.groupedBySheet { it.item.page }

    /** Lembar yang sedang dilihat, sudah dijaga agar tidak melewati batas. */
    val currentSheet: Int
        get() = sheetPage.coerceIn(0, (sheetPages.size - 1).coerceAtLeast(0))

    /** Foto pada lembar yang sedang dilihat; itulah yang bisa diatur. */
    val currentSheetPhotos: List<PhotoOnSheet>
        get() = sheetPages.getOrNull(currentSheet).orEmpty()

    val sheetLayout: SheetLayout
        get() = computeSheetLayout(
            paperWidthMm = settings.paper.widthMm,
            paperHeightMm = settings.paper.heightMm,
            marginMm = settings.marginMm,
            items = currentSheetPhotos.map { it.item },
        )

    /** Area cetak yang berlaku, dari model mana pun yang sedang dipakai. */
    val printableRect: RectMm
        get() = if (sheetMode) sheetLayout.printable else pageLayout.printable

    /** Isi yang digambar pratinjau, seragam untuk kedua mode. */
    val previewItems: List<PreviewItem>
        get() = if (sheetMode) {
            currentSheetPhotos.map { photo ->
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
                // Tanpa awalan kata apa pun. VID:PID sudah berdiri di bawah
                // judul "Printer terdeteksi", dan UiState tidak punya Context
                // untuk menerjemahkan kata pengantar yang tidak menambah apa-apa.
                device.productName
                    ?: "%04X:%04X".format(device.vendorId, device.productId)
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
    private val settingsStore = SettingsStore(app)

    /**
     * Context yang resource-nya sudah berbahasa pilihan pengguna.
     *
     * Layar mendapat bahasanya dari attachBaseContext; catatan tidak, karena
     * ditulis dari ViewModel yang memegang Application. Tanpa ini, aplikasi
     * yang disetel berbahasa Inggris di HP berbahasa Indonesia akan menulis
     * catatan berbahasa Indonesia di bawah layar berbahasa Inggris.
     */
    @Volatile
    private var strings: Context =
        localizedContext(app, settingsStore.loadLanguage())

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
                        log(R.string.log_permission_granted)
                        _state.update { it.copy(permissionDenied = false) }
                        refreshDevices()
                        readDeviceIdentity()
                    } else {
                        log(R.string.log_permission_denied)
                        // Ditandai supaya daftar periksa tidak sekadar bilang
                        // "menunggu izin", dan supaya tidak ada permintaan ulang
                        // otomatis yang berubah jadi lingkaran dialog.
                        _state.update {
                            it.copy(hasPermission = false, permissionDenied = true)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    log(R.string.log_usb_attached)
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    log(R.string.log_usb_detached)
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
        // Pengaturan terakhir dipulihkan supaya pengguna yang selalu mencetak
        // hitam-putih draft tidak perlu mengatur ulang lima chip tiap kali.
        _state.update {
            it.copy(
                hasUsbHost = usbHost,
                settings = settingsStore.load(),
                language = settingsStore.loadLanguage(),
            )
        }
        if (!usbHost) log(R.string.log_no_usb_host)

        sweepCacheQuietly()
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
        if (devices.isEmpty()) log(R.string.log_no_printer)
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
                        log(R.string.log_device_id_no_reply)
                    EscpRSupport.SUPPORTED ->
                        log(R.string.log_device_id_supported, id.orEmpty())
                    else ->
                        log(R.string.log_device_id_not_listed, id.orEmpty())
                }
            }.onFailure { error ->
                _state.update { it.copy(escpRSupport = EscpRSupport.NO_REPLY) }
                log(R.string.log_device_id_failed, error.message.orEmpty())
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

        when (classifyDocument(mime, name)) {
            DocumentKind.PDF -> openPdf(uri)

            // Format yang dikenali tapi belum didukung ditolak di sini dengan
            // penjelasan yang benar. Sebelumnya berkas seperti ini diperlakukan
            // sebagai gambar, gagal diurai, lalu dilaporkan "gagal membuka
            // gambar" -- menyesatkan, karena berkasnya sendiri tidak rusak.
            DocumentKind.OFFICE -> {
                _state.update {
                    it.copy(
                        outcome = PrintOutcome.Failed(
                            PrinterErrorKind.UNSUPPORTED_FORMAT,
                            strings.getString(R.string.err_format_unsupported, mime.ifBlank { name }),
                        )
                    )
                }
                log(R.string.log_format_unsupported, name)
            }

            // Gambar, atau tipe yang tidak dilaporkan sama sekali. Yang terakhir
            // tetap dicoba sebagai gambar karena sebagian penyedia dokumen
            // memang tidak memberi tipe MIME.
            DocumentKind.IMAGE, DocumentKind.UNKNOWN -> addPhotos(listOf(uri))
        }
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
                        pageSelection = PageSelection(),
                    )
                }
                log(R.string.log_selected, document.name, document.pageCount)
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
                log(R.string.log_open_failed, error.message.orEmpty())
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
                log(R.string.log_preview_failed, error.message.orEmpty())
            }
        }
    }

    fun updatePageSelection(transform: (PageSelection) -> PageSelection) {
        _state.update { it.copy(pageSelection = transform(it.pageSelection)) }
    }

    /**
     * Mengembalikan seluruh setelan cetak ke bawaan.
     *
     * Ada karena setelan diingat antar-sesi. Itu berguna sehari-hari, tapi
     * berarti sekali seseorang menjelajah ke 600 dpi dan jenis kertas Matte,
     * ia tinggal di sana selamanya -- termasuk ketika bawaan aplikasinya
     * kemudian diperbaiki. Jumlah salinan sengaja ikut kembali ke satu.
     */
    fun resetSettings() {
        _state.update { state ->
            val bawaan = PrintSettings()
            settingsStore.save(bawaan)
            log(R.string.log_settings_reset)
            state.copy(settings = bawaan)
        }
    }

    /**
     * Mengganti bahasa aplikasi.
     *
     * Layar dibangun ulang oleh Activity begitu nilai ini berubah; yang
     * dikerjakan di sini hanya menyimpannya dan memindahkan sumber kalimat
     * catatan ke bahasa yang baru. Catatan yang sudah tertulis sengaja
     * dibiarkan apa adanya: ia riwayat berstempel waktu, bukan tampilan.
     */
    fun setLanguage(language: AppLanguage) {
        if (language == _state.value.language) return
        settingsStore.saveLanguage(language)
        strings = localizedContext(getApplication(), language)
        _state.update { it.copy(language = language) }
    }

    fun updateSettings(transform: (PrintSettings) -> PrintSettings) {
        _state.update { state ->
            val next = transform(state.settings)
            settingsStore.save(next)
            state.copy(settings = next)
        }
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
                log(R.string.log_image_open_failed, message)
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
                val arranged = combined.map { it.item }
                    .arrangedInGridPaged(printable, state.photosPerSheet)
                val laid = combined.mapIndexed { index, photo ->
                    photo.copy(item = arranged[index])
                }
                state.copy(
                    document = null,
                    previewImage = null,
                    photos = laid,
                    selectedPhotoId = added.last().id,
                    // Pindah ke lembar tempat foto baru mendarat, kalau tidak
                    // pengguna melihat lembar lama dan mengira fotonya hilang.
                    sheetPage = laid.last().item.page,
                    layoutEditorOpen = false,
                )
            }
            log(R.string.log_photos_added, added.size, _state.value.photos.size)
        }
    }

    /** Menyusun ulang ke kisi. Nol kolom berarti biarkan aplikasi memilih. */
    fun arrangeGrid(columns: Int = 0) {
        _state.update { state ->
            if (state.photos.isEmpty()) return@update state
            val arranged = state.photos.map { it.item }
                .arrangedInGridPaged(state.sheetLayout.printable, state.photosPerSheet, columns)
            state.copy(
                photos = state.photos.mapIndexed { index, photo ->
                    photo.copy(item = arranged[index])
                }
            )
        }
    }

    /**
     * Mengubah banyaknya foto per lembar, lalu menyusun ulang semuanya.
     *
     * Penyusunan ulang tidak bisa dihindari: nilai baru menentukan foto mana
     * berada di lembar mana, dan posisi lama pada lembar lama sudah tidak
     * berarti apa-apa di lembar barunya.
     */
    fun setPhotosPerSheet(perSheet: Int) {
        _state.update { state ->
            val value = perSheet.coerceIn(0, 64)
            if (value == state.photosPerSheet) return@update state
            val arranged = state.photos.map { it.item }
                .arrangedInGridPaged(state.sheetLayout.printable, value)
            state.copy(
                photosPerSheet = value,
                photos = state.photos.mapIndexed { index, photo ->
                    photo.copy(item = arranged[index])
                },
                sheetPage = 0,
            )
        }
    }

    fun showSheet(page: Int) {
        _state.update { state ->
            state.copy(sheetPage = page.coerceIn(0, (state.sheetPages.size - 1).coerceAtLeast(0)))
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
            val sheets = remaining.groupedBySheet { it.item.page }.size
            state.copy(
                photos = remaining,
                selectedPhotoId = remaining.lastOrNull()?.id,
                sheetPage = state.sheetPage.coerceIn(0, (sheets - 1).coerceAtLeast(0)),
            )
        }
    }

    fun clearPhotos() {
        _state.update {
            it.copy(
                photos = emptyList(),
                selectedPhotoId = null,
                sheetPage = 0,
                layoutEditorOpen = false,
            )
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

    // ----------------------------------------------------------- perawatan

    /**
     * Meminta persetujuan sebelum menjalankan perawatan.
     *
     * Keduanya memakai sumber daya yang tidak kembali -- selembar kertas atau
     * sejumlah tinta -- dan tidak bisa dibatalkan setelah printer mulai. Jadi
     * tidak ada satu pun yang berjalan hanya karena tombolnya tersenggol.
     */
    fun askMaintenance(task: MaintenanceTask) {
        if (_state.value.busy) return
        _state.update { it.copy(maintenanceAsked = task) }
    }

    fun setMaintenanceVariant(variant: Maintenance.Variant) {
        _state.update { it.copy(maintenanceVariant = variant) }
    }

    fun dismissMaintenance() {
        _state.update { it.copy(maintenanceAsked = null) }
    }

    /**
     * Membaca sisa tinta dari printer.
     *
     * Hanya membaca: tidak ada satu pun byte yang mengubah keadaan printer.
     * Angkanya perkiraan printer sendiri -- printer tangki tinta tidak punya
     * sensor di dalam tangki dan hanya menghitung tetes sejak terakhir kali
     * di-reset.
     */
    fun refreshInk() {
        val current = _state.value
        if (current.busy) return
        val device = current.selectedDevice ?: return

        // Dicatat sebagai printJob supaya tombol berhenti benar-benar mengenai
        // pekerjaan ini. Tanpa itu tombolnya membatalkan pekerjaan lama yang
        // sudah selesai -- terlihat berfungsi, padahal tidak melakukan apa-apa.
        printJob = viewModelScope.launch(ioDispatcher) {
            _state.update { it.copy(busy = true, busyReason = BusyReason.PRINTER_TALK) }
            try {
                UsbPrinter.open(usbManager, device).use { printer ->
                    val request = Maintenance.statusRequest()
                    printer.writeBulk(request, 0, request.size)

                    val status = parsePrinterStatus(readStatusReply(printer))
                    if (status.raw.isNotBlank()) log(R.string.log_status, status.raw)
                    // Dicatat berbaris-baris pendek, bukan satu baris panjang.
                    // Balasannya ratusan byte, dan satu baris panjang di kartu
                    // catatan tidak bisa dibaca dari tangkapan layar -- padahal
                    // kepala balasan itulah yang menentukan bentuknya.
                    if (status.hex.isNotBlank()) {
                        val byteList = status.hex.split(" ")
                        log(R.string.log_reply_bytes, byteList.size)
                        byteList.chunked(24).forEachIndexed { index, bagian ->
                            log("  [" + (index * 24) + "] " + bagian.joinToString(" "))
                        }
                    }
                    _state.update {
                        it.copy(
                            inks = status.inks,
                            inkChecked = true,
                            printerState = if (status.confident) status.state else null,
                        )
                    }

                    if (status.confident) log(R.string.log_printer_state, printerStateWord(status.state))
                    if (status.inks.isEmpty()) {
                        log(R.string.log_ink_unreported)
                    } else {
                        log(
                            status.inks.joinToString(", ") { ink ->
                                ink.label.resolve(strings) + " " + ink.reading.resolve(strings)
                            }
                        )
                    }
                }
            } catch (error: Throwable) {
                _state.update { it.copy(inkChecked = true) }
                log(R.string.log_ink_failed, error.message ?: error.toString())
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Mengumpulkan balasan printer sampai berhenti mengalir.
     *
     * Balasan status bisa datang terpotong beberapa paket USB, jadi pembacaan
     * diteruskan sampai ada satu bacaan kosong -- bukan berhenti begitu judul
     * `@BDC ST2` terlihat, karena judulnya datang paling awal dan justru sisa
     * tinta ada di belakangnya.
     */
    private fun readStatusReply(printer: UsbPrinter): ByteArray {
        var reply = ByteArray(0)
        repeat(STATUS_READ_ATTEMPTS) {
            val chunk = printer.readStatus(STATUS_READ_TIMEOUT_MS)
            if (chunk.isEmpty()) {
                if (reply.isNotEmpty()) return reply
            } else {
                reply += chunk
            }
        }
        return reply
    }

    /**
     * Mengirim satu perintah perawatan ke printer.
     *
     * Perintahnya hanya beberapa puluh byte, jadi tidak ada kemajuan yang
     * berarti untuk ditampilkan. Yang bisa dipastikan hanyalah byte-nya sampai;
     * apakah printer mengerjakannya terlihat dari printernya, bukan dari sini.
     */
    fun runMaintenance(task: MaintenanceTask) {
        val current = _state.value
        if (current.busy) return
        val device = current.selectedDevice ?: return

        printJob = viewModelScope.launch(ioDispatcher) {
            _state.update {
                it.copy(
                    busy = true,
                    busyReason = BusyReason.PRINTER_TALK,
                    progress = 0f,
                    maintenanceAsked = null,
                    outcome = PrintOutcome.None,
                )
            }
            try {
                UsbPrinter.open(usbManager, device).use { printer ->
                    val sink = printer.sink()
                    val bytes = task.bytes(current.maintenanceVariant)
                    log(
                        strings.getString(task.label) +
                            (if (task.usesVariant) " (" + current.maintenanceVariant.name + ")" else "") +
                            ": " + bytes.joinToString(" ") { "%02X".format(it) }
                    )
                    sink.write(bytes, 0, bytes.size)
                    sink.flush()

                    // Sambungan sengaja ditahan sampai printer melaporkan
                    // dirinya siap lagi. Menutupnya tepat setelah byte
                    // terakhir terkirim membuat printer kehilangan host di
                    // tengah pekerjaan -- dan pada percobaan sungguhan
                    // kertasnya berhenti separuh keluar sementara motornya
                    // terus berjalan.
                    //
                    // Selama menunggu tidak satu byte pun dikirim. Permintaan
                    // status memuat ESC @ yang akan mereset printer di tengah
                    // mencetak, jadi yang dilakukan hanya membaca.
                    val mulaiTunggu = System.currentTimeMillis()
                    val akhir = waitUntilIdle(printer)
                    val detik = (System.currentTimeMillis() - mulaiTunggu) / 1000
                    log(
                        R.string.log_maint_waited,
                        detik,
                        (akhir?.state?.let { printerStateWord(it) }
                            ?: strings.getString(R.string.log_maint_no_answer)) +
                            (if (akhir != null && !akhir.confident) {
                                strings.getString(R.string.log_maint_unrecognised)
                            } else {
                                ""
                            }),
                    )

                }
                _state.update { it.copy(outcome = PrintOutcome.MaintenanceSent(task)) }
                log(R.string.log_maint_sent, strings.getString(task.label))
            } catch (error: Throwable) {
                val stillAttached = usbManager.deviceList.values
                    .any { it.deviceName == device.deviceName }
                val kind = classifyFailure(error, stillAttached)
                _state.update {
                    it.copy(
                        outcome = PrintOutcome.Failed(kind, error.message ?: error.toString())
                    )
                }
                log(
                    R.string.log_maint_failed,
                    strings.getString(task.label),
                    error.message ?: error.toString(),
                )
            } finally {
                _state.update { it.copy(busy = false, progress = 0f) }
            }
        }
    }

    /**
     * Menunggu printer melaporkan dirinya siap lagi.
     *
     * Hanya membaca; tidak satu byte pun dikirim. Itu disengaja: permintaan
     * status dibungkus ESC @ yang akan mereset printer kalau tiba di tengah
     * pekerjaan, dan yang ingin dihindari justru itu.
     *
     * Selesainya tidak boleh bergantung pada balasan yang bisa diurai. Balasan
     * printer berbeda bentuk antar-model dan antar-perintah, dan begitu satu
     * bentuk tidak dikenali, penantian berjalan sampai batas penuh -- yang
     * berarti pengeluaran kertas baru dikirim dua setengah menit kemudian.
     * Itu persis yang terjadi pada 2.13: bentuk balasan berubah, dan langkah
     * sesudahnya ikut tertahan.
     *
     * Karena itu tanda selesai yang utama adalah **printer berhenti bicara**
     * setelah jeda terendah terlampaui. Status yang terurai dipakai kalau ada,
     * tapi tidak pernah menjadi syarat.
     */
    private suspend fun waitUntilIdle(printer: UsbPrinter): PrinterStatus? {
        val mulai = System.currentTimeMillis()
        // Jeda terendah sebelum jawaban "siap" boleh dipercaya. Tanpa ini
        // penantiannya sia-sia: pada percobaan sungguhan printer menjawab
        // idle pada detik yang sama perintahnya dikirim -- ia memang belum
        // mulai bergerak. Sambungan lalu ditutup tepat saat printer menarik
        // kertas, dan kertasnya berhenti separuh keluar.
        val palingCepat = mulai + MAINTENANCE_MIN_HOLD_MS
        val batas = mulai + MAINTENANCE_WAIT_MS

        var terakhir: PrinterStatus? = null
        while (System.currentTimeMillis() < batas) {
            // Pembatalan diperiksa tiap putaran. readStatus memblokir sampai
            // satu detik dan tidak bisa disela, jadi tanpa pemeriksaan ini
            // pengguna terkunci sampai dua setengah menit tanpa jalan keluar
            // pada printer yang tidak pernah menjawab.
            if (!currentCoroutineContext().isActive) return terakhir

            val potongan = printer.readStatus(1000)
            val lewatJeda = System.currentTimeMillis() >= palingCepat

            if (potongan.isEmpty()) {
                // Printer berhenti bicara dan jeda terendah sudah lewat:
                // itulah tanda selesai yang berlaku untuk bentuk balasan apa pun.
                if (lewatJeda) return terakhir
                continue
            }

            val status = parsePrinterStatus(potongan)
            terakhir = status
            if (status.confident && status.state == PrinterState.IDLE && lewatJeda) {
                return status
            }
        }
        return terakhir
    }

    /**
     * Membuang salinan dokumen lama dari cache.
     *
     * Tiap berkas yang dibuka disalin ke cache supaya bisa di-seek, dan sampai
     * sekarang tidak ada satu pun yang pernah menghapusnya kembali: mencetak
     * dua ratus foto berarti dua ratus salinan menetap sampai Android sendiri
     * yang membersihkan. Yang sedang dipakai selalu dipertahankan.
     */
    private fun sweepCacheQuietly() {
        viewModelScope.launch(ioDispatcher) {
            val state = _state.value
            val keep = buildSet {
                state.document?.let { add(it.file.absolutePath) }
                state.photos.forEach { add(it.file.absolutePath) }
            }
            val removed = runCatching {
                sweepCache(getApplication<Application>().cacheDir, keep)
            }.getOrDefault(0)
            if (removed > 0) log(R.string.log_cache_cleaned, removed)
        }
    }

    // -------------------------------------------------------------- cetak

    fun print() {
        val current = _state.value
        if (!current.hasContent) return
        val device = current.selectedDevice ?: return

        val sheets = current.pagesToPrint.size * current.settings.copies.coerceAtLeast(1)

        printJob = viewModelScope.launch(ioDispatcher) {
            _state.update {
                it.copy(
                    busy = true,
                    busyReason = BusyReason.PRINTING,
                    progress = 0f,
                    outcome = PrintOutcome.None,
                )
            }
            val started = System.currentTimeMillis()
            try {
                UsbPrinter.open(usbManager, device).use { printer ->
                    // Diperiksa sebelum byte pertama dikirim. Tanpa ini, kertas
                    // habis baru ketahuan setelah separuh halaman terlanjur
                    // terkirim dan transfer gagal di tengah jalan.
                    val before = parsePrinterStatus(printer.readStatus(700))
                    if (before.raw.isNotBlank()) log(R.string.log_printer_status, before.raw)
                    if (before.blocksPrinting) {
                        _state.update {
                            it.copy(
                                outcome = PrintOutcome.Failed(
                                    PrinterErrorKind.PRINTER_NOT_READY,
                                    before.message?.resolve(strings)
                                        ?: strings.getString(R.string.log_printer_not_ready),
                                )
                            )
                        }
                        log(
                            R.string.log_cancelled_before_send,
                            before.message?.resolve(strings)
                                ?: strings.getString(R.string.log_printer_not_ready),
                        )
                        return@use
                    }

                    val sink = printer.sink()
                    pageSource(current).use { source ->
                        val (width, height) = current.printableSize
                        log(R.string.log_printing_at, width, height)
                        val laporan = PrintTask.run(
                            sink, source, current.settings,
                            if (current.sheetMode) ContentPlacement.Fit else current.placement,
                            current.pagesToPrint,
                        ) { progress ->
                            _state.update { it.copy(progress = progress.fraction) }
                        }
                        // Dicatat supaya "lambat" bisa ditindaklanjuti. Yang
                        // bisa diperbaiki dengan kode hanya bagian gambar dan
                        // olah; kalau yang besar justru kirim, batasnya ada di
                        // kabel dan printer, bukan di aplikasi.
                        log(
                            R.string.log_time,
                            strings.getString(R.string.log_time_detail, *laporan.angka()),
                        )
                        log(R.string.log_link, printer.linkSpeed().resolve(strings))
                    }
                    sink.close()

                    val after = parsePrinterStatus(printer.readStatus(500))
                    after.message?.let { log(R.string.log_printer, it.resolve(strings)) }
                    if (after.raw.isNotBlank()) log(R.string.log_final_status, after.raw)
                }
                val seconds = (System.currentTimeMillis() - started) / 1000.0
                log(R.string.log_done_in, seconds)
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
                log(R.string.log_print_failed, kind.name, error.message.orEmpty())
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
        log(R.string.log_cancel_requested)
    }

    /**
     * Menulis job ke file .prn alih-alih ke printer. Berguna untuk memastikan
     * masalahnya ada di kabel/USB atau di data yang dihasilkan.
     */
    fun exportPrn(uri: Uri) {
        val current = _state.value
        if (!current.hasContent) return

        // Alasan sibuk harus disebut tegas. Kalau tidak, ia mewarisi nilai dari
        // pekerjaan sebelumnya -- menyimpan .prn sesudah cek nozzle akan
        // menampilkan "Menghubungi printer" padahal tidak ada printer yang
        // disentuh sama sekali.
        printJob = viewModelScope.launch(ioDispatcher) {
            _state.update {
                it.copy(busy = true, busyReason = BusyReason.PRINTING, progress = 0f)
            }
            try {
                val app = getApplication<Application>()
                val stream = app.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException(strings.getString(R.string.err_cannot_write))

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
                log(R.string.log_saved_prn, formatBytes(written))
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
                log(R.string.log_save_failed, error.message.orEmpty())
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
                    pages = state.sheetPages.map { sheet ->
                        sheet.map { SheetPhoto(it.item, it.file) }
                    },
                    printableMm = state.sheetLayout.printable,
                )
            } else {
                val document = state.document ?: throw java.io.IOException(strings.getString(R.string.err_no_document))
                if (document.isPdf) PdfPageSource(document.file) else ImagePageSource(document.file)
            }
        }

    /** Menulis satu baris catatan dalam bahasa yang sedang dipakai aplikasi. */
    /** Nama keadaan printer untuk catatan, mengikuti bahasa aplikasi. */
    private fun printerStateWord(state: PrinterState): String =
        strings.getString(printerStateLabel(state))

    private fun log(@StringRes id: Int, vararg args: Any) {
        log(strings.getString(id, *args))
    }

    private fun log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { it.copy(log = (it.log + "$stamp  $message").takeLast(60)) }
    }
}
