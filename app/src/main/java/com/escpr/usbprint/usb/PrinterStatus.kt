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
 * Sisa satu warna tinta, seperti yang dilaporkan printer.
 *
 * [percent] adalah perkiraan printer sendiri, bukan hasil pengukuran: printer
 * tangki tinta tidak punya sensor di dalamnya dan hanya menghitung berapa tetes
 * yang sudah disemprotkan sejak terakhir kali diberi tahu bahwa tangkinya
 * penuh.
 * Jadi angka ini bisa meleset jauh kalau tangki diisi tanpa proses reset --
 * dan itu bukan kesalahan pembacaan.
 */
data class InkLevel(
    /** Kode warna dari printer, apa adanya. */
    val code: Int,
    val percent: Int,
) {
    /**
     * Nama warnanya, atau null kalau kodenya tidak dikenali.
     *
     * Null di sini penting: menyebut cyan sebagai magenta lebih buruk daripada
     * menyebutnya "warna 2", karena orang akan mengisi tangki yang salah.
     */
    val name: String? get() = when (code) {
        0x00 -> "Hitam"
        0x01 -> "Cyan"
        0x02 -> "Magenta"
        0x03 -> "Kuning"
        else -> null
    }

    /** Yang ditampilkan ke pengguna; tidak pernah menebak nama. */
    val label: String get() = name ?: ("Warna " + code)
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
    /** Sisa tinta per warna; kosong berarti printer tidak melaporkannya. */
    val inks: List<InkLevel> = emptyList(),
    val confident: Boolean = false,
    /** Isi balasan apa adanya, untuk ditulis ke catatan saat menelusuri masalah. */
    val raw: String = "",
    /**
     * Balasan yang sama dalam heksa.
     *
     * Bentuk terbacanya membuang justru yang dibutuhkan: nomor blok dan
     * panjangnya adalah byte kendali, yang di [raw] semuanya berubah jadi
     * titik. Tanpa heksa, format blok tinta hanya bisa ditebak -- dan menebak
     * format adalah cara paling cepat memasang penguraian yang salah.
     */
    val hex: String = "",
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
    val hex = reply.take(160).joinToString(" ") { "%02X".format(it) }

    val header = "@BDC ST2"
    val headerAt = text.indexOf(header)
    if (headerAt < 0) {
        // Sebagian printer -- L3110 salah satunya, terbukti dari perangkat --
        // membalas dalam bentuk teks "@BDC ST" alih-alih blok biner ST2.
        return parseTextStatus(text, raw, hex) ?: PrinterStatus(raw = raw, hex = hex)
    }

    // Lewati judul dan akhiran barisnya.
    var index = headerAt + header.length
    while (index < reply.size && (reply[index] == '\r'.code.toByte() ||
            reply[index] == '\n'.code.toByte())
    ) index++

    // Dua byte panjang, little-endian.
    if (index + 2 > reply.size) return PrinterStatus(raw = raw, hex = hex)
    index += 2

    var state = PrinterState.UNKNOWN
    var fault = PrinterFault.NONE
    var faultCode: Int? = null
    var inks: List<InkLevel> = emptyList()
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
            ID_INK -> parseInk(reply, payloadAt, length)?.let {
                inks = it
                understood = true
            }
        }
        index = payloadAt + length
    }

    return PrinterStatus(
        state = state,
        fault = fault,
        faultCode = faultCode,
        inks = inks,
        confident = understood,
        raw = raw,
        hex = hex,
    )
}

/**
 * Menguraikan blok sisa tinta.
 *
 * Bentuknya: satu byte panjang tiap entri, lalu entri-entri berurutan. Byte
 * pertama tiap entri kode warnanya, byte terakhir sisanya dalam persen.
 *
 * Mengembalikan null kalau bentuknya tidak masuk akal, dan **bukan** daftar
 * kosong: keduanya berbeda artinya. Null berarti "tidak paham blok ini", daftar
 * kosong berarti "printer tidak melaporkan tinta sama sekali". Entri yang
 * persennya di luar 0-100 dibuang satu per satu, karena satu entri aneh tidak
 * boleh membuat tiga entri lain yang waras ikut hilang.
 */
private fun parseInk(reply: ByteArray, payloadAt: Int, length: Int): List<InkLevel>? {
    if (length < 2) return null
    val entrySize = reply[payloadAt].toInt() and 0xFF
    if (entrySize < 2 || entrySize > length) return null

    val levels = mutableListOf<InkLevel>()
    var at = payloadAt + 1
    val end = payloadAt + length
    while (at + entrySize <= end) {
        val code = reply[at].toInt() and 0xFF
        val percent = reply[at + entrySize - 1].toInt() and 0xFF
        if (percent in 0..100) levels += InkLevel(code, percent)
        at += entrySize
    }
    return levels
}

/**
 * Menguraikan balasan status bentuk teks.
 *
 * Bentuknya: judul `@BDC ST`, akhiran baris, lalu pasangan `KUNCI:HEKSA;`
 * berulang -- misalnya `ST:04;ER:00;`. Balasan sungguhan dari L3110:
 *
 *     40 42 44 43 20 53 54 0D 0A 53 54 3A 30 34 3B 0C
 *     "@BDC ST
ST:04;"
 *
 * Kode statusnya memakai penomoran yang sama dengan bentuk biner, jadi
 * pemetaannya dipakai bersama alih-alih ditulis dua kali dan lalu menyimpang.
 *
 * Mengembalikan null kalau judulnya bukan ini sama sekali, supaya pemanggil
 * bisa membedakan "bukan format ini" dari "format ini tapi kosong".
 */
private fun parseTextStatus(text: String, raw: String, hex: String): PrinterStatus? {
    if (!text.contains("@BDC ST")) return null

    var state = PrinterState.UNKNOWN
    var fault = PrinterFault.NONE
    var faultCode: Int? = null
    var understood = false

    Regex("([A-Z]{2,3}):([0-9A-Fa-f]{2});").findAll(text).forEach { m ->
        val nilai = m.groupValues[2].toIntOrNull(16) ?: return@forEach
        when (m.groupValues[1]) {
            "ST" -> { state = mapState(nilai); understood = true }
            // Nol di sini berarti TIDAK ada galat, kebalikan dari arti kode
            // yang sama pada blok biner. Menyamakan keduanya akan membuat
            // aplikasi menolak mencetak pada printer yang sehat -- kegagalan
            // yang jauh lebih merugikan daripada tidak mengenali galat.
            "ER" -> if (nilai != 0) {
                faultCode = nilai
                fault = mapFault(nilai)
                understood = true
            }
        }
    }

    return PrinterStatus(
        state = state,
        fault = fault,
        faultCode = faultCode,
        confident = understood,
        raw = raw,
        hex = hex,
    )
}

/** Blok status utama. */
private const val ID_STATUS = 0x01

/** Blok sebab kesalahan; hanya ada saat status bernilai error. */
private const val ID_ERROR = 0x02

/**
 * Blok sisa tinta.
 *
 * Nomor blok dan bentuk entrinya diambil dari driver ESC/P-R terbuka dan
 * **belum diverifikasi pada L3110**. Karena itu kode warna yang tidak dikenali
 * ditampilkan apa adanya, dan blok yang bentuknya tidak masuk akal diabaikan
 * seluruhnya alih-alih ditafsirkan sekenanya.
 */
private const val ID_INK = 0x0F

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
