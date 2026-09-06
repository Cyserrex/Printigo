package com.escpr.usbprint.ui

import kotlin.math.min

/**
 * Posisi dan ukuran elemen pratinjau, dalam satuan piksel kanvas gambar.
 */
data class PreviewLayout(
    val insetX: Float,
    val insetY: Float,
    val printableWidth: Float,
    val printableHeight: Float,
    val contentLeft: Float,
    val contentTop: Float,
    val contentWidth: Float,
    val contentHeight: Float
)

/**
 * Menghitung tata letak pratinjau satu halaman.
 *
 * Aturannya sengaja dibuat sama persis dengan jalur cetak sungguhan:
 * area cetak adalah kertas dikurangi margin di keempat sisi, lalu isi
 * diskalakan seragam agar muat seluruhnya dan diletakkan di tengah.
 * Bedanya hanya satuan -- di sini piksel layar, di sana piksel printer --
 * sehingga proporsinya identik dan pratinjau bisa dipercaya.
 *
 * Margin dinyatakan dalam milimeter, jadi porsinya terhadap sisi lebar dan
 * sisi tinggi berbeda kalau kertasnya tidak persegi.
 */
fun computePreviewLayout(
    paperWidthMm: Float,
    paperHeightMm: Float,
    marginMm: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    contentWidth: Int,
    contentHeight: Int
): PreviewLayout {
    val margin = marginMm.coerceAtLeast(0f)
    val insetX = (margin / paperWidthMm) * canvasWidth
    val insetY = (margin / paperHeightMm) * canvasHeight
    val printableWidth = (canvasWidth - 2 * insetX).coerceAtLeast(1f)
    val printableHeight = (canvasHeight - 2 * insetY).coerceAtLeast(1f)

    if (contentWidth <= 0 || contentHeight <= 0) {
        return PreviewLayout(
            insetX, insetY, printableWidth, printableHeight,
            insetX, insetY, 0f, 0f
        )
    }

    val scale = min(printableWidth / contentWidth, printableHeight / contentHeight)
    val drawWidth = contentWidth * scale
    val drawHeight = contentHeight * scale

    return PreviewLayout(
        insetX = insetX,
        insetY = insetY,
        printableWidth = printableWidth,
        printableHeight = printableHeight,
        contentLeft = insetX + (printableWidth - drawWidth) / 2f,
        contentTop = insetY + (printableHeight - drawHeight) / 2f,
        contentWidth = drawWidth,
        contentHeight = drawHeight
    )
}
