package com.escpr.usbprint.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.io.File
import kotlin.math.min

/**
 * Sumber halaman yang bisa digambar pita demi pita (band).
 *
 * Halaman A4 pada 720 dpi berukuran sekitar 5800 x 8250 piksel; kalau
 * di-render sekaligus butuh 190 MB dan aplikasi pasti mati kehabisan memori.
 * Karena itu semua penggambaran dilakukan per pita setinggi seratusan baris.
 */
interface PageSource : Closeable {
    val pageCount: Int

    /** Dipanggil sekali, memberi tahu ukuran area cetak dalam piksel. */
    fun prepare(pageWidthPx: Int, pageHeightPx: Int)

    fun openPage(index: Int)

    /**
     * Menggambar bagian halaman mulai baris [bandTop] ke dalam [bitmap].
     * [bitmap] sudah diisi putih oleh pemanggil.
     */
    fun renderBand(bitmap: Bitmap, bandTop: Int)

    fun closePage() {}
}

// ------------------------------------------------------------------- PDF

class PdfPageSource(file: File) : PageSource {

    private val descriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)

    override val pageCount: Int = renderer.pageCount

    private var page: PdfRenderer.Page? = null
    private var targetWidth = 0
    private var targetHeight = 0
    private val baseMatrix = Matrix()

    override fun prepare(pageWidthPx: Int, pageHeightPx: Int) {
        targetWidth = pageWidthPx
        targetHeight = pageHeightPx
    }

    override fun openPage(index: Int) {
        closePage()
        val p = renderer.openPage(index)
        page = p

        // p.width / p.height dalam poin (1/72 inci). Skalakan agar pas di area
        // cetak dengan rasio terjaga, lalu ketengahkan.
        val scale = min(
            targetWidth.toFloat() / p.width,
            targetHeight.toFloat() / p.height
        )
        baseMatrix.reset()
        baseMatrix.setScale(scale, scale)
        baseMatrix.postTranslate(
            (targetWidth - p.width * scale) / 2f,
            (targetHeight - p.height * scale) / 2f
        )
    }

    override fun renderBand(bitmap: Bitmap, bandTop: Int) {
        val p = page ?: return
        val matrix = Matrix(baseMatrix)
        matrix.postTranslate(0f, -bandTop.toFloat())
        p.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
    }

    override fun closePage() {
        page?.close()
        page = null
    }

    override fun close() {
        closePage()
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

// ---------------------------------------------------------------- gambar

class ImagePageSource(private val file: File) : PageSource {

    override val pageCount: Int = 1

    private var bitmap: Bitmap? = null
    private val destination = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    override fun prepare(pageWidthPx: Int, pageHeightPx: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Berkas gambar tidak bisa dibaca")
        }

        val rotation = readExifRotation()

        val options = BitmapFactory.Options().apply {
            inSampleSize = chooseSampleSize(
                bounds.outWidth, bounds.outHeight,
                if (rotation % 180 == 0) pageWidthPx else pageHeightPx,
                if (rotation % 180 == 0) pageHeightPx else pageWidthPx
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("Gambar gagal diurai")

        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height, matrix, true
            )
            if (rotated !== decoded) decoded.recycle()
            decoded = rotated
        }
        bitmap = decoded

        // Muat sebesar mungkin di dalam area cetak tanpa mengubah rasio.
        val scale = min(
            pageWidthPx.toFloat() / decoded.width,
            pageHeightPx.toFloat() / decoded.height
        )
        val drawWidth = decoded.width * scale
        val drawHeight = decoded.height * scale
        val left = (pageWidthPx - drawWidth) / 2f
        val top = (pageHeightPx - drawHeight) / 2f
        destination.set(left, top, left + drawWidth, top + drawHeight)
    }

    override fun openPage(index: Int) = Unit

    override fun renderBand(bitmap: Bitmap, bandTop: Int) {
        val source = this.bitmap ?: return
        val canvas = Canvas(bitmap)
        canvas.translate(0f, -bandTop.toFloat())
        canvas.drawBitmap(source, null, destination, paint)
    }

    override fun close() {
        bitmap?.recycle()
        bitmap = null
    }

    private fun readExifRotation(): Int = runCatching {
        when (ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private companion object {
        /** Batas piksel hasil decode supaya foto besar tidak menghabiskan heap. */
        const val MAX_DECODED_PIXELS = 6_000_000

        fun chooseSampleSize(
            sourceWidth: Int, sourceHeight: Int,
            targetWidth: Int, targetHeight: Int
        ): Int {
            var sample = 1
            // Kecilkan selama masih di atas resolusi yang benar-benar dibutuhkan.
            while (sourceWidth / (sample * 2) >= targetWidth &&
                sourceHeight / (sample * 2) >= targetHeight
            ) sample *= 2
            // Lalu pastikan tidak melewati batas memori.
            while ((sourceWidth / sample).toLong() * (sourceHeight / sample) > MAX_DECODED_PIXELS) {
                sample *= 2
            }
            return sample
        }
    }
}
