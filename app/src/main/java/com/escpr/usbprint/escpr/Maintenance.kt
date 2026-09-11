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
     * [CLASSIC] tanpa parameter, seperti escputil. [EXTENDED] dengan satu byte
     * parameter, dipakai sebagian model yang lebih baru. Dipisah supaya kalau
     * L3110 ternyata butuh yang satunya, perubahannya satu nilai -- bukan
     * membongkar ulang urutan byte.
     */
    enum class Variant { CLASSIC, EXTENDED }

    /** Pola pembersihan. Nol berarti seluruh warna. */
    const val CLEAN_ALL = 0x00

    /** Pola cek nozzle standar. */
    const val NOZZLE_PATTERN = 0x00

    /**
     * Mencetak pola cek nozzle.
     *
     * Satu lembar kertas dipakai. Polanya digambar printer sendiri, jadi tidak
     * ada gambar yang dikirim dari HP.
     */
    fun nozzleCheck(variant: Variant = Variant.CLASSIC): ByteArray =
        wrap(command("NC", NOZZLE_PATTERN, variant))

    /**
     * Menjalankan pembersihan head.
     *
     * Tidak memakai kertas, tapi **memakai tinta**. Printer akan berbunyi dan
     * sibuk sekitar setengah menit hingga dua menit; selama itu ia tidak akan
     * menerima pekerjaan cetak.
     */
    fun headCleaning(variant: Variant = Variant.CLASSIC): ByteArray =
        wrap(command("CH", CLEAN_ALL, variant))

    /**
     * Meminta printer mengirimkan laporan statusnya.
     *
     * Tanpa ini, membaca dari printer hanya menangkap apa yang kebetulan
     * tersisa di antrian -- biasanya tidak ada apa-apa, atau sisa balasan dari
     * pekerjaan sebelumnya. Perintah `ST` menyuruh printer menyusun laporan
     * baru, dan laporan itulah yang memuat sisa tinta.
     *
     * Tidak memakai kertas maupun tinta, dan tidak menggerakkan apa pun.
     */
    fun statusRequest(): ByteArray = wrapQuery(EscpR.remoteCmd("ST", byteArrayOf(0x01)))

    private fun command(name: String, parameter: Int, variant: Variant): ByteArray =
        when (variant) {
            Variant.CLASSIC -> EscpR.remoteCmd(name)
            Variant.EXTENDED -> EscpR.remoteCmd(name, byteArrayOf(parameter.toByte()))
        }

    /**
     * Membungkus satu perintah REMOTE1 menjadi pekerjaan yang berdiri sendiri.
     *
     * Reset dikirim dua kali sebelum masuk remote mode. Itu bukan kelebihan:
     * kalau printer sedang tertinggal di tengah keadaan dari pekerjaan
     * sebelumnya, reset pertama yang membereskannya dan reset kedua yang
     * benar-benar terbaca sebagai reset.
     */
    private fun wrap(command: ByteArray): ByteArray =
        wrapQuery(EscpR.jobStart() + command + EscpR.loadDefaults() + EscpR.jobEnd())

    /**
     * Membungkus perintah yang hanya bertanya, tanpa membuka pekerjaan.
     *
     * Membaca status bukan mencetak. Membungkusnya dengan `JS` dan `JE` berarti
     * setiap kali sisa tinta diperiksa, printer membuka lalu menutup sebuah
     * pekerjaan -- dan pekerjaan yang ditutup adalah persis yang memicu
     * penanganan kertas. Menanyakan sesuatu tidak boleh menggerakkan apa pun.
     */
    private fun wrapQuery(command: ByteArray): ByteArray =
        EscpR.EXIT_PACKET_MODE +
            EscpR.INIT_PRINTER +
            EscpR.INIT_PRINTER +
            EscpR.ENTER_REMOTE_MODE +
            command +
            EscpR.EXIT_REMOTE_MODE

    /**
     * Mengeluarkan kertas yang tertahan di dalam printer.
     *
     * Form feed adalah perintah ESC/P paling tua dan paling sederhana: majukan
     * kertas sampai keluar. Disediakan sebagai tombol tersendiri karena kertas
     * bisa tertahan oleh sebab apa pun, dan satu-satunya jalan keluar sebelum
     * ini adalah mematikan printer.
     */
    fun ejectPage(): ByteArray = EscpR.EXIT_PACKET_MODE + byteArrayOf(FORM_FEED)

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

    fun bytes(variant: Maintenance.Variant = Maintenance.Variant.CLASSIC): ByteArray =
        when (this) {
            EJECT -> Maintenance.ejectPage()
            NOZZLE_CHECK -> Maintenance.nozzleCheck(variant)
            HEAD_CLEANING -> Maintenance.headCleaning(variant)
        }
}
