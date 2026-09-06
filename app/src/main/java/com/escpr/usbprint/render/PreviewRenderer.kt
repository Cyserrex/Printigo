package com.escpr.usbprint.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Menghasilkan bitmap isi dokumen pada rasio aslinya, untuk pratinjau.
 *
 * Sengaja tidak ikut menghitung kertas dan margin: penempatannya dilakukan di
 * lapisan tampilan, sehingga menggeser slider margin atau mengganti ukuran
 * kertas tidak perlu me-render ulang apa pun.
 */
object PreviewRenderer {

    /** Sisi terpanjang bitmap pratinjau. Cukup tajam di layar, ringan di memori. */
    const val MAX_DIMENSION = 1000

    fun render(file: File, isPdf: Boolean, pageIndex: Int): Bitmap =
        if (isPdf) renderPdfPage(file, pageIndex) else renderImage(file)

    private fun renderPdfPage(file: File, pageIndex: Int): Bitmap {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val index = pageIndex.coerceIn(0, renderer.pageCount - 1)
                renderer.openPage(index).use { page ->
                    val scale = MAX_DIMENSION.toFloat() / max(page.width, page.height)
                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // PdfRenderer menggambar dengan latar tembus pandang.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                }
            }
        }
    }

    private fun renderImage(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Berkas gambar tidak bisa dibaca")
        }

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DIMENSION &&
            bounds.outHeight / (sample * 2) >= MAX_DIMENSION
        ) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("Gambar gagal diurai")

        val rotation = exifRotation(file)
        if (rotation == 0) return decoded

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, matrix, true
        )
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private fun exifRotation(file: File): Int = runCatching {
        when (ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}
