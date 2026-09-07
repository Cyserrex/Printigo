package com.escpr.usbprint.ui

import com.escpr.usbprint.usb.PrinterErrorKind

/** Hasil percobaan cetak terakhir, ditampilkan tepat di atas tombol Cetak. */
sealed interface PrintOutcome {
    data object None : PrintOutcome
    data class Success(val sheets: Int, val seconds: Double) : PrintOutcome
    data object Cancelled : PrintOutcome
    data class Failed(val kind: PrinterErrorKind, val technical: String) : PrintOutcome
    data class Saved(val bytes: Long) : PrintOutcome
}

/** Tindakan pemulihan yang ditawarkan bersama pesan kegagalan. */
enum class OutcomeAction(val label: String) {
    RETRY("Coba lagi"),
    REFRESH("Cari printer"),
    REQUEST_PERMISSION("Beri izin"),
    PICK_FILE("Pilih berkas lain"),
}

/**
 * Satu kalimat sebab dan satu tindakan.
 *
 * Aturannya: pengguna tidak perlu tahu istilah teknis, tapi harus tahu apa
 * yang harus ia lakukan berikutnya. Karena itu tepat satu tombol per sebab --
 * dua pilihan membuat orang ragu, nol pilihan membuat buntu.
 */
data class FailureAdvice(
    val title: String,
    val hint: String,
    val action: OutcomeAction?,
)

fun adviceFor(kind: PrinterErrorKind): FailureAdvice = when (kind) {
    PrinterErrorKind.NO_USB_HOST -> FailureAdvice(
        "HP ini tidak mendukung USB OTG",
        "Mencetak lewat kabel butuh HP yang bisa menjadi host USB. Coba HP lain.",
        null,
    )

    PrinterErrorKind.NO_PRINTER -> FailureAdvice(
        "Printer tidak ditemukan",
        "Pastikan kabel OTG tertancap dan printer menyala, lalu cari lagi.",
        OutcomeAction.REFRESH,
    )

    PrinterErrorKind.NOT_A_PRINTER -> FailureAdvice(
        "Perangkat yang tersambung bukan printer",
        "Yang terdeteksi tidak punya antarmuka printer. Periksa kabelnya menuju printer.",
        OutcomeAction.REFRESH,
    )

    PrinterErrorKind.NO_ENDPOINT -> FailureAdvice(
        "Printer ini tidak bisa menerima data cetak",
        "Printer terdeteksi tapi tidak menyediakan jalur data yang dibutuhkan.",
        null,
    )

    PrinterErrorKind.PERMISSION_DENIED -> FailureAdvice(
        "Izin akses USB belum diberikan",
        "Tekan Beri izin, lalu pilih Oke pada dialog yang muncul.",
        OutcomeAction.REQUEST_PERMISSION,
    )

    PrinterErrorKind.BUSY -> FailureAdvice(
        "Printer sedang dipakai aplikasi lain",
        "Tutup aplikasi cetak lain, atau cabut dan colok ulang kabelnya.",
        OutcomeAction.RETRY,
    )

    PrinterErrorKind.DISCONNECTED -> FailureAdvice(
        "Printer terputus di tengah jalan",
        "Kabel tersenggol atau printer mati. Sambungkan lagi lalu ulangi.",
        OutcomeAction.REFRESH,
    )

    PrinterErrorKind.TRANSFER_FAILED -> FailureAdvice(
        "Printer berhenti menerima data",
        "Biasanya karena kertas habis, kertas macet, atau tutup terbuka. " +
            "Periksa printer, lalu coba lagi.",
        OutcomeAction.RETRY,
    )

    PrinterErrorKind.DOCUMENT_UNREADABLE -> FailureAdvice(
        "Berkas tidak bisa dibaca",
        "Berkasnya mungkin rusak atau formatnya tidak didukung.",
        OutcomeAction.PICK_FILE,
    )

    PrinterErrorKind.UNSUPPORTED_FORMAT -> FailureAdvice(
        "Format berkas ini belum didukung",
        "Printigo hanya bisa mencetak gambar dan PDF. Buka berkasnya di aplikasi " +
            "Office, ekspor ke PDF, lalu cetak PDF-nya.",
        OutcomeAction.PICK_FILE,
    )

    PrinterErrorKind.PRINTER_NOT_READY -> FailureAdvice(
        "Printer belum siap",
        "Beresi printernya dulu, lalu coba lagi. Tidak ada kertas yang terbuang " +
            "karena pengiriman belum dimulai.",
        OutcomeAction.RETRY,
    )

    PrinterErrorKind.OUT_OF_MEMORY -> FailureAdvice(
        "Memori HP tidak cukup",
        "Turunkan resolusi, atau tutup aplikasi lain lalu coba lagi.",
        OutcomeAction.RETRY,
    )

    PrinterErrorKind.UNKNOWN -> FailureAdvice(
        "Cetak gagal",
        "Sebabnya belum bisa dipastikan. Rinciannya ada di Catatan.",
        OutcomeAction.RETRY,
    )
}
