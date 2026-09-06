package com.escpr.usbprint.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Warna diambil dari biru ikon aplikasi (#27ACFD).
private val Blue40 = Color(0xFF0061A4)
private val Blue80 = Color(0xFF9ECAFF)
private val BlueGrey40 = Color(0xFF535F70)
private val BlueGrey80 = Color(0xFFBBC7DB)
private val Cyan40 = Color(0xFF00687B)
private val Cyan80 = Color(0xFF5CD5F2)

private val LightColors = lightColorScheme(
    primary = Blue40,
    secondary = BlueGrey40,
    tertiary = Cyan40,
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFDFCFF),
    surfaceVariant = Color(0xFFDFE2EB)
)

private val DarkColors = darkColorScheme(
    primary = Blue80,
    secondary = BlueGrey80,
    tertiary = Cyan80,
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF43474E)
)

/**
 * Android 12 ke atas memakai warna dinamis dari wallpaper pengguna; di bawah
 * itu memakai palet biru yang senada dengan ikon aplikasi.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
