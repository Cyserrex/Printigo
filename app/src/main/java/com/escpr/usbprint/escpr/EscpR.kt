package com.escpr.usbprint.escpr

import androidx.annotation.StringRes
import com.escpr.usbprint.R

import java.util.Calendar

/**
 * Perintah tingkat byte untuk ESC/P-R, bahasa raster yang dipakai printer
 * inkjet Epson modern termasuk L3110.
 *
 * Panjang setiap perintah di sini sudah dicocokkan dengan konstanta di driver
 * resmi Epson (epson-inkjet-printer-escpr): setj = 22 byte data, setq = 9,
 * sets = 5, endp = 1, sttp/endj = 0.
 *
 * Bentuk umum perintah raster:  ESC <huruf> <panjang data, LE32> <kode 4 huruf> <data>
 * Bentuk umum perintah REMOTE1: <nama 2 huruf> <panjang+1, LE16> <0x00> <data>
 */
object EscpR {

    private const val ESC: Byte = 0x1B
    private val EMPTY = ByteArray(0)

    // ---------------------------------------------------------------- mode

    /** Keluar dari "packet mode" IEEE-1284 kalau printer sedang di dalamnya. */
    val EXIT_PACKET_MODE: ByteArray =
        byteArrayOf(0, 0, 0, ESC, 0x01) + "@EJL 1284.4\n@EJL     \n".toAscii()

    /** ESC @ - reset printer ke kondisi awal. */
    val INIT_PRINTER: ByteArray = byteArrayOf(ESC, '@'.code.toByte())

    val ENTER_REMOTE_MODE: ByteArray =
        byteArrayOf(ESC, '('.code.toByte(), 'R'.code.toByte(), 0x08, 0, 0) + "REMOTE1".toAscii()

    val EXIT_REMOTE_MODE: ByteArray = byteArrayOf(ESC, 0, 0, 0)

    val ENTER_ESCPR_MODE: ByteArray =
        byteArrayOf(ESC, '('.code.toByte(), 'R'.code.toByte(), 0x06, 0, 0) + "ESCPR".toAscii()

    // ------------------------------------------------------------ perakit

    /** ESC <huruf> <len LE32> <kode> <data> */
    fun rasterCmd(letter: Char, code: String, data: ByteArray = EMPTY): ByteArray =
        concat(byteArrayOf(ESC, letter.code.toByte()), le32(data.size), code.toAscii(), data)

    /** <nama> <len+1 LE16> <byte respons> <data> - hanya sah di dalam REMOTE1. */
    fun remoteCmd(name: String, data: ByteArray = EMPTY): ByteArray =
        concat(name.toAscii(), le16(data.size + 1), byteArrayOf(0), data)

    // -------------------------------------------------- perintah REMOTE1

    fun timeInit(now: Calendar = Calendar.getInstance()): ByteArray = remoteCmd(
        "TI",
        concat(
            be16(now.get(Calendar.YEAR)),
            byteArrayOf(
                (now.get(Calendar.MONTH) + 1).toByte(),
                now.get(Calendar.DAY_OF_MONTH).toByte(),
                now.get(Calendar.HOUR_OF_DAY).toByte(),
                now.get(Calendar.MINUTE).toByte(),
                now.get(Calendar.SECOND).toByte()
            )
        )
    )

    fun jobStart(): ByteArray = remoteCmd("JS", byteArrayOf(0, 0, 0))

    fun jobHeader(name: String, jobId: Int = 0): ByteArray =
        remoteCmd("JH", concat(byteArrayOf(0), be32(jobId), name.toAscii()))

    /** platform 4 = Linux; nilai ini hanya informasi, printer tidak rewel. */
    fun hardwareDevice(platform: Int = 4): ByteArray =
        remoteCmd("HD", byteArrayOf(0x03, platform.toByte()))

    /**
     * dst=1 src=0 adalah rear feeder.
     *
     * Dipatok karena printer yang jadi acuan hanya punya satu jalur kertas.
     * Model dengan baki depan atau baki kedua butuh nilai lain, dan itu berarti
     * jalur kertas harus jadi pilihan -- bukan sekadar mengganti angka di sini.
     */
    fun paperPath(dst: Int = 1, src: Int = 0): ByteArray =
        remoteCmd("PP", byteArrayOf(dst.toByte(), src.toByte()))

    fun loadDefaults(): ByteArray = remoteCmd("LD")

    fun jobEnd(): ByteArray = remoteCmd("JE")

    // ------------------------------------------------- perintah ESC/P-R

    fun setQuality(
        mediaType: MediaType,
        quality: Quality,
        colorMode: ColorMode,
        brightness: Int = 0,
        contrast: Int = 0,
        saturation: Int = 0
    ): ByteArray {
        val data = concat(
            byteArrayOf(
                mediaType.code.toByte(),
                quality.code.toByte(),
                colorMode.code.toByte(),
                clampSigned(brightness),
                clampSigned(contrast),
                clampSigned(saturation),
                0                       // palet: 0 = full colour
            ),
            be16(0)                     // panjang tabel palet = 0
        )
        require(data.size == 9)
        return rasterCmd('q', "setq", data)
    }

    /**
     * Menetapkan geometri halaman. Semua satuan dalam piksel pada [dpi].
     * Kembalian: perintah siap kirim.
     */
    fun setJob(
        paperWidthPx: Int,
        paperHeightPx: Int,
        marginTopPx: Int,
        marginLeftPx: Int,
        printableWidthPx: Int,
        printableHeightPx: Int,
        dpi: Dpi,
        direction: PrintDirection = PrintDirection.BIDIRECTIONAL
    ): ByteArray {
        val data = concat(
            be32(paperWidthPx), be32(paperHeightPx),
            be16(marginTopPx), be16(marginLeftPx),
            be32(printableWidthPx), be32(printableHeightPx),
            byteArrayOf(dpi.code.toByte(), direction.code.toByte())
        )
        require(data.size == 22)
        return rasterCmd('j', "setj", data)
    }

    fun startPage(): ByteArray = rasterCmd('p', "sttp")

    fun pageNumber(page: Int): ByteArray =
        rasterCmd('p', "setn", byteArrayOf(page.coerceIn(1, 99).toByte()))

    fun endPage(pagesRemaining: Int): ByteArray =
        rasterCmd('p', "endp", byteArrayOf(pagesRemaining.coerceIn(0, 99).toByte()))

    fun endJob(): ByteArray = rasterCmd('j', "endj")

    // ----------------------------------------------------------- utilitas

    private fun clampSigned(v: Int): Byte = v.coerceIn(-50, 50).toByte()

    private fun String.toAscii(): ByteArray = ByteArray(length) { this[it].code.toByte() }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

    private fun le32(v: Int) =
        byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun be32(v: Int) =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }
}

// ------------------------------------------------------------------ enum

enum class Quality(
    val code: Int,
    @StringRes val label: Int,
    @StringRes val shortLabel: Int,
) {
    DRAFT(0, R.string.quality_draft, R.string.quality_draft_short),
    NORMAL(1, R.string.quality_normal, R.string.quality_normal),
    HIGH(2, R.string.quality_high, R.string.quality_high_short)
}

enum class ColorMode(val code: Int, @StringRes val label: Int) {
    COLOR(0, R.string.color_colour),
    MONO(1, R.string.color_mono)
}

enum class MediaType(val code: Int, @StringRes val label: Int) {
    PLAIN(0, R.string.media_plain),
    MATTE(5, R.string.media_matte),
    PHOTO(6, R.string.media_photo),
    GLOSSY_PHOTO(43, R.string.media_glossy)
}

enum class PrintDirection(val code: Int, @StringRes val label: Int) {
    BIDIRECTIONAL(0, R.string.direction_bidirectional),
    UNIDIRECTIONAL(1, R.string.direction_unidirectional)
}

/** Nilai `ir` pada perintah setj. Hanya empat resolusi ini yang dikenal ESC/P-R. */
enum class Dpi(val value: Int, val code: Int, val label: String) {
    DPI300(300, 2, "300 dpi"),
    DPI360(360, 0, "360 dpi"),
    DPI600(600, 3, "600 dpi"),
    DPI720(720, 1, "720 dpi")
}

enum class PaperSize(
    @StringRes val label: Int,
    @StringRes val shortLabel: Int,
    val widthMm: Float,
    val heightMm: Float
) {
    A4(R.string.paper_a4, R.string.paper_a4_short, 210f, 297f),
    LETTER(R.string.paper_letter, R.string.paper_letter_short, 215.9f, 279.4f),
    LEGAL(R.string.paper_legal, R.string.paper_legal_short, 215.9f, 355.6f),
    A5(R.string.paper_a5, R.string.paper_a5_short, 148f, 210f),
    A6(R.string.paper_a6, R.string.paper_a6_short, 105f, 148f),
    B5(R.string.paper_b5, R.string.paper_b5_short, 176f, 250f),
    PHOTO_4R(R.string.paper_4r, R.string.paper_4r_short, 101.6f, 152.4f),
    POSTCARD(R.string.paper_postcard, R.string.paper_postcard_short, 100f, 148f)
}
