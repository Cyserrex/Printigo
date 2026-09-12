package com.escpr.usbprint.ui

import androidx.annotation.StringRes
import com.escpr.usbprint.R
import com.escpr.usbprint.escpr.MaintenanceTask
import com.escpr.usbprint.usb.PrinterErrorKind

/** Hasil percobaan cetak terakhir, ditampilkan tepat di atas tombol Cetak. */
sealed interface PrintOutcome {
    data object None : PrintOutcome
    data class Success(val sheets: Int, val seconds: Double) : PrintOutcome
    data object Cancelled : PrintOutcome
    data class Failed(val kind: PrinterErrorKind, val technical: String) : PrintOutcome
    data class Saved(val bytes: Long) : PrintOutcome

    /**
     * Perintah perawatan sudah terkirim.
     *
     * Sengaja tidak mengaku "berhasil": yang bisa dipastikan hanyalah byte-nya
     * sampai ke printer. Apakah printer benar-benar mengerjakannya hanya bisa
     * dilihat dari printernya sendiri.
     */
    data class MaintenanceSent(val task: MaintenanceTask) : PrintOutcome
}

/** Tindakan pemulihan yang ditawarkan bersama pesan kegagalan. */
enum class OutcomeAction(@StringRes val label: Int) {
    RETRY(R.string.action_retry),
    REFRESH(R.string.action_refresh),
    REQUEST_PERMISSION(R.string.action_permission),
    PICK_FILE(R.string.action_pick_file),
}

/**
 * Satu kalimat sebab dan satu tindakan.
 *
 * Aturannya: pengguna tidak perlu tahu istilah teknis, tapi harus tahu apa
 * yang harus ia lakukan berikutnya. Karena itu tepat satu tombol per sebab --
 * dua pilihan membuat orang ragu, nol pilihan membuat buntu.
 */
data class FailureAdvice(
    @StringRes val title: Int,
    @StringRes val hint: Int,
    val action: OutcomeAction?,
)

fun adviceFor(kind: PrinterErrorKind): FailureAdvice = when (kind) {
    PrinterErrorKind.NO_USB_HOST -> FailureAdvice(
        R.string.fail_no_usb_host_title,
        R.string.fail_no_usb_host_hint,
        null,
    )

    PrinterErrorKind.NO_PRINTER -> FailureAdvice(
        R.string.fail_no_printer_title,
        R.string.fail_no_printer_hint,
        OutcomeAction.REFRESH,
    )

    PrinterErrorKind.NOT_A_PRINTER -> FailureAdvice(
        R.string.fail_not_a_printer_title,
        R.string.fail_not_a_printer_hint,
        OutcomeAction.REFRESH,
    )

    PrinterErrorKind.NO_ENDPOINT -> FailureAdvice(
        R.string.fail_no_endpoint_title,
        R.string.fail_no_endpoint_hint,
        null,
    )

    PrinterErrorKind.PERMISSION_DENIED -> FailureAdvice(
        R.string.fail_permission_title,
        R.string.fail_permission_hint,
        OutcomeAction.REQUEST_PERMISSION,
    )

    PrinterErrorKind.BUSY -> FailureAdvice(
        R.string.fail_busy_title,
        R.string.fail_busy_hint,
        OutcomeAction.RETRY,
    )

    PrinterErrorKind.DISCONNECTED -> FailureAdvice(
        R.string.fail_disconnected_title,
        R.string.fail_disconnected_hint,
        OutcomeAction.REFRESH,
    )

    PrinterErrorKind.TRANSFER_FAILED -> FailureAdvice(
        R.string.fail_transfer_title,
        R.string.fail_transfer_hint,
        OutcomeAction.RETRY,
    )

    PrinterErrorKind.DOCUMENT_UNREADABLE -> FailureAdvice(
        R.string.fail_unreadable_title,
        R.string.fail_unreadable_hint,
        OutcomeAction.PICK_FILE,
    )

    PrinterErrorKind.UNSUPPORTED_FORMAT -> FailureAdvice(
        R.string.fail_unsupported_title,
        R.string.fail_unsupported_hint,
        OutcomeAction.PICK_FILE,
    )

    PrinterErrorKind.PRINTER_NOT_READY -> FailureAdvice(
        R.string.fail_not_ready_title,
        R.string.fail_not_ready_hint,
        OutcomeAction.RETRY,
    )

    PrinterErrorKind.OUT_OF_MEMORY -> FailureAdvice(
        R.string.fail_out_of_memory_title,
        R.string.fail_out_of_memory_hint,
        OutcomeAction.RETRY,
    )

    PrinterErrorKind.UNKNOWN -> FailureAdvice(
        R.string.fail_unknown_title,
        R.string.fail_unknown_hint,
        OutcomeAction.RETRY,
    )
}
