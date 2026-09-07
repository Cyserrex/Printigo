package com.escpr.usbprint.usb

/**
 * Keadaan printer hasil pembacaan balasan status.
 *
 * Nilai [UNKNOWN] sengaja ada dan sering dipakai: lebih baik mengaku tidak tahu
 * daripada menebak, karena keputusan boleh-tidaknya mencetak bergantung padanya.
 */
enum class PrinterState {
    IDLE,
    PRINTING,
    BUSY,
    PAUSED,
    CLEANING,
    ERROR,
    UNKNOWN,
}

/** Sebab printer berhenti, sejauh yang bisa dipastikan dari balasannya. */
enum class PrinterFault {
    NONE,
    PAPER_OUT,
    PAPER_JAM,
    COVER_OPEN,
    INK_OUT,
    WASTE_INK_FULL,
    /** Printer melaporkan kesalahan, tetapi kodenya tidak dikenali. */
    UNRECOGNIZED,
}

/**
 * Hasil pembacaan status printer.
 *
 * [confident] menandai apakah balasannya benar-benar dipahami. Pemeriksaan
 * sebelum mencetak hanya boleh menghalangi kalau nilainya true -- kalau tidak,
 * kesalahan penafsiran akan memblokir pencetakan yang sebenarnya baik-baik saja.
 */
data class PrinterStatus(
    val state: PrinterState = PrinterState.UNKNOWN,
    val fault: PrinterFault = PrinterFault.NONE,
    val faultCode: Int? = null,
    val confident: Boolean = false,
    /** Isi balasan apa adanya, untuk ditulis ke catatan saat menelusuri masalah. */
    val raw: String = "",
) {
    val blocksPrinting: Boolean
        get() = confident && (state == PrinterState.ERROR || fault != PrinterFault.NONE)

    /** Kalimat singkat untuk pengguna, atau null kalau tidak ada yang perlu disampaikan. */
    val message: String?
        get() = when {
            fault == PrinterFault.PAPER_OUT -> "Kertas habis. Isi baki belakang printer."
            fault == PrinterFault.PAPER_JAM -> "Kertas macet. Keluarkan kertasnya dari printer."
            fault == PrinterFault.COVER_OPEN -> "Tutup printer terbuka."
            fault == PrinterFault.INK_OUT -> "Tinta habis."
            fault == PrinterFault.WASTE_INK_FULL -> "Bak tinta buangan penuh."
            fault == PrinterFault.UNRECOGNIZED ->
                "Printer melaporkan kesalahan" + (faultCode?.let { " (kode $it)" } ?: "") + "."
            state == PrinterState.PAUSED -> "Printer sedang dijeda."
            state == PrinterState.CLEANING -> "Printer sedang membersihkan head."
            state == PrinterState.BUSY -> "Printer sedang sibuk."
            else -> null
        }
}

/**
 * Membaca balasan status Epson berformat `@BDC ST2`.
 *
 * Bentuknya: judul teks `@BDC ST2` diakhiri baris baru, lalu panjang 2 byte,
 * lalu rentetan blok `<id><panjang><isi>`.
 *
 * Penguraian strukturnya pasti dan diuji. Pemetaan kode ke sebab diambil dari
 * driver ESC/P-R terbuka dan **belum diverifikasi pada L3110 sungguhan**, jadi
 * kode yang tidak dikenali dilaporkan apa adanya sebagai [PrinterFault.UNRECOGNIZED]
 * alih-alih ditebak. Balasan yang tidak berformat @BDC ST2 menghasilkan status
 * tidak yakin, bukan status "baik".
 */
fun parsePrinterStatus(reply: ByteArray): PrinterStatus {
    val text = String(reply, Charsets.ISO_8859_1)
    val raw = text.take(120).replace(Regex("[\\x00-\\x1F]"), ".")

    val header = "@BDC ST2"
    val headerAt = text.indexOf(header)
    if (headerAt < 0) return PrinterStatus(raw = raw)

    // Lewati judul dan akhiran barisnya.
    var index = headerAt + header.length
    while (index < reply.size && (reply[index] == '\r'.code.toByte() ||
            reply[index] == '\n'.code.toByte())
    ) index++

    // Dua byte panjang, little-endian.
    if (index + 2 > reply.size) return PrinterStatus(raw = raw)
    index += 2

    var state = PrinterState.UNKNOWN
    var fault = PrinterFault.NONE
    var faultCode: Int? = null
    var understood = false

    while (index + 2 <= reply.size) {
        val id = reply[index].toInt() and 0xFF
        val length = reply[index + 1].toInt() and 0xFF
        val payloadAt = index + 2
        if (payloadAt + length > reply.size) break

        when (id) {
            ID_STATUS -> if (length >= 1) {
                state = mapState(reply[payloadAt].toInt() and 0xFF)
                understood = true
            }
            ID_ERROR -> if (length >= 1) {
                val code = reply[payloadAt].toInt() and 0xFF
                faultCode = code
                fault = mapFault(code)
                understood = true
            }
        }
        index = payloadAt + length
    }

    return PrinterStatus(
        state = state,
        fault = fault,
        faultCode = faultCode,
        confident = understood,
        raw = raw,
    )
}

/** Blok status utama. */
private const val ID_STATUS = 0x01

/** Blok sebab kesalahan; hanya ada saat status bernilai error. */
private const val ID_ERROR = 0x02

private fun mapState(code: Int): PrinterState = when (code) {
    0x00 -> PrinterState.ERROR
    0x01 -> PrinterState.PRINTING     // mencetak lembar status sendiri
    0x02 -> PrinterState.BUSY
    0x03 -> PrinterState.BUSY         // menunggu data
    0x04 -> PrinterState.IDLE
    0x05 -> PrinterState.PAUSED
    0x07 -> PrinterState.CLEANING
    0x08 -> PrinterState.BUSY         // keadaan pabrik
    else -> PrinterState.UNKNOWN
}

/**
 * Kode sebab kesalahan.
 *
 * Hanya yang benar-benar sering muncul dan konsisten di driver terbuka yang
 * dipetakan. Sisanya sengaja dibiarkan tidak dikenali.
 */
private fun mapFault(code: Int): PrinterFault = when (code) {
    0x00 -> PrinterFault.UNRECOGNIZED   // kesalahan fatal, sebabnya tidak dirinci
    0x01 -> PrinterFault.PAPER_JAM
    0x04 -> PrinterFault.PAPER_OUT
    0x05 -> PrinterFault.PAPER_OUT      // baki kosong
    0x0A -> PrinterFault.COVER_OPEN
    0x10 -> PrinterFault.WASTE_INK_FULL
    0x11 -> PrinterFault.INK_OUT
    else -> PrinterFault.UNRECOGNIZED
}
