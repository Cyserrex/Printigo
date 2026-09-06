package com.escpr.usbprint.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import com.escpr.usbprint.layout.RectMm
import com.escpr.usbprint.layout.SheetItem
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Satu foto beserta berkas sumbernya. */
data class SheetPhoto(
    val item: SheetItem,
    val file: File,
)

/**
 * Satu lembar berisi sejumlah foto, masing-masing pada posisinya sendiri.
 *
 * Berbeda dengan [ImagePageSource] yang menaruh satu isi di kotak tujuan,
 * sumber ini menerima kotak tujuan seluas **area cetak** lalu menempatkan tiap
 * foto di dalamnya menurut posisi milimeternya. Rasio kotak tujuan sama dengan
 * rasio area cetak, jadi jalur cetak yang sudah ada tidak perlu tahu apa-apa
 * tentang mode lembar.
 */
class SheetPageSource(
    private val photos: List<SheetPhoto>,
    private val printableMm: RectMm,
) : PageSource {

    override val pageCount: Int = 1

    private val destination = RectF()
    private var pxPerMm = 1f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val bitmaps = HashMap<Long, Bitmap>()

    override fun openPage(index: Int) = Unit

    override fun contentAspect(): Float = printableMm.width / printableMm.height

    override fun setDestination(destination: RectF) {
        this.destination.set(destination)
        // Kotak tujuan meliputi seluruh area cetak, jadi skalanya bisa dibaca
        // langsung dari perbandingan lebar piksel terhadap lebar milimeter.
        pxPerMm = destination.width() / printableMm.width
        decodeAll()
    }

    /**
     * Menguraikan semua foto sekaligus, dengan anggaran piksel bersama.
     *
     * Tiap foto diberi jatah sebanding luasnya di kertas. Tanpa anggaran ini,
     * enam foto beresolusi tinggi pada 720 dpi cukup untuk menghabiskan heap
     * HP kelas menengah.
     */
    private fun decodeAll() {
        recycleAll()
        if (photos.isEmpty()) return

        val areas = photos.map { photo ->
            val width = photo.item.rect.width * pxPerMm
            val height = photo.item.rect.height * pxPerMm
            (width * height).toDouble().coerceAtLeast(1.0)
        }
        val totalArea = areas.sum()

        photos.forEachIndexed { index, photo ->
            val share = (areas[index] / totalArea) * MAX_TOTAL_PIXELS
            val targetWidth = sqrt(share * photo.item.aspect).roundToInt().coerceAtLeast(1)
            val targetHeight = (targetWidth / photo.item.aspect).roundToInt().coerceAtLeast(1)

            // Tidak ada gunanya menguraikan lebih besar dari ukurannya di kertas.
            val drawWidth = (photo.item.rect.width * pxPerMm).roundToInt().coerceAtLeast(1)
            val drawHeight = (photo.item.rect.height * pxPerMm).roundToInt().coerceAtLeast(1)

            decode(photo, minOf(targetWidth, drawWidth), minOf(targetHeight, drawHeight))
                ?.let { bitmaps[photo.item.id] = it }
        }
    }

    private fun decode(photo: SheetPhoto, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth &&
            bounds.outHeight / (sample * 2) >= targetHeight
        ) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var decoded = BitmapFactory.decodeFile(photo.file.absolutePath, options) ?: return null

        val rotation = readExifRotation(photo.file)
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height, matrix, true
            )
            if (rotated !== decoded) decoded.recycle()
            decoded = rotated
        }
        return decoded
    }

    override fun renderBand(bitmap: Bitmap, bandTop: Int) {
        if (photos.isEmpty()) return
        val canvas = Canvas(bitmap)
        canvas.translate(0f, -bandTop.toFloat())

        for (photo in photos) {
            val source = bitmaps[photo.item.id] ?: continue
            val rect = photo.item.rect
            // Milimeter di kertas -> piksel di dalam area cetak.
            val left = (rect.left - printableMm.left) * pxPerMm
            val top = (rect.top - printableMm.top) * pxPerMm
            canvas.drawBitmap(
                source,
                null,
                RectF(left, top, left + rect.width * pxPerMm, top + rect.height * pxPerMm),
                paint,
            )
        }
    }

    override fun close() = recycleAll()

    private fun recycleAll() {
        bitmaps.values.forEach { runCatching { it.recycle() } }
        bitmaps.clear()
    }

    private companion object {
        /** 12 juta piksel ARGB = sekitar 48 MB, dibagi rata menurut luas. */
        const val MAX_TOTAL_PIXELS = 12_000_000.0

        fun readExifRotation(file: File): Int = runCatching {
            when (ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
    }
}
