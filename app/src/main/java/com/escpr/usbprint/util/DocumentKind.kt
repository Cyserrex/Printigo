package com.escpr.usbprint.util

/**
 * Jenis berkas yang dikenali aplikasi.
 *
 * Dipisah menjadi fungsi murni supaya bisa diuji, dan supaya keputusan
 * "didukung atau tidak" ada di satu tempat -- bukan tersebar sebagai
 * pemeriksaan `endsWith(".pdf")` di beberapa berkas.
 */
enum class DocumentKind {
    IMAGE,
    PDF,

    /**
     * Word, Excel, PowerPoint, OpenDocument, dan RTF.
     *
     * Dikenali secara khusus bukan untuk dibuka, melainkan supaya bisa ditolak
     * dengan penjelasan yang benar. Android tidak punya perender bawaan untuk
     * format ini, tidak seperti PDF yang punya PdfRenderer.
     */
    OFFICE,

    /** Belum jelas. Dicoba sebagai gambar, karena sebagian penyedia dokumen
     *  tidak melaporkan tipe MIME sama sekali. */
    UNKNOWN,
}

private val OFFICE_EXTENSIONS = setOf(
    "doc", "docx", "docm", "dot", "dotx",
    "xls", "xlsx", "xlsm", "xlt", "xltx", "csv",
    "ppt", "pptx", "pps", "ppsx", "pot", "potx",
    "odt", "ods", "odp", "rtf",
)

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif",
)

/**
 * Menentukan jenis berkas dari tipe MIME dan namanya.
 *
 * Keduanya dipakai karena masing-masing bisa kosong: penyedia dokumen kadang
 * tidak melaporkan MIME, dan berkas kadang tidak berekstensi.
 */
fun classifyDocument(mimeType: String, fileName: String): DocumentKind {
    val mime = mimeType.lowercase()
    val extension = fileName.substringAfterLast('.', "").lowercase()

    if (mime.contains("pdf") || extension == "pdf") return DocumentKind.PDF
    if (mime.startsWith("image/") || extension in IMAGE_EXTENSIONS) return DocumentKind.IMAGE

    val officeByMime = mime.contains("word") || mime.contains("excel") ||
        mime.contains("powerpoint") || mime.contains("officedocument") ||
        mime.contains("opendocument") || mime.contains("msword") ||
        mime.contains("ms-excel") || mime.contains("ms-powerpoint") ||
        mime.contains("rtf")
    if (officeByMime || extension in OFFICE_EXTENSIONS) return DocumentKind.OFFICE

    return DocumentKind.UNKNOWN
}
