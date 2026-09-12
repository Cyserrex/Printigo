package com.escpr.usbprint.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext

/**
 * Kalimat yang belum diterjemahkan.
 *
 * Aturan yang dijaga oleh tipe ini: bagian yang memutuskan **apa** yang perlu
 * dikatakan tidak boleh ikut memutuskan **bahasa apa** yang dipakai. Sebelum
 * ada dua bahasa, keduanya bisa disatukan tanpa akibat; begitu ada dua,
 * menyatukannya berarti kalimat terbentuk pada saat kejadian dan membeku dalam
 * bahasa yang berlaku saat itu -- sehingga mengganti bahasa hanya mengubah
 * separuh layar.
 *
 * [Raw] ada karena tidak semua yang tampil bisa diterjemahkan: nama berkas,
 * nama perangkat, dan balasan mentah printer datang apa adanya dari luar.
 */
@Immutable
sealed interface UiText {

    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Raw(val value: String) : UiText

    fun resolve(context: Context): String = when (this) {
        is Raw -> value
        is Res -> if (args.isEmpty()) {
            context.getString(id)
        } else {
            context.getString(id, *args.toTypedArray())
        }
    }
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

fun uiText(value: String): UiText = UiText.Raw(value)

/** Bentuk terbaca dari kalimat ini, mengikuti bahasa yang sedang berlaku di layar. */
@Composable
fun UiText.text(): String = resolve(LocalContext.current)
