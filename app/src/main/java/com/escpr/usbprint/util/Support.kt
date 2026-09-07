package com.escpr.usbprint.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.escpr.usbprint.escpr.PrinterSink
import java.io.File
import java.io.OutputStream

/** Menyalurkan ESC/P-R ke sebuah OutputStream, misalnya file .prn. */
class StreamSink(private val stream: OutputStream) : PrinterSink {

    var bytesWritten: Long = 0
        private set

    override fun write(data: ByteArray, offset: Int, length: Int) {
        stream.write(data, offset, length)
        bytesWritten += length
    }

    override fun flush() = stream.flush()

    override fun close() {
        runCatching { stream.flush() }
        stream.close()
    }
}

/**
 * PdfRenderer butuh berkas yang bisa di-seek, dan penyedia dokumen tidak selalu
 * menyediakannya. Menyalin ke cache sekali di depan jauh lebih andal.
 */
/**
 * Awalan nama berkas salinan. Dipakai juga oleh pembersih cache untuk mengenali
 * mana miliknya sendiri dan mana sisa cache pustaka lain.
 */
const val CACHE_PREFIX = "dokumen_"

fun copyToCache(context: Context, uri: Uri, name: String): File {
    val target = File(context.cacheDir, "$CACHE_PREFIX${name.hashCode()}_${sanitize(name)}")
    context.contentResolver.openInputStream(uri)
        ?.use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
        ?: throw java.io.IOException("Tidak bisa membuka berkas yang dipilih")
    return target
}

fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) {
            cursor.getString(column)?.let { return it }
        }
    }
    return uri.lastPathSegment ?: "dokumen"
}

fun mimeType(context: Context, uri: Uri): String =
    context.contentResolver.getType(uri) ?: ""

private fun sanitize(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(48)

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
