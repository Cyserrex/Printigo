package com.escpr.usbprint.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.escpr.usbprint.R
import com.escpr.usbprint.util.UiText
import com.escpr.usbprint.util.text
import com.escpr.usbprint.util.uiText
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
                        Text(stringResource(R.string.editor_title), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(settings.paper.label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeLayoutEditor) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.common_close))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::closeLayoutEditor) {
                        Text(stringResource(R.string.editor_done))
                    }
                },
            )

            // Kertas dan bilah kontrol dibagi menurut bobot tetap, bukan
            // menurut isinya. Sebelumnya kertas mengambil "sisa ruang", jadi
            // ketika peringatan terpotong muncul atau baris margin melipat jadi
            // dua, kertas ikut menyusut. Itu bukan sekadar kedip: skala kertas
            // berubah di tengah gestur, gambar melompat, dan geseran terasa
            // patah-patah.
            Box(
                Modifier
                    .weight(PREVIEW_WEIGHT)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(PREVIEW_TEST_TAG),
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

            EditorControls(state, viewModel, layout, Modifier.weight(CONTROLS_WEIGHT))
        }
    }
}

@Composable
private fun EditorControls(
    state: UiState,
    viewModel: PrintViewModel,
    layout: PageLayout,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, tonalElevation = 3.dp) {
        // Tingginya sudah dipatok dari luar, jadi isi yang tidak muat digulir
        // -- bukan mendorong kertas mengecil.
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            overflowMessage(LocalContext.current, state)
                ?.let { message -> OverflowNotice(message) }

            if (state.sheetMode) {
                SheetControls(state, viewModel)
            } else {
                Text(
                    stringResource(
                        R.string.preview_margins,
                        fmtMm(layout.marginLeftMm),
                        fmtMm(layout.marginTopMm),
                        fmtMm(layout.marginRightMm),
                        fmtMm(layout.marginBottomMm),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Ukuran isi di atas kertas, bukan persentase abstrak: angka dalam
            // milimeter bisa langsung dicocokkan dengan penggaris.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        if (state.sheetMode) R.string.editor_selected_photo
                        else R.string.editor_size
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(12.dp))
                val sized = if (state.sheetMode) state.selectedPhoto?.item?.rect else layout.content
                val turn = if (state.sheetMode) state.selectedPhoto?.item?.rotationDegrees ?: 0 else 0
                Text(
                    when {
                        sized == null -> stringResource(R.string.editor_none)
                        turn != 0 -> stringResource(
                            R.string.editor_size_value_rotated,
                            fmtMm(sized.width), fmtMm(sized.height), turn,
                        )
                        else -> stringResource(
                            R.string.editor_size_value,
                            fmtMm(sized.width), fmtMm(sized.height),
                        )
                    },
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
                ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.editor_enlarge)) }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_margin_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.settings_margin_mm, fmtMm(state.settings.marginMm)),
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Di mode lembar tombol ini duplikat "Susun otomatis" di atas,
                // jadi tidak ditampilkan dua kali.
                if (!state.sheetMode) {
                    TextButton(onClick = viewModel::resetPlacement) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.editor_reset))
                    }
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    stringResource(R.string.editor_gesture_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (state.sheetMode) Modifier.fillMaxWidth() else Modifier,
                    textAlign = if (state.sheetMode) TextAlign.Center else null,
                )
            }
        }
    }
}

/** Peringatan ringkas di dalam editor; versi lengkapnya ada di halaman utama. */
@Composable
private fun OverflowNotice(message: UiText) {
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
                message.text(),
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
    val sheets = state.sheetPages.size
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.editor_sheet_photos, state.currentSheetPhotos.size) +
                (if (sheets > 1) {
                    stringResource(R.string.editor_sheet_of, state.currentSheet + 1, sheets)
                } else {
                    ""
                }) +
                (if (state.selectedPhoto != null) {
                    stringResource(R.string.editor_one_selected)
                } else {
                    ""
                }),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Berpindah lembar hanya masuk akal kalau memang ada lebih dari satu.
        if (sheets > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { viewModel.showSheet(state.currentSheet - 1) },
                    enabled = state.currentSheet > 0,
                ) { Text(stringResource(R.string.editor_prev_sheet)) }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { viewModel.showSheet(state.currentSheet + 1) },
                    enabled = state.currentSheet < sheets - 1,
                ) { Text(stringResource(R.string.editor_next_sheet)) }
            }
        }

        // Berapa foto per lembar. "Semua" mempertahankan perilaku lama: satu
        // lembar, sepadat apa pun. Nilai lain membagi foto ke beberapa lembar.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.editor_per_sheet), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 1, 2, 4, 6, 9).forEach { perSheet ->
                    val selected = state.photosPerSheet == perSheet
                    AssistChip(
                        onClick = { viewModel.setPhotosPerSheet(perSheet) },
                        enabled = !selected,
                        label = {
                            Text(
                                if (perSheet == 0) stringResource(R.string.editor_all)
                                else perSheet.toString()
                            )
                        },
                    )
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { viewModel.rotateSelectedPhoto(-1) },
                enabled = state.selectedPhoto != null,
                label = { Text(stringResource(R.string.editor_rotate_left)) },
            )
            AssistChip(
                onClick = { viewModel.rotateSelectedPhoto(1) },
                enabled = state.selectedPhoto != null,
                label = { Text(stringResource(R.string.editor_rotate_right)) },
            )
            AssistChip(
                onClick = { viewModel.arrangeGrid(0) },
                label = { Text(stringResource(R.string.editor_auto_arrange)) },
            )
            listOf(1, 2, 3, 4).forEach { columns ->
                AssistChip(
                    onClick = { viewModel.arrangeGrid(columns) },
                    label = { Text(stringResource(R.string.editor_columns, columns)) },
                )
            }
            AssistChip(
                onClick = viewModel::removeSelectedPhoto,
                enabled = state.selectedPhoto != null,
                label = { Text(stringResource(R.string.editor_delete_selected)) },
            )
        }
    }
}

/** Wadah kertas di editor; dipakai uji untuk memastikan ukurannya tidak berubah. */
internal const val PREVIEW_TEST_TAG = "editorPreview"

/**
 * Pembagian ruang antara kertas dan bilah kontrol.
 *
 * Angkanya tetap dan tidak bergantung pada isi, itulah intinya. Bagian kontrol
 * dibuat cukup lebar untuk keadaan terburuk -- mode lembar dengan peringatan
 * terpotong dan chip yang melipat dua baris -- dan sisanya digulir.
 */
private const val PREVIEW_WEIGHT = 0.60f
private const val CONTROLS_WEIGHT = 0.40f

/** Satu langkah perbesaran tombol. 5% cukup halus untuk menyetel, tidak lambat. */
private const val ZOOM_STEP = 1.05f

/**
 * Satu kalimat tentang apa yang akan terpotong, atau null kalau semuanya aman.
 *
 * Mode dokumen menyebut jarak per sisi; mode lembar menyebut berapa foto yang
 * kena dan yang terjauh, karena mendaftar empat sisi untuk enam foto sekaligus
 * justru tidak terbaca.
 */
internal fun overflowMessage(context: Context, state: UiState): UiText? {
    if (state.sheetMode) {
        val layout = state.sheetLayout
        val clipped = layout.clipped
        if (clipped.isEmpty()) return null
        val worst = clipped.maxOf { layout.overflowOf(it).worstMm }
        return uiText(R.string.overflow_photos, clipped.size, fmtMm(worst))
    }

    val layout = state.pageLayout
    if (!layout.hasOverflow) return null
    val tolerance = PageLayout.TOLERANCE_MM
    val sides = buildList {
        fun sisi(@StringRes id: Int, mm: Float) {
            if (mm > tolerance) add(context.getString(id, fmtMm(mm)))
        }
        sisi(R.string.overflow_side_left, layout.overflowLeftMm)
        sisi(R.string.overflow_side_top, layout.overflowTopMm)
        sisi(R.string.overflow_side_right, layout.overflowRightMm)
        sisi(R.string.overflow_side_bottom, layout.overflowBottomMm)
    }
    return uiText(R.string.overflow_sides, sides.joinToString("  "))
}

/** 3.0 -> "3", 2.54 -> "2,5" */
internal fun fmtMm(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString()
    else "%.1f".format(rounded)
}
