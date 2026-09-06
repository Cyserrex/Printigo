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
import kotlin.math.roundToInt

/**
 * Sumber halaman yang bisa digambar pita demi pita (band).
 *
 * Halaman A4 pada 720 dpi berukuran sekitar 5800 x 8250 piksel; kalau
 * di-render sekaligus butuh 190 MB dan aplikasi pasti mati kehabisan memori.
 * Karena itu semua penggambaran dilakukan per pita setinggi seratusan baris.
 *
 * Sumber ini tidak lagi memutuskan sendiri di mana isi ditaruh. Kotak
 * tujuannya ditentukan dari luar lewat [setDestination], sehingga penempatan
 * yang diatur pengguna di pratinjau dipakai apa adanya saat mencetak. Kotak
 * itu boleh melewati batas area cetak; bagian yang lewat akan terpotong
 * sendiri karena bitmap pita hanya seluas area cetak.
 *
 * Urutan pemakaian: openPage -> contentAspect -> setDestination -> renderBand*
 */
interface PageSource : Closeable {
    val pageCount: Int

    fun openPage(index: Int)

    /** Lebar dibagi tinggi isi halaman yang sedang terbuka. */
    fun contentAspect(): Float

    /** Kotak tujuan dalam piksel, diukur dari sudut kiri-atas area cetak. */
    fun setDestination(destination: RectF)

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
    private val baseMatrix = Matrix()

    override fun openPage(index: Int) {
        closePage()
        page = renderer.openPage(index)
    }

    override fun contentAspect(): Float {
        val p = page ?: return 0f
        if (p.height <= 0) return 0f
        return p.width.toFloat() / p.height.toFloat()
    }

    override fun setDestination(destination: RectF) {
        val p = page ?: return
        // p.width / p.height dalam poin (1/72 inci).
        val scaleX = destination.width() / p.width
        val scaleY = destination.height() / p.height
        baseMatrix.reset()
        baseMatrix.setScale(scaleX, scaleY)
        baseMatrix.postTranslate(destination.left, destination.top)
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

    private val sourceWidth: Int
    private val sourceHeight: Int
    private val rotation: Int

    private var bitmap: Bitmap? = null
    private val destination = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    init {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Berkas gambar tidak bisa dibaca")
        }
        rotation = readExifRotation()
        // Rasio dilaporkan setelah rotasi EXIF diterapkan, sama seperti yang
        // dilihat pengguna di pratinjau.
        val upright = rotation % 180 != 0
        sourceWidth = if (upright) bounds.outHeight else bounds.outWidth
        sourceHeight = if (upright) bounds.outWidth else bounds.outHeight
    }

    override fun openPage(index: Int) = Unit

    override fun contentAspect(): Float = sourceWidth.toFloat() / sourceHeight.toFloat()

    override fun setDestination(destination: RectF) {
        this.destination.set(destination)
        if (bitmap == null) decode(destination)
    }

    private fun decode(destination: RectF) {
        val targetWidth = destination.width().roundToInt().coerceAtLeast(1)
        val targetHeight = destination.height().roundToInt().coerceAtLeast(1)

        val options = BitmapFactory.Options().apply {
            inSampleSize = chooseSampleSize(sourceWidth, sourceHeight, targetWidth, targetHeight)
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
    }

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
