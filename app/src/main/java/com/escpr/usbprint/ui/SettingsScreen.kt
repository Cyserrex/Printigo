package com.escpr.usbprint.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.escpr.usbprint.BuildConfig
import com.escpr.usbprint.R
import com.escpr.usbprint.escpr.Maintenance
import com.escpr.usbprint.escpr.MaintenanceTask
import com.escpr.usbprint.usb.InkLevel
import com.escpr.usbprint.usb.printerStateLabel
import com.escpr.usbprint.util.AppLanguage
import com.escpr.usbprint.util.text

/**
 * Layar setelan, di balik ikon gear.
 *
 * Batasnya satu pertanyaan: apakah ini berubah dari satu pekerjaan cetak ke
 * pekerjaan berikutnya? Kalau ya, tempatnya di halaman utama bersama
 * pratinjau -- ukuran kertas dan margin termasuk, karena keduanya mengubah
 * gambar pratinjau dan memisahkannya berarti menyetel sesuatu tanpa bisa
 * melihat akibatnya. Kalau tidak, tempatnya di sini.
 *
 * Yang lolos saringan itu: bahasa, bentuk perintah perawatan, mengembalikan
 * setelan ke bawaan, perawatan printer, dan keterangan versi. Semuanya dipakai
 * sesekali, dan semuanya sebelumnya menumpang di halaman utama -- termasuk dua
 * paragraf penjelasan yang tidak pernah lagi dibaca setelah kali pertama.
 */
@Composable
internal fun SettingsDialog(
    open: Boolean,
    state: UiState,
    viewModel: PrintViewModel,
    onClose: () -> Unit,
) {
    if (!open) return
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        SettingsContent(state, viewModel, onClose)
    }
}

/**
 * Isi layar setelan, terpisah dari pembungkus [Dialog].
 *
 * Alasannya sama seperti pada editor tata letak: jendela dialog punya
 * decorView sendiri, dan harness pengujian hanya bisa menggambar decorView
 * milik activity. Dipisah supaya bisa dirender dan ditangkap layarnya.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: UiState,
    viewModel: PrintViewModel,
    onClose: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_screen_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = stringResource(R.string.common_close),
                        )
                    }
                },
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LanguageSection(state, viewModel)
                MaintenanceSection(state, viewModel)
                PrintDefaultsSection(state, viewModel)
                AboutSection(state)
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // Persetujuan perawatan ikut di jendela ini, bukan di halaman utama:
    // tombolnya hanya ada di sini, dan dialog di dalam dialog lain adalah
    // susunan yang tidak perlu diuji kalau bisa dihindari.
    MaintenanceConfirmDialog(state, viewModel)
}

/**
 * Pilihan bahasa.
 *
 * Labelnya memuat kedua kata sekaligus -- "Bahasa · Language" -- supaya tetap
 * bisa ditemukan oleh orang yang terlanjur membuka aplikasi dalam bahasa yang
 * tidak ia mengerti. Itu satu-satunya baris di aplikasi ini yang memang harus
 * dwibahasa sekaligus.
 */
@Composable
private fun LanguageSection(state: UiState, viewModel: PrintViewModel) {
    SectionCard(stringResource(R.string.settings_language)) {
        ChipRow(
            AppLanguage.entries,
            state.language,
            { bahasa -> stringResource(bahasa.label) },
            !state.busy,
        ) { bahasa -> viewModel.setLanguage(bahasa) }
    }
}

/** Mengembalikan setelan cetak ke bawaan. */
@Composable
private fun PrintDefaultsSection(state: UiState, viewModel: PrintViewModel) {
    SectionCard(stringResource(R.string.settings_print_defaults)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_defaults),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::resetSettings, enabled = !state.busy) {
                Text(stringResource(R.string.settings_restore))
            }
        }
    }
}

/**
 * Versi dan Device ID.
 *
 * Device ID dulu tampil di kartu sambungan, tempat ia hanya deretan huruf yang
 * tidak berarti bagi siapa pun yang tidak sedang menelusuri masalah. Di sini ia
 * tetap bisa ditemukan ketika memang dibutuhkan -- dan itulah satu-satunya saat
 * ia berguna.
 */
@Composable
private fun AboutSection(state: UiState) {
    SectionCard(stringResource(R.string.about_title)) {
        Text(
            "Printigo v" + BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            state.deviceId ?: stringResource(R.string.about_no_device_id),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ----------------------------------------------------------- perawatan

/**
 * Cek nozzle dan pembersihan head.
 *
 * Printer tanpa panel bermenu -- L3110 salah satunya -- tidak menyediakan cara
 * membersihkan head yang mampet tanpa komputer. Dua tombol ini menutup lubang
 * itu.
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
                Text(stringResource(R.string.maint_title), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(if (expanded) R.string.common_close else R.string.common_open),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (expanded) {
                InkPanel(state, viewModel)
                HorizontalDivider()
                Text(
                    stringResource(R.string.maint_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaintenanceTask.entries.forEach { task ->
                        OutlinedButton(
                            onClick = { viewModel.askMaintenance(task) },
                            enabled = !state.busy,
                        ) { Text(stringResource(task.label)) }
                    }
                }
                HorizontalDivider()
                Text(
                    stringResource(R.string.maint_variant_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                ChipRow(
                    Maintenance.Variant.entries,
                    state.maintenanceVariant,
                    { v ->
                        stringResource(
                            if (v == Maintenance.Variant.EXTENDED) R.string.maint_variant_default
                            else R.string.maint_variant_legacy
                        )
                    },
                    !state.busy,
                ) { v -> viewModel.setMaintenanceVariant(v) }

                Text(
                    stringResource(R.string.maint_variant_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Sisa tinta, dibaca dari printer.
 *
 * Hanya membaca. Angkanya perkiraan printer sendiri: printer tangki tinta tidak
 * punya sensor di dalam tangki dan hanya menghitung berapa tetes yang sudah
 * disemprotkan sejak terakhir kali diberi tahu bahwa tangkinya penuh. Itu disebutkan di
 * layar, bukan disembunyikan -- pengguna yang baru mengisi tangki perlu tahu
 * kenapa angkanya masih rendah.
 */
@Composable
private fun InkPanel(state: UiState, viewModel: PrintViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ink_status_title), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = viewModel::refreshInk, enabled = !state.busy) {
                Text(
                    stringResource(
                        if (state.inkChecked) R.string.ink_check_again else R.string.ink_check
                    )
                )
            }
        }

        if (state.printerState != null) {
            Text(
                stringResource(
                    R.string.ink_printer_reports,
                    stringResource(printerStateLabel(state.printerState)),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        when {
            state.inks.isNotEmpty() -> {
                state.inks.forEach { ink -> InkBar(ink) }
                Text(
                    stringResource(
                        if (state.inks.none { it.measured }) R.string.ink_note_unmeasured
                        else R.string.ink_note_estimate
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Bukan kegagalan membaca. Printer tangki seperti L3110 tidak punya
            // sensor di dalam tangkinya sama sekali, dan Status Monitor bawaan
            // Epson pun hanya menyuruh melihat tangkinya langsung. Mengatakannya
            // begitu lebih menolong daripada menyarankan orang membaca catatan
            // mentah untuk sesuatu yang memang tidak pernah ada.
            state.inkChecked -> Text(
                stringResource(R.string.ink_note_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Text(
                stringResource(R.string.ink_unchecked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Satu baris tangki tinta.
 *
 * Batang hanya digambar kalau angkanya benar-benar hasil pengukuran. Batang
 * kosong untuk nilai yang tidak terukur akan terbaca sebagai "tinta habis" --
 * salah baca yang jauh lebih merugikan daripada tidak ada batang sama sekali.
 */
@Composable
private fun InkBar(ink: InkLevel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            ink.label.text(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(76.dp),
        )
        if (ink.measured) {
            LinearProgressIndicator(
                progress = { ink.percent / 100f },
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                ink.reading.text(),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(40.dp),
                textAlign = TextAlign.End,
            )
        } else {
            Text(
                ink.reading.text(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
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
        title = { Text(stringResource(task.label)) },
        text = { Text(stringResource(task.confirmation)) },
        confirmButton = {
            TextButton(onClick = { viewModel.runMaintenance(task) }) {
                Text(stringResource(R.string.common_run))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissMaintenance) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
