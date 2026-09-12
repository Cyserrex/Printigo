package com.escpr.usbprint.ui

import androidx.annotation.StringRes
import com.escpr.usbprint.R
import com.escpr.usbprint.util.UiText
import com.escpr.usbprint.util.uiText

/**
 * Daftar periksa sambungan, dari syarat paling dasar sampai siap mencetak.
 *
 * Perhitungannya dipisah dari tampilan dan dibuat murni supaya bisa diuji:
 * urutan langkah, langkah mana yang jadi penghambat, dan tombol apa yang
 * ditawarkan adalah aturan produk, bukan detail gambar.
 *
 * Sejak aplikasi punya dua bahasa, yang dihasilkan di sini bukan lagi kalimat
 * jadi melainkan [UiText] -- rujukan ke kalimatnya. Aturannya tetap sama dan
 * tetap bisa diuji; yang berubah hanya siapa yang menerjemahkan, dan itu
 * sekarang tugas layar, yang memang tahu bahasa apa yang sedang berlaku.
 */
enum class StepState {
    /** Sudah terpenuhi. */
    OK,

    /** Belum terpenuhi dan pengguna bisa berbuat sesuatu sekarang. */
    ACTION_NEEDED,

    /** Tidak akan pernah terpenuhi di perangkat ini. */
    IMPOSSIBLE,

    /** Menunggu langkah sebelumnya, jadi belum relevan. */
    WAITING,
}

enum class StepAction(@StringRes val label: Int) {
    REFRESH(R.string.step_action_refresh),
    REQUEST_PERMISSION(R.string.step_action_permission),
    CHECK_SUPPORT(R.string.step_action_support),
}

data class ConnectionStep(
    val title: UiText,
    val state: StepState,
    val detail: UiText? = null,
    val action: StepAction? = null,
)

/** Hasil pembacaan IEEE-1284 Device ID. */
enum class EscpRSupport {
    /** Belum pernah dibaca. */
    UNKNOWN,

    /** Device ID memuat ESCPR. */
    SUPPORTED,

    /** Device ID terbaca tapi tidak menyebut ESCPR. */
    NOT_LISTED,

    /** Printer tidak membalas permintaan Device ID. */
    NO_REPLY,
}

data class ConnectionStatus(
    val steps: List<ConnectionStep>,
    val ready: Boolean,
    /** Satu kalimat yang mewakili keadaan sekarang, untuk baris ringkas. */
    val headline: UiText,
)

fun computeConnectionStatus(
    hasUsbHost: Boolean,
    deviceCount: Int,
    deviceLabel: String?,
    hasPermission: Boolean,
    permissionDenied: Boolean,
    support: EscpRSupport,
): ConnectionStatus {
    val steps = mutableListOf<ConnectionStep>()

    // 1. Kemampuan HP. Kalau tidak ada, tidak ada gunanya menawarkan apa pun.
    steps += ConnectionStep(
        title = uiText(R.string.conn_step_otg),
        state = if (hasUsbHost) StepState.OK else StepState.IMPOSSIBLE,
        detail = if (hasUsbHost) null else uiText(R.string.conn_step_otg_no),
    )

    // 2. Perangkat terlihat.
    val deviceFound = deviceCount > 0
    steps += ConnectionStep(
        title = uiText(R.string.conn_step_detected),
        state = when {
            !hasUsbHost -> StepState.WAITING
            deviceFound -> StepState.OK
            else -> StepState.ACTION_NEEDED
        },
        detail = when {
            !hasUsbHost -> null
            // Nama perangkat datang dari printernya sendiri: tidak ada yang
            // bisa diterjemahkan, dan menerjemahkannya justru akan salah.
            deviceFound -> deviceLabel?.let { uiText(it) }
            else -> uiText(R.string.conn_step_detected_no)
        },
        action = if (hasUsbHost && !deviceFound) StepAction.REFRESH else null,
    )

    // 3. Izin akses. Dibedakan antara belum diminta dan sudah ditolak, supaya
    //    pengguna yang menolak tahu bahwa ia harus menyetujui, bukan menunggu.
    steps += ConnectionStep(
        title = uiText(R.string.conn_step_permission),
        state = when {
            !deviceFound -> StepState.WAITING
            hasPermission -> StepState.OK
            else -> StepState.ACTION_NEEDED
        },
        detail = when {
            !deviceFound -> null
            hasPermission -> null
            permissionDenied -> uiText(R.string.conn_step_permission_denied)
            else -> uiText(R.string.conn_step_permission_pending)
        },
        action = if (deviceFound && !hasPermission) StepAction.REQUEST_PERMISSION else null,
    )

    // 4. Kesiapan cetak.
    val ready = hasUsbHost && deviceFound && hasPermission
    steps += ConnectionStep(
        title = uiText(R.string.conn_step_ready),
        state = if (ready) StepState.OK else StepState.WAITING,
        detail = when {
            !ready -> null
            support == EscpRSupport.SUPPORTED -> uiText(R.string.conn_support_yes)
            support == EscpRSupport.NOT_LISTED -> uiText(R.string.conn_support_not_listed)
            support == EscpRSupport.NO_REPLY -> uiText(R.string.conn_support_no_reply)
            else -> null
        },
        action = if (ready && support == EscpRSupport.UNKNOWN) StepAction.CHECK_SUPPORT else null,
    )

    // Ringkasan sengaja tidak memakai kalimat detail langkah: keduanya tampil
    // bersamaan saat panel terbuka, dan kalimat yang sama dua kali dalam satu
    // kartu terbaca seperti kesalahan.
    val headline = when {
        !hasUsbHost -> uiText(R.string.conn_headline_no_otg)
        !deviceFound -> uiText(R.string.conn_headline_no_device)
        !hasPermission -> uiText(R.string.conn_headline_no_permission)
        else -> deviceLabel?.let { uiText(it) } ?: uiText(R.string.conn_headline_ready)
    }

    return ConnectionStatus(steps = steps, ready = ready, headline = headline)
}
