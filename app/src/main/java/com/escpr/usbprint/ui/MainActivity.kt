package com.escpr.usbprint.ui

import android.app.Activity
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
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
import com.escpr.usbprint.escpr.ColorMode
import com.escpr.usbprint.escpr.Dpi
import com.escpr.usbprint.escpr.MaintenanceTask
import com.escpr.usbprint.escpr.MediaType
import com.escpr.usbprint.escpr.PaperSize
import com.escpr.usbprint.escpr.Quality
import com.escpr.usbprint.layout.PageLayout
import com.escpr.usbprint.print.PageSelectionMode
import com.escpr.usbprint.ui.theme.AppTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: PrintViewModel by viewModels()

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
                        Text("USB Printer OTG", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Epson ESC/P-R  -  v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshDevices) {
                        Icon(Icons.Default.Refresh, contentDescription = "Cari printer")
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
            PaperSection(state, viewModel)
            OutputSection(state, viewModel)
            MaintenanceSection(state, viewModel)
            LogSection(state)
            Spacer(Modifier.height(8.dp))
        }
    }

    LayoutEditorDialog(state, viewModel)
    MaintenanceConfirmDialog(state, viewModel)
}

// ----------------------------------------------------------- perawatan

/**
 * Cek nozzle dan pembersihan head.
 *
 * L3110 tidak punya layar maupun menu, jadi tanpa komputer pemiliknya tidak
 * punya cara sama sekali membersihkan head yang mampet. Dua tombol ini menutup
 * lubang itu.
 *
 * Ditaruh paling bawah dan tidak menonjol dengan sengaja: ini bukan yang
 * dikerjakan orang setiap hari, dan pembersihan head memakai tinta.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaintenanceSection(state: UiState, viewModel: PrintViewModel) {
    // Tanpa printer yang tersambung dan berizin, tombolnya hanya menipu.
    if (state.selectedDevice == null || !state.hasPermission) return

    var expanded by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Perawatan printer", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "Tutup" else "Buka",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (expanded) {
                Text(
                    "Hasil cetak bergaris atau warna hilang biasanya berarti " +
                        "nozzle mampet. Cek dulu, baru bersihkan kalau memang perlu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaintenanceTask.entries.forEach { task ->
                        OutlinedButton(
                            onClick = { viewModel.askMaintenance(task) },
                            enabled = !state.busy,
                        ) { Text(task.label) }
                    }
                }
                Text(
                    "Perintah ini mengikuti driver Epson terbuka dan belum " +
                        "diuji pada L3110. Kalau printer tidak bereaksi sama " +
                        "sekali, bentuk perintahnya perlu disesuaikan.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Persetujuan sebelum perawatan berjalan.
 *
 * Keduanya memakai sesuatu yang tidak kembali -- selembar kertas atau sejumlah
 * tinta -- dan tidak bisa dihentikan setelah printer mulai bergerak.
 */
@Composable
private fun MaintenanceConfirmDialog(state: UiState, viewModel: PrintViewModel) {
    val task = state.maintenanceAsked ?: return

    AlertDialog(
        onDismissRequest = viewModel::dismissMaintenance,
        title = { Text(task.label) },
        text = { Text(task.confirmation) },
        confirmButton = {
            TextButton(onClick = { viewModel.runMaintenance(task) }) { Text("Jalankan") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissMaintenance) { Text("Batal") }
        },
    )
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
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
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
                    Text("Atur tata letak")
                }

                if (state.sheetMode) {
                    Text(
                        state.photos.size.toString() + " foto dalam satu lembar",
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        "Margin  kiri " + fmtMm(layout.marginLeftMm) +
                            "  atas " + fmtMm(layout.marginTopMm) +
                            "  kanan " + fmtMm(layout.marginRightMm) +
                            "  bawah " + fmtMm(layout.marginBottomMm) + " mm",
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
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Halaman sebelumnya") }
                    Text(
                        "Halaman " + (state.previewPage + 1) + " / " + document.pageCount,
                        style = MaterialTheme.typography.labelLarge
                    )
                    IconButton(
                        onClick = { viewModel.showPage(state.previewPage + 1) },
                        enabled = state.previewPage < document.pageCount - 1
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Halaman berikutnya") }
                }
            }

            val (width, height) = state.printableSize
            Text(
                text = if (document == null) {
                    "Pilih gambar atau PDF untuk melihat pratinjaunya"
                } else {
                    "Area cetak " + width + " x " + height + " piksel  -  " +
                        settings.dpi.value + " dpi"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPick, enabled = !state.busy) {
                    Text(if (state.hasContent) "Ganti berkas" else "Pilih berkas")
                }
                if (state.sheetMode) {
                    OutlinedButton(onClick = onAddPhotos, enabled = !state.busy) {
                        Text("Tambah foto")
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
                label = { Text("Semua " + pageCount + " halaman") },
            )
            FilterChip(
                selected = selection.mode == PageSelectionMode.CURRENT,
                onClick = {
                    viewModel.updatePageSelection { it.copy(mode = PageSelectionMode.CURRENT) }
                },
                enabled = enabled,
                label = { Text("Halaman ini") },
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
                label = { Text("Rentang") },
            )
        }

        if (selection.mode == PageSelectionMode.RANGE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dari", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                Stepper(selection.fromPage, 1..pageCount, enabled) { value ->
                    viewModel.updatePageSelection { it.copy(fromPage = value) }
                }
                Spacer(Modifier.width(12.dp))
                Text("sampai", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                Stepper(selection.toPage, 1..pageCount, enabled) { value ->
                    viewModel.updatePageSelection { it.copy(toPage = value) }
                }
            }
        }

        val count = state.pagesToPrint.size
        Text(
            count.toString() + " halaman akan dicetak",
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
    val message = overflowMessage(state) ?: return

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
                    "Sebagian gambar akan terpotong",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    message + ". Bagian merah tidak akan tercetak.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

// -------------------------------------------------------------- kertas

@Composable
private fun PaperSection(state: UiState, viewModel: PrintViewModel) {
    val settings = state.settings
    val enabled = !state.busy

    SectionCard("Kertas") {
        ChipRow(PaperSize.entries, settings.paper, { it.shortLabel }, enabled) { value ->
            viewModel.updateSettings { it.copy(paper = value) }
        }

        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Margin", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                "${fmtMm(settings.marginMm)} mm",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        // Nilainya diteruskan langsung ke state, jadi pratinjau ikut bergerak
        // selama slider digeser.
        Slider(
            value = settings.marginMm,
            onValueChange = { value ->
                viewModel.updateSettings { it.copy(marginMm = (value * 2).roundToInt() / 2f) }
            },
            valueRange = 0f..20f,
            steps = 39,
            enabled = enabled,
            // Tanpa ini pembaca layar hanya menyebut "penggeser 15 persen",
            // angka yang tidak berarti apa-apa bagi pengguna.
            modifier = Modifier.semantics {
                contentDescription = "Batas cetak"
                stateDescription = fmtMm(settings.marginMm) + " milimeter"
            }
        )
        Text(
            "Epson L3110 tidak bisa mencetak tanpa batas. Margin di bawah 3 mm " +
                "berisiko terpotong di tepi kertas.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ------------------------------------------------------------- keluaran

@Composable
private fun OutputSection(state: UiState, viewModel: PrintViewModel) {
    val settings = state.settings
    val enabled = !state.busy

    SectionCard("Hasil cetak") {
        Label("Warna")
        ChipRow(ColorMode.entries, settings.colorMode, { it.label }, enabled) { value ->
            viewModel.updateSettings { it.copy(colorMode = value) }
        }

        Label("Kualitas")
        ChipRow(Quality.entries, settings.quality, { it.shortLabel }, enabled) { value ->
            viewModel.updateSettings { it.copy(quality = value) }
        }

        Label("Resolusi")
        ChipRow(Dpi.entries, settings.dpi, { it.label }, enabled) { value ->
            viewModel.updateSettings { it.copy(dpi = value) }
        }

        Label("Jenis kertas")
        ChipRow(MediaType.entries, settings.mediaType, { it.label }, enabled) { value ->
            viewModel.updateSettings { it.copy(mediaType = value) }
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Salinan", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Stepper(
                value = settings.copies,
                range = 1..20,
                enabled = enabled
            ) { value -> viewModel.updateSettings { it.copy(copies = value) } }
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
                    connection.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (connection.ready) {
                    TextButton(onClick = { manuallyExpanded = !manuallyExpanded }) {
                        Text(if (expanded) "Tutup" else "Rincian")
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
                                            candidate.productName ?: "%04X:%04X".format(
                                                candidate.vendorId, candidate.productId
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }

                    state.deviceId?.let { id ->
                        Text(
                            id,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
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
                step.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
            step.detail?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        step.action?.let { action ->
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onAction(action) }) { Text(action.label) }
        }
    }
}

@Composable
private fun StepIcon(state: StepState) {
    when (state) {
        StepState.OK -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = "selesai",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        StepState.ACTION_NEEDED, StepState.IMPOSSIBLE -> Icon(
            Icons.Default.Warning,
            contentDescription = "perlu tindakan",
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
private fun LogSection(state: UiState) {
    if (state.log.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    SectionCard("Catatan") {
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
        if (!expanded) {
            Text(
                state.log.last(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Sembunyikan" else "Lihat semua (${state.log.size})")
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
            OutcomeCard(state.outcome, viewModel, onPickFile)
            if (state.busy) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Mengirim ${(state.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.busy) {
                    OutlinedButton(onClick = viewModel::cancel) { Text("Batalkan") }
                } else {
                    OutlinedButton(
                        onClick = onExport,
                        enabled = state.document != null
                    ) { Text("Simpan .prn") }
                }
                Button(
                    onClick = viewModel::print,
                    enabled = state.canPrint,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) {
                    Text(
                        if (state.settings.copies > 1) "Cetak ${state.settings.copies} salinan"
                        else "Cetak",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------- komponen kecil

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun Label(text: String) {
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
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
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

@Composable
private fun Stepper(value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = { onChange((value - 1).coerceIn(range)) },
            enabled = enabled && value > range.first,
            label = { Text("-") },
            modifier = Modifier.semantics { contentDescription = "Kurangi" }
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
            modifier = Modifier.semantics { contentDescription = "Tambah" }
        )
    }
}

/**
 * Kartu hasil percobaan terakhir: satu kalimat sebab, satu tombol tindakan.
 */
@Composable
private fun OutcomeCard(
    outcome: PrintOutcome,
    viewModel: PrintViewModel,
    onPickFile: () -> Unit,
) {
    if (outcome is PrintOutcome.None) return

    val scheme = MaterialTheme.colorScheme
    val failed = outcome is PrintOutcome.Failed
    val advice = (outcome as? PrintOutcome.Failed)?.let { adviceFor(it.kind) }

    val title = when (outcome) {
        is PrintOutcome.Failed -> advice!!.title
        is PrintOutcome.Success ->
            "Selesai. ${outcome.sheets} lembar terkirim dalam " +
                "${outcome.seconds.roundToInt()} detik."
        is PrintOutcome.Saved -> "Tersimpan sebagai berkas .prn."
        is PrintOutcome.MaintenanceSent -> outcome.task.label + " sudah dikirim."
        PrintOutcome.Cancelled -> "Cetak dibatalkan."
        PrintOutcome.None -> ""
    }
    val hint = when (outcome) {
        is PrintOutcome.Failed -> advice!!.hint
        PrintOutcome.Cancelled ->
            "Halaman yang sudah masuk ke printer tetap akan keluar."
        is PrintOutcome.Success ->
            "Printer mungkin masih menyelesaikan lembar terakhir."
        is PrintOutcome.MaintenanceSent ->
            if (outcome.task.usesPaper) "Lihat hasilnya di kertas yang keluar."
            else "Printer akan sibuk sekitar satu menit. Tunggu sampai lampunya tenang."
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
                        }
                    }) { Text(action.label) }
                }
                TextButton(onClick = viewModel::dismissOutcome) { Text("Tutup") }
            }
        }
    }
}

