package com.escpr.usbprint.ui

/**
 * Daftar periksa sambungan, dari syarat paling dasar sampai siap mencetak.
 *
 * Perhitungannya dipisah dari tampilan dan dibuat murni supaya bisa diuji:
 * urutan langkah, langkah mana yang jadi penghambat, dan tombol apa yang
 * ditawarkan adalah aturan produk, bukan detail gambar.
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

enum class StepAction(val label: String) {
    REFRESH("Cari ulang"),
    REQUEST_PERMISSION("Beri izin"),
    CHECK_SUPPORT("Cek dukungan"),
}

data class ConnectionStep(
    val title: String,
    val state: StepState,
    val detail: String? = null,
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
    val headline: String,
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
        title = "HP mendukung USB OTG",
        state = if (hasUsbHost) StepState.OK else StepState.IMPOSSIBLE,
        detail = if (hasUsbHost) null
        else "HP ini tidak bisa menjadi host USB, jadi tidak bisa mencetak lewat kabel.",
    )

    // 2. Perangkat terlihat.
    val deviceFound = deviceCount > 0
    steps += ConnectionStep(
        title = "Printer terdeteksi",
        state = when {
            !hasUsbHost -> StepState.WAITING
            deviceFound -> StepState.OK
            else -> StepState.ACTION_NEEDED
        },
        detail = when {
            !hasUsbHost -> null
            deviceFound -> deviceLabel
            else -> "Colok printer ke HP pakai kabel OTG, lalu nyalakan printernya."
        },
        action = if (hasUsbHost && !deviceFound) StepAction.REFRESH else null,
    )

    // 3. Izin akses. Dibedakan antara belum diminta dan sudah ditolak, supaya
    //    pengguna yang menolak tahu bahwa ia harus menyetujui, bukan menunggu.
    steps += ConnectionStep(
        title = "Izin akses USB",
        state = when {
            !deviceFound -> StepState.WAITING
            hasPermission -> StepState.OK
            else -> StepState.ACTION_NEEDED
        },
        detail = when {
            !deviceFound -> null
            hasPermission -> null
            permissionDenied -> "Izin ditolak. Tekan Beri izin lalu pilih Oke pada dialog Android."
            else -> "Android meminta persetujuan sebelum aplikasi boleh memakai printer."
        },
        action = if (deviceFound && !hasPermission) StepAction.REQUEST_PERMISSION else null,
    )

    // 4. Kesiapan cetak.
    val ready = hasUsbHost && deviceFound && hasPermission
    steps += ConnectionStep(
        title = "Siap mencetak",
        state = if (ready) StepState.OK else StepState.WAITING,
        detail = when {
            !ready -> null
            support == EscpRSupport.SUPPORTED -> "Printer mendukung ESC/P-R."
            support == EscpRSupport.NOT_LISTED ->
                "Printer tidak menyebut ESC/P-R. Cetak tetap bisa dicoba."
            support == EscpRSupport.NO_REPLY ->
                "Printer tidak membalas pemeriksaan. Ini tidak selalu masalah."
            else -> null
        },
        action = if (ready && support == EscpRSupport.UNKNOWN) StepAction.CHECK_SUPPORT else null,
    )

    // Ringkasan sengaja tidak memakai kalimat detail langkah: keduanya tampil
    // bersamaan saat panel terbuka, dan kalimat yang sama dua kali dalam satu
    // kartu terbaca seperti kesalahan.
    val headline = when {
        !hasUsbHost -> "HP ini tidak mendukung cetak lewat kabel"
        !deviceFound -> "Printer belum tersambung"
        !hasPermission -> "Menunggu izin akses USB"
        else -> deviceLabel ?: "Printer siap"
    }

    return ConnectionStatus(steps = steps, ready = ready, headline = headline)
}
