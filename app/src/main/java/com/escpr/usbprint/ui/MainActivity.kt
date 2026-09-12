package com.escpr.usbprint.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.annotation.StringRes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.escpr.usbprint.BuildConfig
import com.escpr.usbprint.R
import com.escpr.usbprint.escpr.ColorMode
import com.escpr.usbprint.escpr.Dpi
import com.escpr.usbprint.escpr.MediaType
import com.escpr.usbprint.escpr.EscpRJob
import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.escpr.PrintDirection
import com.escpr.usbprint.escpr.PrintPreset
import com.escpr.usbprint.escpr.presetOf
import com.escpr.usbprint.escpr.Quality
import com.escpr.usbprint.layout.PageLayout
import com.escpr.usbprint.util.AppLanguage
import com.escpr.usbprint.util.SettingsStore
import com.escpr.usbprint.util.formatBytes
import com.escpr.usbprint.util.localizedContext
import com.escpr.usbprint.util.text
import com.escpr.usbprint.print.PageSelectionMode
import com.escpr.usbprint.ui.theme.AppTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: PrintViewModel by viewModels()

    /**
     * Memasang bahasa pilihan pengguna sebelum satu pun tampilan dibuat.
     *
     * Harus di sini, bukan di onCreate: resource sudah dibaca saat Activity
     * berdiri, jadi mengganti locale setelah itu hanya berpengaruh pada teks
     * yang kebetulan dibaca belakangan -- sebagian layar berganti bahasa,
     * sebagian tidak.
     */
    override fun attachBaseContext(newBase: Context) {
        val bahasa = SettingsStore(newBase).loadLanguage()
        super.attachBaseContext(localizedContext(newBase, bahasa))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { AppTheme { PrintScreen(viewModel) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshDevices()
    }

    /**
     * Menerima berkas dari luar aplikasi.
     *
     * Tiga jalan masuk: dibagikan satu berkas, dibagikan beberapa foto
     * sekaligus, atau dibuka langsung dari pengelola berkas. Ketiganya berakhir
     * di tempat yang sama, hanya cara mengambil URI-nya yang berbeda.
     */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> streamExtra(intent)?.let(viewModel::openDocument)

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = streamExtras(intent)
                // Banyak berkas hanya masuk akal sebagai lembar foto. PDF yang
                // ikut terbawa akan ditolak satu per satu oleh openDocument
                // dengan penjelasannya sendiri.
                if (uris.isNotEmpty()) viewModel.addPhotos(uris)
            }

            Intent.ACTION_VIEW -> intent.data?.let(viewModel::openDocument)
        }
    }

    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun streamExtras(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }.orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrintScreen(viewModel: PrintViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Bahasa dipasang di attachBaseContext, jadi menggantinya berarti membangun
    // ulang Activity. Nilai awalnya diingat supaya pembangunan ulang hanya
    // terjadi ketika pengguna benar-benar memilih bahasa lain, bukan pada
    // setiap penyusunan ulang layar.
    val context = LocalContext.current
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val bahasaAwal = remember { state.language }
    LaunchedEffect(state.language) {
        if (state.language != bahasaAwal) (context as? Activity)?.recreate()
    }

    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::openDocument) }

    val addPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.addPhotos(uris) }

    val savePrn = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(viewModel::exportPrn) }

    // Pekerjaan cetak berjalan di viewModelScope milik layar ini. Kalau layar
    // terkunci, Android bisa menghentikan prosesnya di tengah jalan dan kertas
    // tinggal separuh tercetak. Menahan layar tetap menyala menutup penyebab
    // yang paling sering, walau bukan penggantinya foreground service.
    val view = LocalView.current
    DisposableEffect(state.busy) {
        val window = (view.context as? Activity)?.window
        if (state.busy) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.app_title), fontWeight = FontWeight.SemiBold)
                        // Subjudul menambahkan yang tidak disebut di tempat
                        // lain. Nama printer sudah tampil di kartu sambungan,
                        // dan "ESC/P-R" -- nama bahasa raster Epson -- hanya
                        // berarti bagi yang menulis kodenya. Yang berguna bagi
                        // pengguna justru alasan aplikasi ini ada, ditambah
                        // nomor versi untuk melaporkan masalah.
                        Text(
                            stringResource(R.string.app_subtitle, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshDevices) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_find_printer),
                        )
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_screen_title),
                        )
                    }
                }
            )
        },
        bottomBar = {
            PrintBar(
                state = state,
                viewModel = viewModel,
                onPickFile = { pickDocument.launch(arrayOf("image/*", "application/pdf")) },
                onExport = { savePrn.launch(viewModel.suggestedFileName()) },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(2.dp))
            // Status sambungan lebih dulu: kalau ini belum beres, tidak ada
            // gunanya pengguna mengatur kertas atau melihat pratinjau.
            ConnectionCard(state, viewModel)
            PreviewSection(
                state = state,
                viewModel = viewModel,
                onPick = { pickDocument.launch(arrayOf("image/*", "application/pdf")) },
                onAddPhotos = { addPhotos.launch(arrayOf("image/*")) },
            )
            PrintSettingsSection(state, viewModel)
            LogSection(state) { savePrn.launch(viewModel.suggestedFileName()) }
            Spacer(Modifier.height(8.dp))
        }
    }

    LayoutEditorDialog(state, viewModel)
    SettingsDialog(settingsOpen, state, viewModel) { settingsOpen = false }
}

// ------------------------------------------------------------ pratinjau

/**
 * Pratinjau ringkas di halaman utama.
 *
 * Sengaja tidak bisa diatur di sini: menggeser satu milimeter pada kertas
 * sebesar ini tidak terlihat bedanya, dan gestur di dalam halaman yang bisa
 * digulir akan saling berebut sentuhan. Mengetuknya membuka editor layar penuh.
 */
@Composable
private fun PreviewSection(
    state: UiState,
    viewModel: PrintViewModel,
    onPick: () -> Unit,
    onAddPhotos: () -> Unit,
) {
    val document = state.document
    val settings = state.settings
    val layout = state.pageLayout

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            // Sisi kiri-kanan dipersempit supaya kertasnya lebih lebar. Inilah
            // satu-satunya bagian layar yang benar-benar dipandangi orang, dan
            // sebelumnya ia dikelilingi ruang kosong yang lebih luas daripada
            // kertasnya sendiri.
            Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .then(
                        if (document != null && !state.busy) {
                            Modifier.clickable(onClick = viewModel::openLayoutEditor)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                PagePreview(
                    paperWidthMm = settings.paper.widthMm,
                    paperHeightMm = settings.paper.heightMm,
                    printable = state.printableRect,
                    items = state.previewItems,
                    monochrome = settings.colorMode == ColorMode.MONO,
                    interactive = false,
                    onTapMm = { _, _ -> },
                    onGesture = { _, _, _ -> },
                    onReset = {},
                    modifier = Modifier.fillMaxSize()
                )
                if (state.previewLoading) {
                    CircularProgressIndicator(Modifier.size(36.dp))
                }
            }

            if (state.hasContent) {
                Button(onClick = viewModel::openLayoutEditor, enabled = !state.busy) {
                    Text(stringResource(R.string.preview_arrange))
                }

                if (state.sheetMode) {
                    Text(
                        stringResource(R.string.preview_photos_on_sheet, state.photos.size),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        stringResource(
                            R.string.preview_margins,
                            fmtMm(layout.marginLeftMm),
                            fmtMm(layout.marginTopMm),
                            fmtMm(layout.marginRightMm),
                            fmtMm(layout.marginBottomMm),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                }

                OverflowWarning(state)
            }

            if (document != null && document.pageCount > 1) {
                PageSelectionRow(state, viewModel, document.pageCount)
            }

            // Navigasi halaman hanya berguna untuk PDF banyak halaman.
            if (document != null && document.pageCount > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { viewModel.showPage(state.previewPage - 1) },
                        enabled = state.previewPage > 0
                    ) { Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            stringResource(R.string.preview_page_prev),
                        ) }
                    Text(
                        stringResource(
                            R.string.preview_page_of,
                            state.previewPage + 1,
                            document.pageCount,
                        ),
                        style = MaterialTheme.typography.labelLarge
                    )
                    IconButton(
                        onClick = { viewModel.showPage(state.previewPage + 1) },
                        enabled = state.previewPage < document.pageCount - 1
                    ) { Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            stringResource(R.string.preview_page_next),
                        ) }
                }
            }

            val (width, height) = state.printableSize
            Text(
                text = if (document == null) {
                    stringResource(R.string.preview_pick_prompt)
                } else {
                    stringResource(R.string.preview_print_area, width, height, settings.dpi.value)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPick, enabled = !state.busy) {
                    Text(
                        stringResource(
                            if (state.hasContent) R.string.preview_change_file
                            else R.string.preview_pick_file
                        )
                    )
                }
                if (state.sheetMode) {
                    OutlinedButton(onClick = onAddPhotos, enabled = !state.busy) {
                        Text(stringResource(R.string.preview_add_photos))
                    }
                }
            }

            if (document != null && !state.sheetMode) {
                // Sebagian penyedia dokumen mengembalikan nama yang sangat
                // panjang, jadi dibatasi satu baris agar tidak mendorong tata
                // letak ke bawah.
                Text(
                    document.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Berapa besar data yang akan dikirim ke printer.
 *
 * Foto hampir tidak bisa dipadatkan -- diukur 100 persen pada foto sungguhan --
 * jadi angka mentah inilah yang benar-benar melewati kabel. Tanpa disebutkan,
 * pilihan 600 dpi tampak sama murahnya dengan 300 dpi padahal empat kali lebih
 * banyak, dan orang baru menyadarinya setelah menunggu dua menit di depan
 * printer.
 */
@Composable
private fun DataSizeNote(state: UiState) {
    val lembar = state.pagesToPrint.size.coerceAtLeast(1) *
        state.settings.copies.coerceAtLeast(1)
    val total = EscpRJob.estimatedBytesPerPage(state.settings) * lembar

    // Satu baris, bukan paragraf. Angkanya yang berguna; alasannya ada di
    // README dan tidak perlu diulang di layar setiap kali.
    Text(
        if (lembar > 1) {
            stringResource(R.string.settings_data_size_sheets, formatBytes(total), lembar)
        } else {
            stringResource(R.string.settings_data_size, formatBytes(total))
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Memilih halaman PDF mana yang dicetak.
 *
 * Tanpa ini, satu halaman dari PDF empat puluh halaman berarti mencetak
 * keempat puluhnya. Itu pemborosan yang terjadi setiap kali, bukan sesekali.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageSelectionRow(state: UiState, viewModel: PrintViewModel, pageCount: Int) {
    val selection = state.pageSelection
    val enabled = !state.busy

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selection.mode == PageSelectionMode.ALL,
                onClick = { viewModel.updatePageSelection { it.copy(mode = PageSelectionMode.ALL) } },
                enabled = enabled,
                label = { Text(stringResource(R.string.pages_all, pageCount)) },
            )
            FilterChip(
                selected = selection.mode == PageSelectionMode.CURRENT,
                onClick = {
                    viewModel.updatePageSelection { it.copy(mode = PageSelectionMode.CURRENT) }
                },
                enabled = enabled,
                label = { Text(stringResource(R.string.pages_current)) },
            )
            FilterChip(
                selected = selection.mode == PageSelectionMode.RANGE,
                onClick = {
                    viewModel.updatePageSelection {
                        it.copy(
                            mode = PageSelectionMode.RANGE,
                            fromPage = it.fromPage.coerceIn(1, pageCount),
                            toPage = it.toPage.coerceIn(1, pageCount),
                        )
                    }
                },
                enabled = enabled,
                label = { Text(stringResource(R.string.pages_range)) },
            )
        }

        if (selection.mode == PageSelectionMode.RANGE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pages_from), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                Stepper(selection.fromPage, 1..pageCount, enabled) { value ->
                    viewModel.updatePageSelection { it.copy(fromPage = value) }
                }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.pages_to), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                Stepper(selection.toPage, 1..pageCount, enabled) { value ->
                    viewModel.updatePageSelection { it.copy(toPage = value) }
                }
            }
        }

        val count = state.pagesToPrint.size
        Text(
            stringResource(R.string.pages_count_to_print, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Peringatan bagian yang akan terpotong.
 *
 * Menyebut jaraknya dalam milimeter, karena "gambar terpotong" saja tidak
 * memberi tahu pengguna harus menggeser ke mana dan sejauh apa.
 */
@Composable
private fun OverflowWarning(state: UiState) {
    val message = overflowMessage(LocalContext.current, state) ?: return

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    stringResource(R.string.overflow_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    stringResource(R.string.overflow_detail, message.text()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

// ------------------------------------------------------- setelan cetak

/**
 * Seluruh setelan cetak dalam satu kartu.
 *
 * Dulu terpisah jadi dua -- Kertas dan Hasil cetak -- dengan sepuluh baris chip
 * dan lima paragraf penjelasan yang selalu terpampang. Digabung dan dilipat,
 * karena hampir semuanya dipilih sekali lalu tidak disentuh lagi: ukuran
 * kertas, margin, resolusi, jenis kertas, arah cetak.
 *
 * Yang tetap di permukaan hanya yang benar-benar berubah antar-pekerjaan --
 * preset, warna, jumlah salinan -- ditambah satu baris ringkasan supaya
 * setelan yang tersembunyi tidak pernah jadi kejutan.
 */
@Composable
private fun PrintSettingsSection(state: UiState, viewModel: PrintViewModel) {
    val settings = state.settings
    val enabled = !state.busy
    val preset = presetOf(settings)
    var lanjutanTerbuka by remember { mutableStateOf(false) }

    SectionCard(stringResource(R.string.settings_title)) {
        ChipRow<PrintPreset?>(
            PrintPreset.entries,
            preset,
            { pilihan -> pilihan?.let { stringResource(it.label) }.orEmpty() },
            enabled,
        ) { value -> value?.let { pilih -> viewModel.updateSettings { pilih.applyTo(it) } } }

        Text(
            stringResource(preset?.hint ?: R.string.settings_preset_mixed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Label(stringResource(R.string.settings_color))
        ChipRow(ColorMode.entries, settings.colorMode, { stringResource(it.label) }, enabled) { value ->
            viewModel.updateSettings { it.copy(colorMode = value) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_copies), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Stepper(settings.copies, 1..20, enabled) { value ->
                viewModel.updateSettings { it.copy(copies = value) }
            }
        }

        HorizontalDivider()

        // Ringkasan satu baris. Setelan yang dilipat tidak boleh jadi setelan
        // yang tersembunyi -- orang harus tetap bisa melihat apa yang berlaku
        // tanpa membuka apa pun.
        Text(
            stringResource(
                R.string.settings_summary,
                stringResource(settings.paper.shortLabel),
                settings.dpi.label,
                stringResource(settings.mediaType.label).lowercase(),
                fmtMm(settings.marginMm),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DataSizeNote(state)

        Row(
            Modifier
                .fillMaxWidth()
                .clickable { lanjutanTerbuka = !lanjutanTerbuka },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_advanced),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(
                    if (lanjutanTerbuka) R.string.common_close else R.string.common_open
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (lanjutanTerbuka) {
            Label(stringResource(R.string.settings_paper))
            ChipRow(
                PaperSize.entries,
                settings.paper,
                { stringResource(it.shortLabel) },
                enabled,
            ) { value ->
                viewModel.updateSettings { it.copy(paper = value) }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_margin), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.settings_margin_mm, fmtMm(settings.marginMm)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = settings.marginMm,
                onValueChange = { value ->
                    viewModel.updateSettings { it.copy(marginMm = (value * 2).roundToInt() / 2f) }
                },
                valueRange = 0f..20f,
                steps = 39,
                enabled = enabled,
                modifier = run {
                    val nama = stringResource(R.string.settings_margin_desc)
                    val nilai = stringResource(
                        R.string.settings_margin_state,
                        fmtMm(settings.marginMm),
                    )
                    Modifier.semantics {
                        contentDescription = nama
                        stateDescription = nilai
                    }
                },
            )
            Text(
                stringResource(R.string.settings_margin_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Label(stringResource(R.string.settings_quality))
            ChipRow(
                Quality.entries,
                settings.quality,
                { stringResource(it.shortLabel) },
                enabled,
            ) { value ->
                viewModel.updateSettings { it.copy(quality = value) }
            }

            Label(stringResource(R.string.settings_resolution))
            ChipRow(Dpi.entries, settings.dpi, { it.label }, enabled) { value ->
                viewModel.updateSettings { it.copy(dpi = value) }
            }

            Label(stringResource(R.string.settings_media))
            ChipRow(
                MediaType.entries,
                settings.mediaType,
                { stringResource(it.label) },
                enabled,
            ) { value ->
                viewModel.updateSettings { it.copy(mediaType = value) }
            }
            Text(
                stringResource(R.string.settings_media_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Label(stringResource(R.string.settings_direction))
            ChipRow(
                PrintDirection.entries,
                settings.direction,
                { arah -> stringResource(arah.label) },
                enabled,
            ) { arah -> viewModel.updateSettings { it.copy(direction = arah) } }
        }
    }
}

// ------------------------------------------------- status sambungan

/**
 * Daftar periksa sambungan, ditaruh paling atas.
 *
 * Saat semuanya beres panel menciut jadi satu baris supaya tidak memakan
 * ruang; begitu ada langkah yang menghambat, panel terbuka sendiri dan
 * menonjolkan satu tombol untuk langkah itu saja.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionCard(state: UiState, viewModel: PrintViewModel) {
    val connection = state.connection
    var manuallyExpanded by remember { mutableStateOf(false) }
    val expanded = !connection.ready || manuallyExpanded

    val onAction: (StepAction) -> Unit = { action ->
        when (action) {
            StepAction.REFRESH -> viewModel.refreshDevices()
            StepAction.REQUEST_PERMISSION -> viewModel.requestPermission()
            StepAction.CHECK_SUPPORT -> viewModel.readDeviceIdentity()
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connection.ready) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            }
        )
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepIcon(if (connection.ready) StepState.OK else StepState.ACTION_NEEDED)
                Spacer(Modifier.width(10.dp))
                Text(
                    connection.headline.text(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (connection.ready) {
                    TextButton(onClick = { manuallyExpanded = !manuallyExpanded }) {
                        Text(
                            stringResource(
                                if (expanded) R.string.common_close else R.string.conn_details
                            )
                        )
                    }
                }
            }

            // Bukan AnimatedVisibility: keterbukaan panel ini bergantung pada
            // state yang bisa berubah dari utas latar (perangkat dicabut saat
            // mencetak), dan animasi yang dipicu dari sana menyentuh view di
            // utas yang salah.
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    connection.steps.forEach { step -> StepRow(step, onAction) }

                    // Pemilih perangkat hanya relevan kalau memang ada lebih dari satu.
                    if (state.devices.size > 1) {
                        Spacer(Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.devices.forEach { candidate ->
                                FilterChip(
                                    selected = state.selectedDevice?.deviceName == candidate.deviceName,
                                    onClick = { viewModel.selectDevice(candidate) },
                                    label = {
                                        Text(
                                            candidate.productName
                                                ?: "%04X:%04X".format(
                                                    candidate.vendorId, candidate.productId
                                                )
                                        )
                                    }
                                )
                            }
                        }
                    }

                }
            }
        }
    }
}

@Composable
private fun StepRow(step: ConnectionStep, onAction: (StepAction) -> Unit) {
    val dim = step.state == StepState.WAITING
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepIcon(step.state)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                step.title.text(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
            step.detail?.let { detail ->
                Text(
                    detail.text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        step.action?.let { action ->
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onAction(action) }) { Text(stringResource(action.label)) }
        }
    }
}

@Composable
private fun StepIcon(state: StepState) {
    when (state) {
        StepState.OK -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = stringResource(R.string.step_icon_done),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        StepState.ACTION_NEEDED, StepState.IMPOSSIBLE -> Icon(
            Icons.Default.Warning,
            contentDescription = stringResource(R.string.step_icon_action),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        // Lingkaran kosong: langkah ini menunggu langkah sebelumnya.
        StepState.WAITING -> Box(
            Modifier
                .size(20.dp)
                .padding(3.dp)
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
        )
    }
}

// ------------------------------------------------------------- catatan

@Composable
private fun LogSection(state: UiState, onExport: () -> Unit) {
    // Dulu kartunya hilang sepenuhnya saat catatan kosong. Sekarang ia juga
    // menampung ekspor .prn, jadi ia tetap ada selama masih ada yang bisa
    // diekspor -- kalau tidak, tombolnya lenyap justru ketika dibutuhkan.
    if (state.log.isEmpty() && !state.hasContent) return
    var expanded by remember { mutableStateOf(false) }

    SectionCard(stringResource(R.string.log_title)) {
        AnimatedVisibility(expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                state.log.forEach { entry ->
                    Text(
                        entry,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        // lastOrNull, bukan last: sejak kartu ini ikut menampung ekspor .prn,
        // ia bisa muncul sebelum ada satu pun catatan tertulis.
        if (!expanded) {
            state.log.lastOrNull()?.let { terakhir ->
                Text(
                    terakhir,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (state.log.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) stringResource(R.string.log_hide)
                    else stringResource(R.string.log_show_all, state.log.size)
                )
            }
        }

        if (state.hasContent) {
            HorizontalDivider()
            Text(
                stringResource(R.string.log_prn_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onExport, enabled = !state.busy) {
                Text(stringResource(R.string.log_save_prn))
            }
        }
    }
}

// ------------------------------------------------------------ bilah bawah

@Composable
private fun PrintBar(
    state: UiState,
    viewModel: PrintViewModel,
    onPickFile: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        // enableEdgeToEdge membuat aplikasi menggambar sampai ke belakang bilah
        // sistem, dan slot bottomBar tidak menerima inset itu sendiri. Tanpa
        // navigationBarsPadding, tombol Cetak berada persis di bawah tombol
        // navigasi dan hampir tidak bisa disentuh.
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Hasil dan kesalahan muncul di sini, menempel pada tombol Cetak --
            // bukan di kartu Catatan yang letaknya jauh di bawah dan harus
            // digulir untuk ditemukan.
            OutcomeCard(state, viewModel, onPickFile)
            // Kemajuan hanya berarti untuk pencetakan. Membaca sisa tinta
            // memang menyibukkan aplikasi, tapi menampilkan "Mengirim 0%"
            // untuk itu membuat orang mengira cetakan sudah mulai.
            if (state.busy && state.busyReason == BusyReason.PRINTING) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.bar_sending, (state.progress * 100).roundToInt()),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
            } else if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.bar_contacting),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.busy && state.busyReason == BusyReason.PRINTING) {
                    OutlinedButton(onClick = viewModel::cancel) {
                        Text(stringResource(R.string.bar_cancel))
                    }
                } else if (state.busy) {
                    // Menunggu printer selesai bisa berlangsung sampai dua
                    // setengah menit pada printer yang tidak pernah menjawab.
                    // Tombolnya tidak membatalkan perintah yang sudah terkirim
                    // -- itu sudah di tangan printer -- hanya berhenti menunggu.
                    OutlinedButton(onClick = viewModel::cancel) {
                        Text(stringResource(R.string.bar_stop_waiting))
                    }
                }
                // Simpan .prn pindah ke kartu Catatan. Ia alat penelusuran,
                // bukan tindakan sehari-hari, dan sebelumnya menempati separuh
                // bilah bawah -- ruang paling berharga di layar -- bersaing
                // perhatian dengan satu-satunya tombol yang benar-benar dicari
                // orang saat membuka aplikasi ini.
                Button(
                    onClick = viewModel::print,
                    enabled = state.canPrint,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) {
                    Text(
                        if (state.settings.copies > 1) {
                            stringResource(R.string.bar_print_copies, state.settings.copies)
                        } else {
                            stringResource(R.string.bar_print)
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------- komponen kecil

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

@Composable
internal fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Satu baris pilihan berbentuk chip.
 *
 * Sempat memakai SegmentedButton di sini, tapi pada pengujian sentuhannya tidak
 * pernah sampai ke onClick padahal batas node-nya benar, sementara FilterChip
 * bekerja normal. Karena memilih warna dan kualitas itu fungsi pokok, dipakai
 * komponen yang terbukti bisa disentuh -- sekalian membuat semua kelompok
 * pilihan di layar ini tampil seragam.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChipRow(
    options: List<T>,
    selected: T,
    // @Composable supaya pemanggil boleh memakai stringResource di dalamnya.
    // Tanpa itu setiap pemanggil harus menyiapkan teksnya lebih dulu di luar,
    // dan daftar pilihan yang panjang jadi dua daftar yang harus sejalan.
    labelOf: @Composable (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled,
                label = { Text(labelOf(option)) }
            )
        }
    }
}

/** contentDescription butuh resource, dan resource butuh konteks composable. */
@Composable
internal fun stepperSemantics(@StringRes id: Int): Modifier {
    val nama = stringResource(id)
    return Modifier.semantics { contentDescription = nama }
}

@Composable
private fun Stepper(value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = { onChange((value - 1).coerceIn(range)) },
            enabled = enabled && value > range.first,
            label = { Text("-") },
            modifier = stepperSemantics(R.string.stepper_decrease)
        )
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        AssistChip(
            onClick = { onChange((value + 1).coerceIn(range)) },
            enabled = enabled && value < range.last,
            label = { Text("+") },
            modifier = stepperSemantics(R.string.stepper_increase)
        )
    }
}

/**
 * Menyerahkan berkas ke aplikasi lain yang sanggup membukanya.
 *
 * Dipakai untuk berkas Office, yang tidak bisa dicetak Printigo tetapi bisa
 * diubah jadi PDF oleh aplikasi pembaca dokumen di HP yang sama. Izin baca
 * diteruskan lewat FLAG_GRANT_READ_URI_PERMISSION -- tanpa itu aplikasi tujuan
 * menerima URI yang tidak boleh ia buka.
 *
 * Kalau tidak ada satu pun aplikasi yang bisa menanganinya, Android melempar
 * ActivityNotFoundException. Itu ditangkap dan dibiarkan diam: kartunya masih
 * di layar dengan kalimat yang menjelaskan keadaannya, dan aplikasi yang mati
 * di titik ini jauh lebih buruk daripada tombol yang tidak melakukan apa-apa.
 */
private fun openElsewhere(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.chooser_open_with))
        )
    }
}

/**
 * Kartu hasil percobaan terakhir: satu kalimat sebab, satu tombol tindakan.
 */
@Composable
private fun OutcomeCard(
    state: UiState,
    viewModel: PrintViewModel,
    onPickFile: () -> Unit,
) {
    val outcome = state.outcome
    if (outcome is PrintOutcome.None) return

    val context = LocalContext.current

    val scheme = MaterialTheme.colorScheme
    val failed = outcome is PrintOutcome.Failed
    val advice = (outcome as? PrintOutcome.Failed)?.let { adviceFor(it.kind) }

    val title = when (outcome) {
        is PrintOutcome.Failed -> stringResource(advice!!.title)
        is PrintOutcome.Success -> stringResource(
            R.string.outcome_success, outcome.sheets, outcome.seconds.roundToInt()
        )
        is PrintOutcome.Saved -> stringResource(R.string.outcome_saved)
        is PrintOutcome.MaintenanceSent ->
            stringResource(R.string.outcome_maint_sent, stringResource(outcome.task.label))
        PrintOutcome.Cancelled -> stringResource(R.string.outcome_cancelled)
        PrintOutcome.None -> ""
    }
    val hint = when (outcome) {
        is PrintOutcome.Failed -> stringResource(advice!!.hint)
        PrintOutcome.Cancelled -> stringResource(R.string.outcome_cancelled_hint)
        is PrintOutcome.Success -> stringResource(R.string.outcome_success_hint)
        is PrintOutcome.MaintenanceSent -> stringResource(
            if (outcome.task.usesPaper) R.string.outcome_maint_paper_hint
            else R.string.outcome_maint_ink_hint
        )
        else -> null
    }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) scheme.errorContainer else scheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = if (failed) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (failed) scheme.onErrorContainer else scheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (failed) scheme.onErrorContainer else scheme.onSecondaryContainer
                    )
                    hint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failed) scheme.onErrorContainer
                            else scheme.onSecondaryContainer
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                advice?.action?.let { action ->
                    Button(onClick = {
                        viewModel.dismissOutcome()
                        when (action) {
                            OutcomeAction.RETRY -> viewModel.print()
                            OutcomeAction.REFRESH -> viewModel.refreshDevices()
                            OutcomeAction.REQUEST_PERMISSION -> viewModel.requestPermission()
                            OutcomeAction.PICK_FILE -> onPickFile()
                            OutcomeAction.OPEN_ELSEWHERE ->
                                state.rejectedUri?.let { openElsewhere(context, it) }
                                    ?: onPickFile()
                        }
                    }) { Text(stringResource(action.label)) }
                }
                TextButton(onClick = viewModel::dismissOutcome) {
                    Text(stringResource(R.string.outcome_close))
                }
            }
        }
    }
}

