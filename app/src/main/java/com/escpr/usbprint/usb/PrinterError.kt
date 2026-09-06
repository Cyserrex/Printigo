package com.escpr.usbprint.usb

import java.io.IOException

/**
 * Sebab kegagalan yang bisa dibedakan secara terprogram.
 *
 * Sengaja berupa enum, bukan pencocokan teks pesan: pesan untuk pengguna bisa
 * berubah kapan saja tanpa memengaruhi cara aplikasi memilih tindakan pemulihan.
 */
enum class PrinterErrorKind {
    /** HP-nya sendiri tidak punya kemampuan USB Host / OTG. */
    NO_USB_HOST,

    /** Tidak ada perangkat USB yang tersambung. */
    NO_PRINTER,

    /** Ada perangkat, tapi tidak punya antarmuka printer (USB class 7). */
    NOT_A_PRINTER,

    /** Printer tidak punya bulk endpoint keluar. */
    NO_ENDPOINT,

    /** Pengguna belum memberi, atau menolak, izin akses USB. */
    PERMISSION_DENIED,

    /** Antarmuka sedang dikuasai proses lain. */
    BUSY,

    /** Printer hilang dari daftar perangkat di tengah pekerjaan. */
    DISCONNECTED,

    /** Transfer bulk gagal walau perangkat masih terdaftar. */
    TRANSFER_FAILED,

    /** Berkas yang dipilih tidak bisa dibaca atau diurai. */
    DOCUMENT_UNREADABLE,

    /** Kehabisan memori saat merender halaman. */
    OUT_OF_MEMORY,

    /** Belum terklasifikasi. */
    UNKNOWN,
}

/**
 * Kegagalan yang sudah membawa sebabnya. [message] tetap diisi kalimat yang
 * layak dibaca manusia supaya berguna di log dan laporan bug.
 */
class PrinterException(
    val kind: PrinterErrorKind,
    override val message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Menerjemahkan pengecualian apa pun menjadi sebab yang bisa ditindaklanjuti.
 *
 * [deviceStillAttached] diperiksa oleh pemanggil pada saat kegagalan terjadi.
 * Ini pemeriksaan langsung ke daftar perangkat USB, bukan tebakan dari isi
 * pesan: kalau printer sudah hilang dari daftar, sebab yang paling masuk akal
 * adalah kabelnya terlepas, bukan datanya yang salah.
 */
fun classifyFailure(error: Throwable, deviceStillAttached: Boolean): PrinterErrorKind = when {
    error is PrinterException -> {
        if (error.kind == PrinterErrorKind.TRANSFER_FAILED && !deviceStillAttached) {
            PrinterErrorKind.DISCONNECTED
        } else {
            error.kind
        }
    }
    error is OutOfMemoryError -> PrinterErrorKind.OUT_OF_MEMORY
    !deviceStillAttached -> PrinterErrorKind.DISCONNECTED
    else -> PrinterErrorKind.UNKNOWN
}
