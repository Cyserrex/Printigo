package com.escpr.usbprint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.escpr.usbprint.escpr.ColorMode
import com.escpr.usbprint.layout.PageLayout
import kotlin.math.roundToInt

/**
 * Layar penuh untuk mengatur tata letak.
 *
 * Dipisahkan dari halaman utama karena mengatur posisi dan ukuran dengan jari
 * butuh kertas sebesar mungkin: di kartu kecil, penggaris milimeter tidak
 * terbaca dan geseran satu milimeter tidak terlihat bedanya.
 *
 * Selain gestur, disediakan tombol perbesar/perkecil bertahap. Cubitan bagus
 * untuk perubahan besar, tapi untuk menyetel beberapa persen tombol lebih tepat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutEditorDialog(
    state: UiState,
    viewModel: PrintViewModel,
) {
    if (!state.layoutEditorOpen) return

    Dialog(
        onDismissRequest = viewModel::closeLayoutEditor,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LayoutEditorContent(state, viewModel)
    }
}

/**
 * Isi editor, terpisah dari pembungkus [Dialog].
 *
 * Pemisahan ini bukan gaya semata: jendela dialog punya decorView sendiri, dan
 * harness pengujian hanya bisa menggambar decorView milik activity. Dengan
 * dipisah, isinya bisa dirender dan ditangkap layarnya tanpa perangkat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayoutEditorContent(
    state: UiState,
    viewModel: PrintViewModel,
) {
    val layout = state.pageLayout
    val settings = state.settings

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text("Atur tata letak", fontWeight = FontWeight.SemiBold)
                        Text(
                            settings.paper.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeLayoutEditor) {
                        Icon(Icons.Default.Clear, contentDescription = "Tutup")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::closeLayoutEditor) { Text("Selesai") }
                },
            )

            // Kertas mendapat semua ruang yang tersisa.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                PagePreview(
                    paperWidthMm = settings.paper.widthMm,
                    paperHeightMm = settings.paper.heightMm,
                    printable = state.printableRect,
                    items = state.previewItems,
                    monochrome = settings.colorMode == ColorMode.MONO,
                    interactive = true,
                    onTapMm = viewModel::selectPhotoAt,
                    onGesture = viewModel::nudgePlacement,
                    onReset = viewModel::resetPlacement,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            EditorControls(state, viewModel, layout)
        }
    }
}

@Composable
private fun EditorControls(
    state: UiState,
    viewModel: PrintViewModel,
    layout: PageLayout,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            overflowMessage(state)?.let { message -> OverflowNotice(message) }

            if (state.sheetMode) {
                SheetControls(state, viewModel)
            } else {
                Text(
                    "Margin  kiri " + fmtMm(layout.marginLeftMm) +
                        "  atas " + fmtMm(layout.marginTopMm) +
                        "  kanan " + fmtMm(layout.marginRightMm) +
                        "  bawah " + fmtMm(layout.marginBottomMm) + " mm",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Ukuran isi di atas kertas, bukan persentase abstrak: angka dalam
            // milimeter bisa langsung dicocokkan dengan penggaris.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.sheetMode) "Foto terpilih" else "Ukuran",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(12.dp))
                val sized = if (state.sheetMode) state.selectedPhoto?.item?.rect else layout.content
                Text(
                    if (sized == null) "-"
                    else fmtMm(sized.width) + " x " + fmtMm(sized.height) + " mm",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = { viewModel.nudgePlacement(0f, 0f, 1f / ZOOM_STEP) }
                ) { Text("-", style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = { viewModel.nudgePlacement(0f, 0f, ZOOM_STEP) }
                ) { Icon(Icons.Default.Add, contentDescription = "Perbesar") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Batas cetak", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    fmtMm(state.settings.marginMm) + " mm",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = state.settings.marginMm,
                onValueChange = { value ->
                    viewModel.updateSettings {
                        it.copy(marginMm = (value * 2).roundToInt() / 2f)
                    }
                },
                valueRange = 0f..20f,
                steps = 39,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::resetPlacement) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Atur ulang")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Geser, cubit, atau ketuk dua kali",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Peringatan ringkas di dalam editor; versi lengkapnya ada di halaman utama. */
@Composable
private fun OverflowNotice(message: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * Kontrol khusus mode lembar: menyusun ulang ke kisi dan menghapus foto.
 *
 * Tombol kisi memakai jumlah kolom, bukan "2 per lembar", karena yang
 * menentukan bentuk susunan memang kolomnya; jumlah barisnya mengikuti.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetControls(state: UiState, viewModel: PrintViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            state.photos.size.toString() + " foto di lembar ini" +
                (if (state.selectedPhoto != null) " - satu terpilih" else ""),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { viewModel.arrangeGrid(0) },
                label = { Text("Susun otomatis") },
            )
            listOf(1, 2, 3, 4).forEach { columns ->
                AssistChip(
                    onClick = { viewModel.arrangeGrid(columns) },
                    label = { Text(columns.toString() + " kolom") },
                )
            }
            AssistChip(
                onClick = viewModel::removeSelectedPhoto,
                enabled = state.selectedPhoto != null,
                label = { Text("Hapus terpilih") },
            )
        }
    }
}

/** Satu langkah perbesaran tombol. 5% cukup halus untuk menyetel, tidak lambat. */
private const val ZOOM_STEP = 1.05f

/**
 * Satu kalimat tentang apa yang akan terpotong, atau null kalau semuanya aman.
 *
 * Mode dokumen menyebut jarak per sisi; mode lembar menyebut berapa foto yang
 * kena dan yang terjauh, karena mendaftar empat sisi untuk enam foto sekaligus
 * justru tidak terbaca.
 */
internal fun overflowMessage(state: UiState): String? {
    if (state.sheetMode) {
        val layout = state.sheetLayout
        val clipped = layout.clipped
        if (clipped.isEmpty()) return null
        val worst = clipped.maxOf { layout.overflowOf(it).worstMm }
        return clipped.size.toString() + " foto keluar area cetak, terjauh " +
            fmtMm(worst) + " mm"
    }

    val layout = state.pageLayout
    if (!layout.hasOverflow) return null
    val tolerance = PageLayout.TOLERANCE_MM
    val sides = buildList {
        if (layout.overflowLeftMm > tolerance) add("kiri " + fmtMm(layout.overflowLeftMm))
        if (layout.overflowTopMm > tolerance) add("atas " + fmtMm(layout.overflowTopMm))
        if (layout.overflowRightMm > tolerance) add("kanan " + fmtMm(layout.overflowRightMm))
        if (layout.overflowBottomMm > tolerance) add("bawah " + fmtMm(layout.overflowBottomMm))
    }
    return "Keluar dari area cetak: " + sides.joinToString("  ") + " mm"
}

/** 3.0 -> "3", 2.54 -> "2,5" */
internal fun fmtMm(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString()
    else "%.1f".format(rounded)
}
