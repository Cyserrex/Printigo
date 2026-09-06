package com.escpr.usbprint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.escpr.usbprint.layout.PageLayout
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pratinjau satu halaman cetak yang bisa diatur dengan jari.
 *
 * Semua penempatan datang dari [layout], yang dihitung dalam milimeter oleh
 * `computePageLayout` -- fungsi yang sama persis dipakai jalur cetak. Jadi
 * yang terlihat di sini bukan perkiraan, melainkan hasil hitungan yang identik
 * dengan yang dikirim ke printer.
 *
 * Geser untuk memindahkan, cubit untuk memperbesar, ketuk dua kali untuk
 * mengembalikan ke ukuran muat.
 */
@Composable
fun PagePreview(
    layout: PageLayout,
    content: ImageBitmap?,
    monochrome: Boolean,
    interactive: Boolean,
    onGesture: (panXmm: Float, panYmm: Float, zoom: Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val monoFilter = remember(monochrome) {
        if (monochrome) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        else null
    }
    val measurer = rememberTextMeasurer()

    val scheme = MaterialTheme.colorScheme
    val rulerColor = scheme.onSurfaceVariant
    val safeColor = scheme.primary
    val cutColor = scheme.error
    val paperEdge = scheme.outlineVariant
    val emptyColor = scheme.onSurfaceVariant

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Skala kertas ke piksel layar disimpan di luar Canvas supaya gestur
        // bisa mengubah geseran piksel menjadi milimeter memakai angka yang sama.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val gutterPx = with(density) { RULER_GUTTER.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val paperScale = min(
            (widthPx - gutterPx) / layout.paperWidthMm,
            (heightPx - gutterPx) / layout.paperHeightMm,
        ).coerceAtLeast(0.01f)

        val gestureModifier = if (!interactive) Modifier else Modifier
            .pointerInput(paperScale) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onGesture(pan.x / paperScale, pan.y / paperScale, zoom)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onReset() })
            }

        Canvas(Modifier.fillMaxSize().then(gestureModifier)) {
            val paperWidthPx = layout.paperWidthMm * paperScale
            val paperHeightPx = layout.paperHeightMm * paperScale
            val originX = gutterPx + (size.width - gutterPx - paperWidthPx) / 2f
            val originY = gutterPx + (size.height - gutterPx - paperHeightPx) / 2f

            fun x(mm: Float) = originX + mm * paperScale
            fun y(mm: Float) = originY + mm * paperScale

            val paperRect = Rect(originX, originY, originX + paperWidthPx, originY + paperHeightPx)
            val printableRect = Rect(
                x(layout.printable.left), y(layout.printable.top),
                x(layout.printable.right), y(layout.printable.bottom),
            )

            // Kertas.
            drawRect(Color.White, paperRect.topLeft, paperRect.size)

            // Isi dokumen, dipotong pada tepi kertas.
            if (content != null && layout.hasContent) {
                val contentRect = Rect(
                    x(layout.content.left), y(layout.content.top),
                    x(layout.content.right), y(layout.content.bottom),
                )
                clipRect(
                    paperRect.left, paperRect.top, paperRect.right, paperRect.bottom
                ) {
                    drawImage(
                        image = content,
                        dstOffset = IntOffset(
                            contentRect.left.roundToInt(), contentRect.top.roundToInt()
                        ),
                        dstSize = IntSize(
                            contentRect.width.roundToInt().coerceAtLeast(1),
                            contentRect.height.roundToInt().coerceAtLeast(1),
                        ),
                        colorFilter = monoFilter,
                        filterQuality = FilterQuality.Medium,
                    )

                    // Bagian isi yang keluar dari area cetak diberi warna merah:
                    // itulah yang tidak akan tercetak.
                    if (layout.hasOverflow) {
                        clipRect(
                            printableRect.left, printableRect.top,
                            printableRect.right, printableRect.bottom,
                            clipOp = ClipOp.Difference,
                        ) {
                            drawRect(
                                color = cutColor.copy(alpha = 0.45f),
                                topLeft = contentRect.topLeft,
                                size = contentRect.size,
                            )
                        }
                    }
                }
            }

            // Tepi kertas.
            drawRect(
                color = paperEdge,
                topLeft = paperRect.topLeft,
                size = paperRect.size,
                style = Stroke(width = 1f),
            )

            // Batas area cetak: merah kalau ada yang terpotong.
            val boundary = if (layout.hasOverflow) cutColor else safeColor
            drawRect(
                color = boundary.copy(alpha = if (layout.hasOverflow) 0.95f else 0.55f),
                topLeft = printableRect.topLeft,
                size = printableRect.size,
                style = Stroke(
                    width = if (layout.hasOverflow) 2.5f else 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f), 0f),
                ),
            )

            drawRulers(
                layout = layout,
                paperScale = paperScale,
                originX = originX,
                originY = originY,
                gutter = gutterPx,
                color = rulerColor,
                measurer = measurer,
            )

        }

        // Keadaan kosong ditulis sebagai komponen, bukan digambar ke kanvas:
        // teks di dalam Canvas tidak terbaca pembaca layar.
        if (content == null) {
            Text(
                text = "Belum ada dokumen",
                style = MaterialTheme.typography.bodyMedium,
                color = emptyColor,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = RULER_GUTTER / 2, y = RULER_GUTTER / 2),
            )
        }
    }
}

private val RULER_GUTTER = 18.dp

/**
 * Penggaris milimeter di tepi atas dan kiri.
 *
 * Jarak antar garis dipilih menyesuaikan skala: pada kertas besar yang
 * ditampilkan kecil, garis tiap 5 mm akan menyatu jadi blok abu-abu, jadi
 * yang dipakai 10 mm atau 20 mm.
 */
private fun DrawScope.drawRulers(
    layout: PageLayout,
    paperScale: Float,
    originX: Float,
    originY: Float,
    gutter: Float,
    color: Color,
    measurer: TextMeasurer,
) {
    val minorMm = when {
        paperScale > 3f -> 5f
        paperScale > 1.2f -> 10f
        else -> 20f
    }
    val majorEvery = if (minorMm >= 20f) 100f else 50f
    val labelStyle = TextStyle(fontSize = 8.sp, color = color)

    fun tick(mm: Float, horizontal: Boolean) {
        val major = (mm % majorEvery) < 0.01f
        val length = if (major) gutter * 0.62f else gutter * 0.3f
        if (horizontal) {
            val px = originX + mm * paperScale
            drawLine(
                color = color.copy(alpha = if (major) 0.9f else 0.45f),
                start = Offset(px, originY - length),
                end = Offset(px, originY),
                strokeWidth = 1f,
            )
            if (major && mm > 0f) {
                val measured = measurer.measure(mm.roundToInt().toString(), labelStyle)
                drawText(measured, topLeft = Offset(px + 2f, originY - gutter))
            }
        } else {
            val px = originY + mm * paperScale
            drawLine(
                color = color.copy(alpha = if (major) 0.9f else 0.45f),
                start = Offset(originX - length, px),
                end = Offset(originX, px),
                strokeWidth = 1f,
            )
            if (major && mm > 0f) {
                val measured = measurer.measure(mm.roundToInt().toString(), labelStyle)
                drawText(measured, topLeft = Offset(originX - gutter, px + 1f))
            }
        }
    }

    var mm = 0f
    while (mm <= layout.paperWidthMm + 0.01f) {
        tick(mm, horizontal = true)
        mm += minorMm
    }
    mm = 0f
    while (mm <= layout.paperHeightMm + 0.01f) {
        tick(mm, horizontal = false)
        mm += minorMm
    }

    // Garis dasar penggaris, supaya tepi kertas terbaca sebagai titik nol.
    drawLine(
        color = color.copy(alpha = 0.5f),
        start = Offset(originX, originY),
        end = Offset(originX + layout.paperWidthMm * paperScale, originY),
        strokeWidth = 1f,
    )
    drawLine(
        color = color.copy(alpha = 0.5f),
        start = Offset(originX, originY),
        end = Offset(originX, originY + layout.paperHeightMm * paperScale),
        strokeWidth = 1f,
    )
}
