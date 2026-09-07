package com.escpr.usbprint.util

import java.io.File

/** Satu berkas di cache, cukup datanya saja supaya logikanya bisa diuji tanpa disk. */
data class CachedFile(
    val path: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/**
 * Berapa lama salinan dokumen boleh tinggal di cache.
 *
 * Tujuh hari cukup untuk "cetak lagi berkas yang tadi", dan cukup pendek supaya
 * salinan dokumen orang tidak menetap berbulan-bulan tanpa alasan.
 */
const val CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

/** Batas atas seluruh cache. 256 MB kira-kira 60 foto HP resolusi penuh. */
const val CACHE_BUDGET_BYTES = 256L * 1024 * 1024

/**
 * Berkas semuda ini tidak pernah disentuh, walau cache sudah melewati batas.
 *
 * Sapuan berjalan di latar sementara berkas lain sedang disalin masuk. Berkas
 * yang sedang ditulis belum tercatat sebagai "dipakai" oleh siapa pun, jadi
 * tanpa jeda ini sapuan bisa menghapus berkas yang baru separuh tersalin --
 * dan yang gagal adalah pekerjaan yang sedang dibuka pengguna saat itu juga.
 */
const val CACHE_GRACE_MS = 5L * 60 * 1000

/**
 * Memilih berkas cache mana yang boleh dihapus.
 *
 * Dipisahkan sebagai fungsi murni karena ini kode yang **menghapus berkas
 * orang**. Kalau salah, yang hilang bukan sekadar tampilan: dokumen yang
 * sedang dibuka bisa lenyap di tengah pekerjaan cetak. Jadi keputusannya
 * dibuat di tempat yang bisa diuji tanpa menyentuh disk sama sekali.
 *
 * Dua aturan, dan [keep] mengalahkan keduanya:
 *
 * 1. Yang lebih tua dari [maxAgeMs] dibuang.
 * 2. Kalau sisanya masih melewati [budgetBytes], yang paling lama tidak
 *    disentuh dibuang lebih dulu sampai muat.
 *
 * @param keep jalur berkas yang sedang dipakai; tidak pernah dihapus apa pun
 *             umurnya dan seberapa penuh pun cache-nya.
 * @param graceMs berkas semuda ini juga tidak pernah dihapus; lihat
 *                [CACHE_GRACE_MS].
 */
fun cacheFilesToDelete(
    files: List<CachedFile>,
    keep: Set<String>,
    now: Long,
    maxAgeMs: Long = CACHE_MAX_AGE_MS,
    budgetBytes: Long = CACHE_BUDGET_BYTES,
    graceMs: Long = CACHE_GRACE_MS,
): List<CachedFile> {
    val candidates = files.filter { it.path !in keep && now - it.modifiedAt > graceMs }

    val tooOld = candidates.filter { now - it.modifiedAt > maxAgeMs }
    val doomed = tooOld.toMutableList()

    // Sisa yang masih hidup, termasuk yang dipertahankan: batas ukuran berlaku
    // untuk seluruh isi cache, bukan hanya untuk yang boleh dihapus.
    var total = files.filterNot { it in doomed }.sumOf { it.sizeBytes }
    if (total <= budgetBytes) return doomed

    val byAge = candidates.filterNot { it in doomed }.sortedBy { it.modifiedAt }
    for (file in byAge) {
        if (total <= budgetBytes) break
        doomed += file
        total -= file.sizeBytes
    }
    return doomed
}

/**
 * Menjalankan pembersihan pada direktori cache sungguhan.
 *
 * Hanya berkas hasil [copyToCache] yang dilihat; sisa cache milik pustaka lain
 * bukan urusan kita. Kegagalan menghapus diabaikan dengan sengaja -- berkas
 * yang sedang terkunci akan terhapus pada sapuan berikutnya, dan gagal
 * membersihkan bukan alasan untuk menggagalkan apa pun yang sedang berjalan.
 */
fun sweepCache(cacheDir: File, keep: Set<String>, now: Long = System.currentTimeMillis()): Int {
    val files = (cacheDir.listFiles() ?: return 0)
        .filter { it.isFile && it.name.startsWith(CACHE_PREFIX) }
        .map { CachedFile(it.absolutePath, it.length(), it.lastModified()) }

    val doomed = cacheFilesToDelete(files, keep, now)
    var removed = 0
    doomed.forEach { file ->
        if (runCatching { File(file.path).delete() }.getOrDefault(false)) removed++
    }
    return removed
}
