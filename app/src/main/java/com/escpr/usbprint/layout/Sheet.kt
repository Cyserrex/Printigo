package com.escpr.usbprint.layout

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Satu foto yang ditempatkan di atas kertas.
 *
 * Posisinya disimpan sebagai kotak dalam milimeter, bukan sebagai skala relatif
 * seperti [ContentPlacement]. Untuk satu isi per kertas, "sebesar mungkin di
 * dalam area cetak" adalah acuan yang masuk akal; begitu ada banyak foto,
 * acuan itu hilang dan yang tersisa hanyalah posisi sesungguhnya di kertas.
 */
data class SheetItem(
    val id: Long,
    /** Lebar dibagi tinggi foto aslinya, sebelum diputar. */
    val aspect: Float,
    /** Kotak foto di atas kertas, sudah termasuk hasil putaran. */
    val rect: RectMm,
    /** Kelipatan 90 derajat: 0, 90, 180, atau 270. */
    val rotationDegrees: Int = 0,
    /**
     * Lembar keberapa foto ini berada, dimulai dari nol.
     *
     * Disimpan sebagai nilai tersendiri, bukan disimpulkan dari urutan daftar,
     * karena urutan daftar juga menentukan siapa menutupi siapa. Menaikkan
     * sebuah foto ke tumpukan paling atas mengubah urutannya; kalau lembar
     * ikut disimpulkan dari urutan, foto itu akan melompat ke lembar lain
     * hanya karena disentuh.
     */
    val page: Int = 0,
) {
    /**
     * Rasio foto setelah diputar.
     *
     * Putaran 90 dan 270 derajat menukar sisi panjang dan pendek, jadi inilah
     * rasio yang berlaku untuk menyusun dan mengukur -- bukan [aspect].
     */
    val displayAspect: Float
        get() = if (rotationDegrees % 180 == 0) aspect else 1f / aspect

    /** True kalau foto sedang berdiri tegak lurus dari orientasi aslinya. */
    val isQuarterTurned: Boolean get() = rotationDegrees % 180 != 0
}

/**
 * Memutar foto seperempat putaran, searah jarum jam untuk [quarterTurns] positif.
 *
 * Kotaknya ikut ditukar sisi terhadap pusatnya sendiri, sehingga foto tetap
 * berada di tempat yang sama dan hanya berubah orientasi. Pusat dipakai sebagai
 * poros, bukan sudut kiri-atas, supaya foto tidak melompat saat diputar.
 */
fun SheetItem.rotatedBy(quarterTurns: Int): SheetItem {
    if (quarterTurns == 0) return this
    val turns = ((rotationDegrees / 90) + quarterTurns).mod(4)
    val swap = quarterTurns.mod(2) != 0
    val newRect = if (!swap) rect else RectMm(
        left = rect.centerX - rect.height / 2f,
        top = rect.centerY - rect.width / 2f,
        width = rect.height,
        height = rect.width,
    )
    return copy(rotationDegrees = turns * 90, rect = newRect)
}

/** Seberapa jauh sebuah foto keluar dari area cetak, per sisi. */
data class Overflow(
    val leftMm: Float,
    val topMm: Float,
    val rightMm: Float,
    val bottomMm: Float,
) {
    val any: Boolean
        get() = leftMm > PageLayout.TOLERANCE_MM || topMm > PageLayout.TOLERANCE_MM ||
            rightMm > PageLayout.TOLERANCE_MM || bottomMm > PageLayout.TOLERANCE_MM

    val worstMm: Float get() = maxOf(leftMm, topMm, rightMm, bottomMm)
}

/**
 * Tata letak satu lembar berisi sejumlah foto.
 *
 * Sama seperti [PageLayout], semuanya dalam milimeter supaya pratinjau di layar
 * dan raster yang dikirim ke printer memakai angka yang persis sama.
 */
data class SheetLayout(
    val paperWidthMm: Float,
    val paperHeightMm: Float,
    val printable: RectMm,
    val items: List<SheetItem>,
) {
    fun overflowOf(item: SheetItem): Overflow = Overflow(
        leftMm = max(0f, printable.left - item.rect.left),
        topMm = max(0f, printable.top - item.rect.top),
        rightMm = max(0f, item.rect.right - printable.right),
        bottomMm = max(0f, item.rect.bottom - printable.bottom),
    )

    /** Foto-foto yang sebagian akan terpotong. */
    val clipped: List<SheetItem> get() = items.filter { overflowOf(it).any }

    val hasOverflow: Boolean get() = clipped.isNotEmpty()

    /** Foto paling atas yang menutupi titik itu; null kalau tidak ada. */
    fun itemAt(xMm: Float, yMm: Float): SheetItem? =
        items.lastOrNull { item ->
            xMm >= item.rect.left && xMm <= item.rect.right &&
                yMm >= item.rect.top && yMm <= item.rect.bottom
        }
}

fun computeSheetLayout(
    paperWidthMm: Float,
    paperHeightMm: Float,
    marginMm: Float,
    items: List<SheetItem>,
): SheetLayout {
    val margin = marginMm.coerceIn(0f, min(paperWidthMm, paperHeightMm) / 2f - 1f)
    return SheetLayout(
        paperWidthMm = paperWidthMm,
        paperHeightMm = paperHeightMm,
        printable = RectMm(
            left = margin,
            top = margin,
            width = (paperWidthMm - 2 * margin).coerceAtLeast(1f),
            height = (paperHeightMm - 2 * margin).coerceAtLeast(1f),
        ),
        items = items,
    )
}

// ------------------------------------------------------------- penyusunan

/** Jarak antar foto saat disusun otomatis. */
const val DEFAULT_GAP_MM = 3f

/** Sisi terpendek foto tidak boleh lebih kecil dari ini, supaya tetap terlihat. */
const val MIN_ITEM_MM = 10f

/**
 * Menyusun foto ke dalam kisi yang mengisi area cetak.
 *
 * Rasio tiap foto dijaga, jadi foto diletakkan di tengah selnya masing-masing
 * dan tidak semua sel terisi penuh. Jumlah kolom nol berarti "pilihkan": akar
 * kuadrat dari jumlah foto memberi kisi yang paling mendekati persegi, yang
 * biasanya paling hemat kertas.
 */
fun arrangeInGrid(
    aspects: List<Float>,
    printable: RectMm,
    columns: Int = 0,
    gapMm: Float = DEFAULT_GAP_MM,
): List<RectMm> {
    if (aspects.isEmpty()) return emptyList()

    val cols = if (columns > 0) columns else ceil(sqrt(aspects.size.toDouble())).toInt()
    val rows = ceil(aspects.size / cols.toFloat()).toInt()

    val cellWidth = (printable.width - (cols - 1) * gapMm) / cols
    val cellHeight = (printable.height - (rows - 1) * gapMm) / rows

    return aspects.mapIndexed { index, aspect ->
        val col = index % cols
        val row = index / cols
        val cellLeft = printable.left + col * (cellWidth + gapMm)
        val cellTop = printable.top + row * (cellHeight + gapMm)

        val safeAspect = if (aspect > 0f && aspect.isFinite()) aspect else 1f
        var width = cellWidth
        var height = width / safeAspect
        if (height > cellHeight) {
            height = cellHeight
            width = height * safeAspect
        }

        RectMm(
            left = cellLeft + (cellWidth - width) / 2f,
            top = cellTop + (cellHeight - height) / 2f,
            width = width.coerceAtLeast(1f),
            height = height.coerceAtLeast(1f),
        )
    }
}

/**
 * Membagi foto ke beberapa lembar lalu menyusun tiap lembar sendiri-sendiri.
 *
 * [perSheet] nol atau kurang berarti semuanya tetap pada satu lembar. Tanpa
 * pembagian ini, dua puluh foto pada satu A4 masing-masing hanya sebesar
 * perangko: tersusun rapi, tapi tidak ada gunanya dicetak.
 *
 * Tiap lembar disusun terhadap area cetak yang sama, jadi lembar kedua
 * berbentuk sama dengan lembar pertama walau isinya lebih sedikit.
 */
fun List<SheetItem>.arrangedInGridPaged(
    printable: RectMm,
    perSheet: Int,
    columns: Int = 0,
    gapMm: Float = DEFAULT_GAP_MM,
): List<SheetItem> {
    if (isEmpty()) return this
    val chunk = if (perSheet <= 0) size else perSheet
    return chunked(chunk).flatMapIndexed { page, group ->
        group.arrangedInGrid(printable, columns, gapMm).map { it.copy(page = page) }
    }
}

/**
 * Mengelompokkan foto per lembar, terurut, tanpa lembar kosong.
 *
 * Lembar yang seluruh isinya dihapus hilang dengan sendirinya, jadi tidak ada
 * kertas polos yang ikut keluar hanya karena nomor lembarnya masih tersisa.
 */
fun <T> List<T>.groupedBySheet(pageOf: (T) -> Int): List<List<T>> =
    if (isEmpty()) emptyList()
    else groupBy(pageOf).toSortedMap().values.toList()

/**
 * Menyusun ulang seluruh foto ke kisi, mempertahankan urutan, id, dan putaran.
 *
 * Yang dipakai adalah rasio setelah diputar: foto yang sudah diputar 90 derajat
 * harus disusun sebagai foto tegak, bukan sebagai foto mendatar.
 */
fun List<SheetItem>.arrangedInGrid(
    printable: RectMm,
    columns: Int = 0,
    gapMm: Float = DEFAULT_GAP_MM,
): List<SheetItem> {
    val rects = arrangeInGrid(map { it.displayAspect }, printable, columns, gapMm)
    return mapIndexed { index, item -> item.copy(rect = rects[index]) }
}

// ------------------------------------------------------- ubah satu foto

/**
 * Menggeser foto, dengan batas agar tidak bisa dibuang seluruhnya keluar
 * kertas: pusatnya harus tetap berada di atas kertas.
 */
fun SheetItem.movedBy(
    dxMm: Float,
    dyMm: Float,
    paperWidthMm: Float,
    paperHeightMm: Float,
): SheetItem {
    val centerX = (rect.centerX + dxMm).coerceIn(0f, paperWidthMm)
    val centerY = (rect.centerY + dyMm).coerceIn(0f, paperHeightMm)
    return copy(
        rect = rect.copy(
            left = centerX - rect.width / 2f,
            top = centerY - rect.height / 2f,
        )
    )
}

/**
 * Memperbesar atau memperkecil foto terhadap pusatnya sendiri, rasio dijaga.
 *
 * Batas bawah menjaga foto tetap bisa disentuh; batas atas menjaga agar tidak
 * membengkak sampai jauh melewati kertas dan sulit dikembalikan.
 */
fun SheetItem.scaledBy(
    factor: Float,
    paperWidthMm: Float,
    paperHeightMm: Float,
): SheetItem {
    val maxSide = max(paperWidthMm, paperHeightMm) * 3f
    val shortest = min(rect.width, rect.height)
    val longest = max(rect.width, rect.height)

    val lowerBound = if (shortest <= 0f) 1f else MIN_ITEM_MM / shortest
    val upperBound = if (longest <= 0f) 1f else maxSide / longest
    val applied = factor.coerceIn(min(lowerBound, 1f), max(upperBound, 1f))

    val width = rect.width * applied
    val height = rect.height * applied
    return copy(
        rect = RectMm(
            left = rect.centerX - width / 2f,
            top = rect.centerY - height / 2f,
            width = width,
            height = height,
        )
    )
}

/** Menaikkan foto ke tumpukan paling atas, supaya yang dipilih tidak tertutup. */
fun List<SheetItem>.broughtToFront(id: Long): List<SheetItem> {
    val item = firstOrNull { it.id == id } ?: return this
    return filterNot { it.id == id } + item
}
