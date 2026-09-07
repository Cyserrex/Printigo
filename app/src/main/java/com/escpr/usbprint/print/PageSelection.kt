package com.escpr.usbprint.print

/** Halaman mana saja dari sebuah PDF yang akan dicetak. */
enum class PageSelectionMode { ALL, CURRENT, RANGE }

data class PageSelection(
    val mode: PageSelectionMode = PageSelectionMode.ALL,
    /** Nomor halaman seperti yang dilihat pengguna, dimulai dari 1. */
    val fromPage: Int = 1,
    val toPage: Int = 1,
)

/**
 * Mengubah pilihan pengguna menjadi daftar indeks halaman, dimulai dari nol.
 *
 * Nomor di layar dimulai dari 1 sedangkan PdfRenderer memakai indeks dari 0,
 * dan pergeseran satu itu sumber kesalahan yang mudah lolos. Karena itu
 * konversinya dikerjakan di satu fungsi murni yang diuji, bukan tersebar.
 *
 * Rentang yang terbalik dibetulkan, bukan ditolak: pengguna yang mengetik
 * "5 sampai 2" jelas bermaksud halaman 2 sampai 5.
 */
fun resolvePages(
    selection: PageSelection,
    currentPageIndex: Int,
    pageCount: Int,
): List<Int> {
    if (pageCount <= 0) return emptyList()
    val all = (0 until pageCount).toList()

    return when (selection.mode) {
        PageSelectionMode.ALL -> all

        PageSelectionMode.CURRENT ->
            listOf(currentPageIndex.coerceIn(0, pageCount - 1))

        PageSelectionMode.RANGE -> {
            val a = selection.fromPage.coerceIn(1, pageCount)
            val b = selection.toPage.coerceIn(1, pageCount)
            val first = minOf(a, b)
            val last = maxOf(a, b)
            (first - 1..last - 1).toList()
        }
    }
}
