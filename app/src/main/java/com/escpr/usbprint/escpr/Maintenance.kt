package com.escpr.usbprint.escpr

/**
 * Perawatan printer: cek nozzle dan pembersihan head.
 *
 * Keduanya perintah REMOTE1, bukan ESC/P-R. Bedanya penting: perintah raster
 * mengirim gambar untuk dicetak, sedangkan perintah di sini menyuruh printer
 * mengerjakan sesuatu sendiri. Karena itu tidak ada satu pun data halaman yang
 * ikut -- polanya digambar oleh printer dari memorinya sendiri.
 *
 * ## Dari mana byte-nya
 *
 * Urutannya mengikuti escputil di Gutenprint, yang sudah dipakai bertahun-tahun
 * untuk printer Epson: keluar dari packet mode, reset dua kali, masuk REMOTE1,
 * satu perintah dua huruf, keluar, reset lagi.
 *
 * **Belum diverifikasi pada L3110 sungguhan.** Sebagian printer Epson memakai
 * bentuk perintah tanpa parameter (`NC 01 00 00`), sebagian yang lebih baru
 * memakai satu byte parameter (`NC 02 00 00 <pola>`). Yang dipakai di sini
 * bentuk klasik, dan bentuk kedua tinggal mengganti [Variant]. Kalau printer
 * tidak bereaksi sama sekali, itu petunjuk pertama yang harus dicoba.
 *
 * ## Kenapa perintahnya dibungkus JS dan JE
 *
 * Pada L3110 sungguhan, pola cek nozzle tercetak lengkap tetapi kertasnya
 * berhenti separuh keluar sementara printer melaporkan dirinya sudah idle.
 *
 * Tiga dugaan dicoba di perangkat. Menahan sambungan sampai printer melapor
 * siap: tetap tertahan. Membuang akhiran `ESC @`: tetap tertahan. Form feed
 * terpisah: kertas keluar -- jadi kertasnya memang menunggu diperintah.
 *
 * Tetapi form feed hanya menutup gejalanya. Sebabnya terlihat setelah
 * membandingkan dengan jalur cetak, yang tidak pernah meninggalkan kertas
 * tertahan: [EscpRJob] membuka pekerjaan dengan `JS` dan menutupnya dengan
 * `LD` lalu `JE`. Perintah perawatan dulu tidak melakukan keduanya, jadi
 * printer menerimanya di luar pekerjaan mana pun -- ia mencetak polanya, tapi
 * rutinitas akhir yang mengeluarkan kertas tidak pernah dijalankan karena
 * tidak ada pekerjaan yang dinyatakan selesai.
 *
 * Membungkusnya sama seperti jalur cetak lebih baik daripada menambahkan form
 * feed: kalau suatu model memang mengeluarkan kertas sendiri, form feed akan
 * memakan satu lembar kosong, sedangkan penutup pekerjaan tidak pernah
 * menambah halaman.
 *
 * Perintah ini tidak bisa merusak printer: keduanya operasi perawatan biasa
 * yang juga ada di panel printer bermenu. Yang perlu diingat hanya bahwa
 * pembersihan head **memakai tinta cukup banyak**, jadi tidak untuk diulang-ulang.
 */
object Maintenance {

    /**
     * Dua bentuk perintah REMOTE1 yang beredar di printer Epson.
     *
     * [CLASSIC] tanpa parameter, [EXTENDED] dengan satu byte parameter.
     * Dipisah supaya model yang menolak yang satu bisa mencoba yang lain.
     */
    enum class Variant { CLASSIC, EXTENDED }

    /**
     * Bentuk yang dipakai driver Epson resmi, terbaca dari rekaman USB-nya.
     */
    val DEFAULT_VARIANT = Variant.EXTENDED

    /** Pola pembersihan. Nol berarti seluruh warna. */
    const val CLEAN_ALL = 0x00

    /** Pola cek nozzle standar. */
    const val NOZZLE_PATTERN = 0x00

    /**
     * Mencetak pola cek nozzle.
     *
     * Urutannya disalin dari rekaman lalu lintas USB driver Epson resmi,
     * bukan disusun sendiri. Satu lembar kertas dipakai; polanya digambar
     * printer dari memorinya sendiri.
     */
    fun nozzleCheck(variant: Variant = DEFAULT_VARIANT): ByteArray =
        wrapJob(command("NC", NOZZLE_PATTERN, variant), keluarkanKertas = true)

    /**
     * Menjalankan pembersihan head.
     *
     * Memakai tinta, tidak memakai kertas. Strukturnya mengikuti cek nozzle
     * yang terekam, tanpa form feed -- **itu kesimpulan, bukan rekaman**:
     * yang direkam baru cek nozzle.
     */
    fun headCleaning(variant: Variant = DEFAULT_VARIANT): ByteArray =
        wrapJob(command("CH", CLEAN_ALL, variant), keluarkanKertas = false)

    /**
     * Meminta printer mengirimkan laporan statusnya.
     *
     * Bentuknya disalin dari rekaman USB driver Epson: `OT 02 00 01 01` lalu
     * `ST 01 00 01`. Itu bukan bentuk yang selama ini dipakai aplikasi ini.
     *
     * Bedanya menentukan. Bentuk lama (`ST 02 00 00 01`) dijawab printer dengan
     * teks pendek `@BDC ST` yang hanya memuat status, tanpa tinta sama sekali.
     * Bentuk driver dijawab dengan balasan biner `@BDC ST2` sepanjang 204 byte
     * yang memuat blok tinta, nomor seri, dan banyak lagi. Selama berhari-hari
     * aplikasi ini menyimpulkan "printer tidak melaporkan tinta" padahal yang
     * terjadi adalah kita menanyakannya dengan cara yang salah.
     *
     * Tidak membuka pekerjaan -- bertanya tidak boleh menggerakkan kertas.
     */
    fun statusRequest(): ByteArray = wrapQuery(OT_QUERY + ST_QUERY)

    /**
     * Mengeluarkan kertas yang tertahan di dalam printer.
     *
     * Form feed sendirian, tanpa pembungkus pekerjaan. Tetap disediakan sebagai
     * tombol karena kertas bisa tertahan oleh sebab apa pun.
     */
    fun ejectPage(): ByteArray = EscpR.EXIT_PACKET_MODE + byteArrayOf(FORM_FEED)

    private fun command(name: String, parameter: Int, variant: Variant): ByteArray =
        when (variant) {
            Variant.CLASSIC -> EscpR.remoteCmd(name)
            Variant.EXTENDED -> EscpR.remoteCmd(name, byteArrayOf(parameter.toByte()))
        }

    /**
     * Membungkus perintah perawatan persis seperti driver Epson.
     *
     * Strukturnya tidak disusun sendiri melainkan disalin dari rekaman USB.
     * Tiga blok REMOTE1 terpisah, bukan satu -- dan `JE` datang paling akhir,
     * **sesudah** form feed, didahului dua reset. Itulah yang selama ini
     * terlewat: empat susunan buatan sendiri semuanya meninggalkan kertas
     * tertahan, karena semuanya menutup pekerjaan sebelum kertas diperintahkan
     * keluar.
     */
    private fun wrapJob(command: ByteArray, keluarkanKertas: Boolean): ByteArray =
        EscpR.EXIT_PACKET_MODE +
            EscpR.INIT_PRINTER +
            EscpR.INIT_PRINTER +
            EscpR.ENTER_REMOTE_MODE +
            EscpR.timeInit() +
            EscpR.jobStart() +
            command +
            EscpR.EXIT_REMOTE_MODE +
            byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A) +
            EscpR.ENTER_REMOTE_MODE +
            EscpR.remoteCmd("VI", byteArrayOf(0x00)) +
            LOAD_DEFAULTS_KOSONG +
            EscpR.EXIT_REMOTE_MODE +
            (if (keluarkanKertas) byteArrayOf(FORM_FEED) else ByteArray(0)) +
            EscpR.INIT_PRINTER +
            EscpR.INIT_PRINTER +
            EscpR.ENTER_REMOTE_MODE +
            EscpR.jobEnd() +
            EscpR.EXIT_REMOTE_MODE

    /**
     * Membungkus perintah yang hanya bertanya, tanpa membuka pekerjaan.
     *
     * Penutup pekerjaan itulah yang memicu penanganan kertas, jadi pertanyaan
     * tidak boleh memakainya.
     */
    private fun wrapQuery(command: ByteArray): ByteArray =
        EscpR.EXIT_PACKET_MODE +
            EscpR.INIT_PRINTER +
            EscpR.INIT_PRINTER +
            EscpR.ENTER_REMOTE_MODE +
            command +
            EscpR.EXIT_REMOTE_MODE

    /**
     * `LD` versi driver: panjang nol, tanpa byte respons sama sekali.
     *
     * Berbeda dari [EscpR.loadDefaults] yang menghasilkan `LD 01 00 00`.
     * Terekam apa adanya sebagai `4C 44 00 00`, jadi ditulis apa adanya.
     */
    private val LOAD_DEFAULTS_KOSONG = byteArrayOf(0x4C, 0x44, 0x00, 0x00)

    /**
     * Dua perintah yang mendahului pembacaan status, apa adanya dari rekaman.
     *
     * Keduanya berpanjang tidak lazim -- `ST` hanya satu byte isi tanpa byte
     * respons terpisah -- jadi tidak bisa dirakit lewat [EscpR.remoteCmd] dan
     * ditulis sebagai byte langsung. Apa yang dilakukan `OT` belum diketahui;
     * ia disertakan karena driver menyertakannya, dan meniru persis sudah
     * terbukti lebih baik daripada menyusun sendiri.
     */
    private val OT_QUERY = byteArrayOf(0x4F, 0x54, 0x02, 0x00, 0x01, 0x01)
    private val ST_QUERY = byteArrayOf(0x53, 0x54, 0x01, 0x00, 0x01)

    /** ESC/P: majukan kertas ke halaman berikutnya. */
    private const val FORM_FEED: Byte = 0x0C
}

/** Perawatan yang bisa diminta pengguna. */
enum class MaintenanceTask(
    val label: String,
    /** Kalimat yang dilihat pengguna sebelum menyetujui. */
    val confirmation: String,
    val usesPaper: Boolean,
    /**
     * Apakah pilihan bentuk perintah berlaku untuk tugas ini.
     *
     * Form feed bukan perintah REMOTE1 dan tidak punya dua bentuk. Mencatatnya
     * sebagai "(EXTENDED)" di catatan hanya akan membuat orang mengira
     * pilihannya berpengaruh padahal tidak.
     */
    val usesVariant: Boolean = true,
) {
    NOZZLE_CHECK(
        label = "Cek nozzle",
        confirmation = "Printer akan mencetak pola cek nozzle. Siapkan satu " +
            "lembar kertas di baki.",
        usesPaper = true,
    ),
    HEAD_CLEANING(
        label = "Bersihkan head",
        confirmation = "Pembersihan head memakai tinta cukup banyak dan " +
            "berlangsung sekitar satu menit. Lakukan hanya kalau hasil cek " +
            "nozzle memang putus-putus, dan jangan diulang berkali-kali.",
        usesPaper = false,
    ),
    EJECT(
        label = "Keluarkan kertas",
        confirmation = "Printer akan memajukan kertas sampai keluar. Dipakai " +
            "kalau ada kertas yang tertahan di dalam.",
        usesPaper = false,
        usesVariant = false,
    );

    fun bytes(variant: Maintenance.Variant = Maintenance.DEFAULT_VARIANT): ByteArray =
        when (this) {
            EJECT -> Maintenance.ejectPage()
            NOZZLE_CHECK -> Maintenance.nozzleCheck(variant)
            HEAD_CLEANING -> Maintenance.headCleaning(variant)
        }
}
