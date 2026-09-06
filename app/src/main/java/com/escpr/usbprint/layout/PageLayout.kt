package com.escpr.usbprint.layout

import kotlin.math.max
import kotlin.math.min

/**
 * Penempatan isi di atas kertas.
 *
 * Dinyatakan relatif terhadap ukuran "muat" (isi diperbesar sebesar mungkin
 * tanpa keluar area cetak), bukan dalam piksel. Dengan begitu penempatan yang
 * sama tetap berlaku ketika pengguna mengganti kertas atau resolusi, dan
 * pratinjau di layar bisa memakai angka yang sama persis dengan jalur cetak.
 */
data class ContentPlacement(
    /** 1.0 berarti pas memenuhi area cetak. */
    val scale: Float = 1f,
    /** Pergeseran pusat isi dari pusat kertas, dalam milimeter. */
    val offsetXmm: Float = 0f,
    val offsetYmm: Float = 0f,
    /** True setelah pengguna menggeser atau mencubit sendiri. */
    val manual: Boolean = false,
) {
    companion object {
        val Fit = ContentPlacement()

        /** Batas perbesaran, supaya tidak bisa digeser sampai hilang dari kertas. */
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 8f
    }
}

/** Kotak dalam milimeter, diukur dari sudut kiri-atas kertas. */
data class RectMm(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

/**
 * Hasil perhitungan tata letak satu halaman, semuanya dalam milimeter.
 *
 * [overflow*] adalah seberapa jauh isi keluar dari area cetak di tiap sisi.
 * Nilainya nol kalau aman. Bagian yang keluar itulah yang akan terpotong,
 * karena printer tidak bisa menaruh tinta di luar area cetaknya.
 */
data class PageLayout(
    val paperWidthMm: Float,
    val paperHeightMm: Float,
    val printable: RectMm,
    val content: RectMm,
    val overflowLeftMm: Float,
    val overflowTopMm: Float,
    val overflowRightMm: Float,
    val overflowBottomMm: Float,
) {
    val hasContent: Boolean get() = content.width > 0f && content.height > 0f

    val hasOverflow: Boolean
        get() = hasContent && (
            overflowLeftMm > TOLERANCE_MM || overflowTopMm > TOLERANCE_MM ||
                overflowRightMm > TOLERANCE_MM || overflowBottomMm > TOLERANCE_MM
            )

    /** Jarak isi ke tepi kertas di tiap sisi -- margin sesungguhnya yang terlihat. */
    val marginLeftMm: Float get() = content.left
    val marginTopMm: Float get() = content.top
    val marginRightMm: Float get() = paperWidthMm - content.right
    val marginBottomMm: Float get() = paperHeightMm - content.bottom

    companion object {
        /** Selisih di bawah ini dianggap nol; menghindari peringatan karena pembulatan. */
        const val TOLERANCE_MM = 0.05f
    }
}

/**
 * Menghitung tata letak satu halaman.
 *
 * [contentAspect] adalah lebar dibagi tinggi isi. Nilai <= 0 berarti belum ada
 * dokumen, dan hasilnya hanya berisi geometri kertas.
 */
fun computePageLayout(
    paperWidthMm: Float,
    paperHeightMm: Float,
    marginMm: Float,
    contentAspect: Float,
    placement: ContentPlacement = ContentPlacement.Fit,
): PageLayout {
    val margin = marginMm.coerceIn(0f, min(paperWidthMm, paperHeightMm) / 2f - 1f)
    val printable = RectMm(
        left = margin,
        top = margin,
        width = (paperWidthMm - 2 * margin).coerceAtLeast(1f),
        height = (paperHeightMm - 2 * margin).coerceAtLeast(1f),
    )

    if (contentAspect <= 0f || !contentAspect.isFinite()) {
        return PageLayout(
            paperWidthMm, paperHeightMm, printable,
            content = RectMm(printable.left, printable.top, 0f, 0f),
            overflowLeftMm = 0f, overflowTopMm = 0f,
            overflowRightMm = 0f, overflowBottomMm = 0f,
        )
    }

    // Ukuran "muat": sebesar mungkin di dalam area cetak, rasio dijaga.
    var fitWidth = printable.width
    var fitHeight = fitWidth / contentAspect
    if (fitHeight > printable.height) {
        fitHeight = printable.height
        fitWidth = fitHeight * contentAspect
    }

    val scale = placement.scale.coerceIn(ContentPlacement.MIN_SCALE, ContentPlacement.MAX_SCALE)
    val drawWidth = fitWidth * scale
    val drawHeight = fitHeight * scale

    val centerX = paperWidthMm / 2f + placement.offsetXmm
    val centerY = paperHeightMm / 2f + placement.offsetYmm
    val content = RectMm(
        left = centerX - drawWidth / 2f,
        top = centerY - drawHeight / 2f,
        width = drawWidth,
        height = drawHeight,
    )

    return PageLayout(
        paperWidthMm = paperWidthMm,
        paperHeightMm = paperHeightMm,
        printable = printable,
        content = content,
        overflowLeftMm = max(0f, printable.left - content.left),
        overflowTopMm = max(0f, printable.top - content.top),
        overflowRightMm = max(0f, content.right - printable.right),
        overflowBottomMm = max(0f, content.bottom - printable.bottom),
    )
}

/**
 * Kotak tujuan penggambaran dalam piksel, diukur dari sudut kiri-atas **area
 * cetak** -- bukan dari sudut kertas, karena raster yang dikirim ke printer
 * hanya meliputi area cetak.
 *
 * Hasilnya boleh keluar batas 0..lebar/tinggi; bagian itu memang harus
 * terpotong, dan pemotongan terjadi sendiri saat digambar ke bitmap pita.
 */
fun PageLayout.contentRectInPrintablePx(dpi: Int): FloatArray {
    val pxPerMm = dpi / 25.4f
    val left = (content.left - printable.left) * pxPerMm
    val top = (content.top - printable.top) * pxPerMm
    return floatArrayOf(left, top, left + content.width * pxPerMm, top + content.height * pxPerMm)
}

/**
 * Membatasi penempatan agar tetap masuk akal.
 *
 * Skala dijaga di rentang yang wajar, dan pusat isi tidak boleh keluar dari
 * kertas. Tanpa batas kedua itu, satu geseran panjang bisa membuang gambar
 * ke luar layar dan pengguna tidak punya cara mengembalikannya selain
 * mengulang dari awal.
 */
fun ContentPlacement.clampedTo(paperWidthMm: Float, paperHeightMm: Float): ContentPlacement =
    copy(
        scale = scale.coerceIn(ContentPlacement.MIN_SCALE, ContentPlacement.MAX_SCALE),
        offsetXmm = offsetXmm.coerceIn(-paperWidthMm / 2f, paperWidthMm / 2f),
        offsetYmm = offsetYmm.coerceIn(-paperHeightMm / 2f, paperHeightMm / 2f),
    )
