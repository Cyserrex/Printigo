package com.escpr.usbprint.escpr

/**
 * Kompresi run-length ala ESC/P-R. Bekerja per piksel RGB (3 byte), bukan per byte.
 *
 * Arti byte penghitung n:
 *   n <= 127  -> menyusul (n + 1) piksel apa adanya
 *   n >= 128  -> piksel berikutnya diulang (257 - n) kali
 *
 * Untuk dokumen teks rasionya bisa 50x lebih kecil, jadi ini yang menentukan
 * apakah mencetak lewat USB terasa cepat atau tidak.
 */
object Rle {

    const val BYTES_PER_PIXEL = 3

    /** Ukuran buffer keluaran teraman untuk masukan sepanjang [length] byte. */
    fun maxEncodedSize(length: Int): Int =
        length + length / (128 * BYTES_PER_PIXEL) + BYTES_PER_PIXEL + 2

    /**
     * Meng-encode [length] byte pertama dari [src] ke dalam [dst] mulai [dstOffset].
     * Mengembalikan jumlah byte yang ditulis.
     */
    fun encode(src: ByteArray, length: Int, dst: ByteArray, dstOffset: Int = 0): Int {
        require(length % BYTES_PER_PIXEL == 0) { "panjang baris bukan kelipatan 3" }

        val bpp = BYTES_PER_PIXEL
        var i = 0
        var o = dstOffset

        while (i < length) {
            val a = src[i]
            val b = src[i + 1]
            val c = src[i + 2]

            // Berapa kali piksel ini berulang? Maksimum 129 (batas penghitung).
            var repeat = 1
            var j = i + bpp
            while (j + bpp <= length && repeat < 129 &&
                src[j] == a && src[j + 1] == b && src[j + 2] == c
            ) {
                repeat++
                j += bpp
            }

            if (repeat >= 2) {
                dst[o++] = (257 - repeat).toByte()
                dst[o++] = a
                dst[o++] = b
                dst[o++] = c
                i = j
                continue
            }

            // Tidak berulang: kumpulkan piksel unik, maksimum 128, dan berhenti
            // tepat sebelum run berulang berikutnya supaya run itu bisa dipadatkan.
            var count = 1
            j = i + bpp
            while (j + bpp <= length && count < 128) {
                if (j + 2 * bpp <= length &&
                    src[j] == src[j + bpp] &&
                    src[j + 1] == src[j + bpp + 1] &&
                    src[j + 2] == src[j + bpp + 2]
                ) break
                count++
                j += bpp
            }
            dst[o++] = (count - 1).toByte()
            System.arraycopy(src, i, dst, o, j - i)
            o += j - i
            i = j
        }

        return o - dstOffset
    }

    /** Kebalikan dari [encode]; dipakai unit test. */
    fun decode(src: ByteArray, length: Int = src.size): ByteArray {
        val bpp = BYTES_PER_PIXEL
        val out = ArrayList<Byte>(length * 2)
        var i = 0
        while (i < length) {
            val n = src[i].toInt() and 0xFF
            i++
            if (n <= 127) {
                val bytes = (n + 1) * bpp
                for (k in 0 until bytes) out.add(src[i + k])
                i += bytes
            } else {
                val times = 257 - n
                repeat(times) {
                    out.add(src[i]); out.add(src[i + 1]); out.add(src[i + 2])
                }
                i += bpp
            }
        }
        return out.toByteArray()
    }
}
